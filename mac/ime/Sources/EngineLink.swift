// エンジン（desktop の常駐する JVM）への AF_UNIX のソケットのつなぎ。やりとりは Windows の DLL・fcitx5 のアドオンと同じ
// （docs/WINDOWS-PROTOCOL.md）。入力方式のアプリは1つのプロセスなので、つなぎも1本。すべて主スレッドで扱う
import AppKit

/** エンジンからの命令を書く相手（いまの入力欄の入力方式、または入力方式に関わらない音声入力の書き先） */
protocol CommandTarget: AnyObject {
    func apply(_ commands: [[String]])
}

final class EngineLink {
    static let shared = EngineLink()

    private var fd: Int32 = -1
    private var buffer = Data()
    private var pending: [Int: [[String]]] = [:]
    private var late = Set<Int>()
    private var nextId = 1
    private var waiting = -1
    private var answered: Bool?
    private var timeouts = 0
    private var source: DispatchSourceRead?
    private var launched: Date?

    /** いまの入力欄（Kanade が選ばれているとき）。なければ id 0 の命令は [fallback] へ */
    weak var target: CommandTarget?
    /** Kanade が選ばれていないとき（ほかの入力方式で音声入力を使ったとき）の書き先 */
    var fallback: CommandTarget?
    /** エンジンの今のモード（日本語か）。分かるまで nil */
    var japanese: Bool? { didSet { if let j = japanese { onModeChange?(j) } } }
    /** モードが変わった（メニューバーの項目を映す） */
    var onModeChange: ((Bool) -> Void)?
    /** 録音キーの設定が届いた（つないだときと、設定を変えたとき） */
    var onVoiceKeys: ((String, String, String) -> Void)?
    /** 音声入力の様子（off・hold・listen）が届いた。エンジンが自分で終えることがあるので、これで Esc を取るかを決める */
    var onVoiceState: ((String) -> Void)?
    /** つながった・切れた */
    var onConnected: (() -> Void)?

    var connected: Bool { fd >= 0 }

    @discardableResult
    func ensureConnected() -> Bool {
        if fd >= 0 { return true }
        let s = socket(AF_UNIX, SOCK_STREAM, 0)
        if s < 0 { return false }
        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let path = Paths.socket
        let bytes = Array(path.utf8)
        let capacity = MemoryLayout.size(ofValue: addr.sun_path)
        if bytes.count >= capacity {
            close(s)
            return false
        }
        withUnsafeMutableBytes(of: &addr.sun_path) { raw in
            for (i, b) in bytes.enumerated() { raw[i] = b }
            raw[bytes.count] = 0
        }
        addr.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
        let ok = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(s, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
        }
        if ok != 0 {
            close(s)
            launchEngine()
            return false
        }
        var on: Int32 = 1
        setsockopt(s, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        _ = fcntl(s, F_SETFL, fcntl(s, F_GETFL) | O_NONBLOCK)
        fd = s
        buffer.removeAll()
        pending.removeAll()
        late.removeAll()
        timeouts = 0
        let src = DispatchSource.makeReadSource(fileDescriptor: s, queue: .main)
        src.setEventHandler { [weak self] in _ = self?.readAvailable() }
        src.resume()
        source = src
        send(["hello", "1", String(getpid()), "macos"])
        klog("connected to \(path)")
        onConnected?()
        return true
    }

    /** エンジンが動いていなければ起こす（最初のキーで）。続けて何度も起こさない */
    private func launchEngine() {
        if let t = launched, Date().timeIntervalSince(t) < 30 { return }
        launched = Date()
        let path = Paths.engine
        guard FileManager.default.isExecutableFile(atPath: path) else {
            klog("engine not found: \(path)")
            return
        }
        klog("starting \(path)")
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = ["serve"]
        var env = ProcessInfo.processInfo.environment
        if env["LANG"] == nil { env["LANG"] = L.ja ? "ja_JP.UTF-8" : "en_US.UTF-8" }
        p.environment = env
        p.standardInput = FileHandle.nullDevice
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        do { try p.run() } catch { klog("cannot start engine: \(error)") }
    }

    func disconnect() {
        source?.cancel()
        source = nil
        if fd >= 0 { close(fd) }
        fd = -1
        onConnected?()
    }

    func send(_ fields: [String]) {
        guard fd >= 0 else { return }
        let data = Array((Wire.encode(fields) + "\n").utf8)
        var off = 0
        while off < data.count {
            let n = data[off...].withUnsafeBytes { Darwin.send(fd, $0.baseAddress, $0.count, 0) }
            if n > 0 {
                off += n
            } else if n < 0 && (errno == EAGAIN || errno == EINTR) {
                var p = pollfd(fd: fd, events: Int16(POLLOUT), revents: 0)
                poll(&p, 1, 100)
            } else {
                disconnect()
                return
            }
        }
    }

    func newId() -> Int {
        let id = nextId
        nextId += 1
        return id
    }

    /** 届いている分を読んで、1行ずつ処理する。切れたら false */
    @discardableResult
    private func readAvailable() -> Bool {
        var buf = [UInt8](repeating: 0, count: 8192)
        while fd >= 0 {
            let n = recv(fd, &buf, buf.count, 0)
            if n > 0 {
                buffer.append(contentsOf: buf[0..<n])
                continue
            }
            if n < 0 && (errno == EAGAIN || errno == EINTR) { break }
            klog("engine closed the connection")
            disconnect()
            return false
        }
        while let nl = buffer.firstIndex(of: 0x0A) {
            let lineData = buffer[buffer.startIndex..<nl]
            buffer.removeSubrange(buffer.startIndex...nl)
            if let line = String(data: lineData, encoding: .utf8), !line.isEmpty { onLine(line) }
        }
        return fd >= 0
    }

    /** [id] の返事（done）が来るまで待つ。来たら consumed、時間切れなら nil */
    func waitFor(_ id: Int, timeoutMs: Int = 300) -> Bool? {
        waiting = id
        answered = nil
        let deadline = Date().addingTimeInterval(Double(timeoutMs) / 1000)
        while answered == nil && fd >= 0 {
            let left = Int(deadline.timeIntervalSinceNow * 1000)
            if left <= 0 { break }
            var p = pollfd(fd: fd, events: Int16(POLLIN), revents: 0)
            if poll(&p, 1, Int32(left)) <= 0 { continue }
            if !readAvailable() { break }
        }
        waiting = -1
        return answered
    }

    /** 返事を待つ行を送る。返事が来なかったら、あとで届いたときに書く（late）。3回続いたら切る */
    func request(_ id: Int, _ fields: [String]) -> Bool? {
        send(fields)
        let r = waitFor(id)
        if r == nil {
            timeouts += 1
            if timeouts >= 3 {
                klog("engine does not answer; disconnecting")
                disconnect()
            } else {
                late.insert(id)
            }
        }
        return r
    }

    private func onLine(_ line: String) {
        let f = Wire.decode(line)
        if f[0] == "c", f.count >= 5, f[2] == "voicekeys" {
            onVoiceKeys?(f[3], f[4], f.count >= 6 ? f[5] : "KANADE")
        } else if f[0] == "c", f.count >= 4, f[2] == "voicestate" {
            onVoiceState?(f[3])
        } else if f[0] == "c", f.count >= 3 {
            pending[Int(f[1]) ?? 0, default: []].append(Array(f[2...]))
        } else if f[0] == "done", f.count >= 3 {
            let id = Int(f[1]) ?? 0
            let commands = pending.removeValue(forKey: id) ?? []
            timeouts = 0
            let mine = id == waiting
            let wasLate = late.remove(id) != nil
            if id == 0 || mine || wasLate {
                if let t = target {
                    t.apply(commands)
                } else {
                    // Kanade が選ばれていないときも、メニューバーの項目のためにモードは覚える
                    for c in commands where c.count >= 2 && c[0] == "mode" { japanese = c[1] == "1" }
                    fallback?.apply(commands)
                }
            }
            if mine { answered = f[2] == "1" }
        }
    }
}
