// Kanade の設定の画面（macOS）。~/Library/Application Support/Kanade の settings.properties・user_dictionary.txt・snippets.txt を書く。
// 保存するとエンジンが 2 秒ほどで読み直す。中身は Linux 版の設定の画面（linux/settings）と同じ
//   Kanade Settings.app --args [input|voice|dictionary|snippets|backup|usage]
import AppKit
import SwiftUI
import UniformTypeIdentifiers

let support = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Kanade")
/** 表示の言語（設定の ui_language。auto は Mac の表示言語に合わせる）。変えたら画面を作り直す */
nonisolated(unsafe) var ja = uiJapanese(nil)

func uiJapanese(_ setting: String?) -> Bool {
    let value = setting ?? ((try? String(contentsOf: support.appendingPathComponent("settings.properties"), encoding: .utf8)) ?? "")
        .components(separatedBy: "\n").first { $0.hasPrefix("ui_language=") }.map { String($0.dropFirst("ui_language=".count)) }
    switch value?.trimmingCharacters(in: .whitespaces) {
    case "ja": return true
    case "en": return false
    default: return (Locale.preferredLanguages.first ?? "").hasPrefix("ja")
    }
}
func T(_ j: String, _ e: String) -> String { ja ? j : e }

/** settings.properties を1行ずつ持ち、変えた行だけ書き換える（コメントと並びはそのまま） */
@MainActor @Observable
final class SettingsFile {
    private let url = support.appendingPathComponent("settings.properties")
    private var lines: [String] = []
    static let defaults: [String: String] = [
        "full_width_symbols": "true", "live_conversion": "true", "punctuation": "、。", "pair_brackets": "true", "emoji_shortcodes": "true",
        "spoken_punctuation": "true", "voice_final": "parakeet", "learning": "true", "ui_language": "auto",
        "voice_final_en": "parakeet-en", "voice_hold_keys": "F12,Alt_R", "voice_toggle_key": "Control+Alt+space",
    ]
    var values: [String: String] = [:]

    init() { load() }

    func load() {
        lines = ((try? String(contentsOf: url, encoding: .utf8)) ?? "").components(separatedBy: "\n")
        var v = Self.defaults
        for l in lines where !l.hasPrefix("#") {
            if let eq = l.firstIndex(of: "=") {
                v[l[..<eq].trimmingCharacters(in: .whitespaces)] = l[l.index(after: eq)...].trimmingCharacters(in: .whitespaces)
            }
        }
        values = v
    }

    func string(_ key: String) -> Binding<String> {
        Binding(get: { self.values[key] ?? "" }, set: { self.set(key, $0) })
    }

    func bool(_ key: String) -> Binding<Bool> {
        Binding(get: { self.values[key] == "true" }, set: { self.set(key, $0 ? "true" : "false") })
    }

    func set(_ key: String, _ value: String) {
        values[key] = value
        if let i = lines.firstIndex(where: { $0.hasPrefix(key + "=") }) {
            lines[i] = "\(key)=\(value)"
        } else {
            if lines.last == "" { lines.removeLast() }
            lines.append("\(key)=\(value)")
            lines.append("")
        }
        try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        try? lines.joined(separator: "\n").write(to: url, atomically: true, encoding: .utf8)
    }
}

/** エンジンへの頼みごと（admin。Android 版の設定の読み込み・書き出し・学習を消す）。返事は (成功したか, 文) */
func engineRequest(_ command: String, _ argument: String) -> (Bool, String) {
    let path = support.appendingPathComponent("engine.sock").path
    let s = socket(AF_UNIX, SOCK_STREAM, 0)
    guard s >= 0 else { return (false, "socket") }
    defer { close(s) }
    var addr = sockaddr_un()
    addr.sun_family = sa_family_t(AF_UNIX)
    let bytes = Array(path.utf8)
    withUnsafeMutableBytes(of: &addr.sun_path) { raw in
        for (i, b) in bytes.enumerated() where i < raw.count - 1 { raw[i] = b }
    }
    let ok = withUnsafePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(s, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
    }
    guard ok == 0 else { return (false, T("Kanade のエンジンが動いていません。入力ソースで Kanade を一度選んでください", "The Kanade engine is not running. Select Kanade in the input menu once.")) }
    func esc(_ f: String) -> String {
        f.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\t", with: "\\t").replacingOccurrences(of: "\n", with: "\\n")
    }
    let line = ["admin", "1", command, argument].map(esc).joined(separator: "\t") + "\n"
    _ = line.withCString { send(s, $0, strlen($0), 0) }
    var tv = timeval(tv_sec: 30, tv_usec: 0)
    setsockopt(s, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
    var data = Data()
    var buf = [UInt8](repeating: 0, count: 4096)
    while true {
        let n = recv(s, &buf, buf.count, 0)
        if n <= 0 { break }
        data.append(contentsOf: buf[0..<n])
        for l in String(decoding: data, as: UTF8.self).components(separatedBy: "\n") where l.hasPrefix("admin\t") {
            let f = l.components(separatedBy: "\t")
            if f.count >= 4 { return (f[2] == "1", f[3].replacingOccurrences(of: "\\n", with: "\n")) }
        }
    }
    return (false, T("返事がありません", "No answer"))
}


let logFile = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/Kanade/engine.log")
let engineBinary = support.appendingPathComponent("engine/bin/kanade-engine")

/**
 * Android 版の形の設定のファイルを読む。エンジンが動いていなければ、エンジンのコマンドで読む（動き出したら読み直す）
 */
func importSettings(_ path: String) -> (Bool, String) {
    let r = engineRequest("import-android", path)
    if r.0 || isEngineAlive() { return r }
    guard FileManager.default.isExecutableFile(atPath: engineBinary.path) else { return r }
    let p = Process()
    p.executableURL = engineBinary
    p.arguments = ["import-android", path]
    let out = Pipe()
    p.standardOutput = out
    p.standardError = out
    do { try p.run() } catch { return (false, error.localizedDescription) }
    p.waitUntilExit()
    let text = String(decoding: out.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        .split(separator: "\n").filter { !$0.hasPrefix("[kanade]") }.joined(separator: "\n")
    return (p.terminationStatus == 0, text.trimmingCharacters(in: .whitespacesAndNewlines))
}

/** ソケットの先でエンジンが待っているか */
func isEngineAlive() -> Bool {
    let s = socket(AF_UNIX, SOCK_STREAM, 0)
    guard s >= 0 else { return false }
    defer { close(s) }
    var addr = sockaddr_un()
    addr.sun_family = sa_family_t(AF_UNIX)
    let bytes = Array(support.appendingPathComponent("engine.sock").path.utf8)
    withUnsafeMutableBytes(of: &addr.sun_path) { raw in
        for (i, b) in bytes.enumerated() where i < raw.count - 1 { raw[i] = b }
    }
    return withUnsafePointer(to: &addr) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(s, $0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
    } == 0
}

/** ユーザー辞書・スニペットの1行（「キー 値」。書式はエンジンの UserDictionary.parsePairs と同じ） */
struct PairEntry: Identifiable, Hashable {
    let line: Int
    let key: String
    let value: String
    var id: Int { line }
}

enum PairFile {
    static func read(_ url: URL) -> String { (try? String(contentsOf: url, encoding: .utf8)) ?? "" }

    static func write(_ url: URL, _ text: String) {
        try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        try? (text.hasSuffix("\n") || text.isEmpty ? text : text + "\n").write(to: url, atomically: true, encoding: .utf8)
    }

    static func entries(_ url: URL) -> [PairEntry] {
        read(url).components(separatedBy: "\n").enumerated().compactMap { i, raw in
            let s = raw.trimmingCharacters(in: .whitespaces)
            if s.isEmpty || s.hasPrefix("#") { return nil }
            guard let cut = s.firstIndex(where: { $0 == " " || $0 == "\t" }), cut > s.startIndex else { return nil }
            let value = s[s.index(after: cut)...].trimmingCharacters(in: .whitespaces)
            return value.isEmpty ? nil : PairEntry(line: i, key: String(s[..<cut]), value: value)
        }
    }

    /** [entry] が nil なら足す、[line] が nil なら消す */
    static func change(_ url: URL, _ entry: PairEntry?, _ line: String?) {
        var lines = read(url).components(separatedBy: "\n")
        if lines.last == "" { lines.removeLast() }
        if let entry, entry.line < lines.count {
            if let line { lines[entry.line] = line } else { lines.remove(at: entry.line) }
        } else if let line {
            lines.append(line)
        }
        write(url, lines.joined(separator: "\n"))
    }
}
