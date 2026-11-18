// 「Kanade を取り除く」: 入力方式・設定の画面・エンジンをゴミ箱へ移す（設定の画面の「設定の保存と読み込み」から）
// pkg が全員分に入れたもの（/Library/Input Methods・/Applications）は、Mac のパスワードを1回求めて移す（do shell script … with administrator privileges）
// 開発用に本人のフォルダーへ入れたもの（~/Library/Input Methods・~/Applications）も一緒に移す
import AppKit

enum Uninstaller {
    static let fm = FileManager.default
    static let home = fm.homeDirectoryForCurrentUser
    static let systemItems = [URL(fileURLWithPath: "/Library/Input Methods/Kanade.app"), URL(fileURLWithPath: "/Applications/Kanade Settings.app")]
    static var userItems: [URL] {
        [home.appendingPathComponent("Library/Input Methods/Kanade.app"), home.appendingPathComponent("Applications/Kanade Settings.app")]
    }
    static var support: URL { home.appendingPathComponent("Library/Application Support/Kanade") }

    enum Outcome { case removed, cancelled, failed(String) }

    /** 取り除く。removeData なら設定・ユーザー辞書・学習・落とした音声のモデル（~/Library/Application Support/Kanade）もゴミ箱へ */
    static func run(removeData: Bool) -> Outcome {
        let f = DateFormatter()
        f.dateFormat = "yyyyMMddHHmmss"
        let trash = home.appendingPathComponent(".Trash/Kanade-\(f.string(from: Date()))")
        // 入力ソースを無効にする（本人のユーザーで）
        if let im = (systemItems + userItems).first(where: { fm.isExecutableFile(atPath: $0.appendingPathComponent("Contents/MacOS/Kanade").path) && $0.lastPathComponent == "Kanade.app" }) {
            exec(im.appendingPathComponent("Contents/MacOS/Kanade").path, ["--uninstall"])
        }
        exec("/usr/bin/pkill", ["-x", "Kanade"])
        exec("/usr/bin/pkill", ["-f", "dev.mooma.kanade.desktop.MainKt serve"])
        // 全員分に入れたもの
        let system = systemItems.filter { fm.fileExists(atPath: $0.path) }
        if !system.isEmpty {
            var cmds = ["/bin/mkdir -p \(q(trash.path))"]
            cmds += system.map { "/bin/mv \(q($0.path)) \(q(trash.path))/" }
            cmds += ["/usr/sbin/chown -R \(getuid()) \(q(trash.path))", "/usr/sbin/pkgutil --forget dev.mooma.pkg.Kanade >/dev/null 2>&1 || true"]
            let shell = cmds.joined(separator: " && ").replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
            var error: NSDictionary?
            NSAppleScript(source: "do shell script \"\(shell)\" with administrator privileges")?.executeAndReturnError(&error)
            if let error {
                if (error[NSAppleScript.errorNumber] as? Int) == -128 { return .cancelled }
                return .failed(error[NSAppleScript.errorMessage] as? String ?? "\(error)")
            }
        }
        // 本人のフォルダーのもの
        do {
            try fm.createDirectory(at: trash, withIntermediateDirectories: true)
            for u in userItems where fm.fileExists(atPath: u.path) {
                try fm.moveItem(at: u, to: trash.appendingPathComponent(u.lastPathComponent))
            }
            if removeData {
                if fm.fileExists(atPath: support.path) { try fm.moveItem(at: support, to: trash.appendingPathComponent("Application Support")) }
            } else {
                // エンジン（実行環境・辞書）だけ移し、落とした音声のモデル（engine/data の中のフォルダー）と設定は残す
                let engine = support.appendingPathComponent("engine")
                for d in ["bin", "lib", "native", "runtime", "VERSION"] where fm.fileExists(atPath: engine.appendingPathComponent(d).path) {
                    try fm.createDirectory(at: trash.appendingPathComponent("engine"), withIntermediateDirectories: true)
                    try fm.moveItem(at: engine.appendingPathComponent(d), to: trash.appendingPathComponent("engine/\(d)"))
                }
            }
        } catch {
            return .failed(error.localizedDescription)
        }
        return .removed
    }

    private static func q(_ s: String) -> String { "'" + s.replacingOccurrences(of: "'", with: "'\\''") + "'" }

    private static func exec(_ path: String, _ args: [String]) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: path)
        p.arguments = args
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        try? p.run()
        p.waitUntilExit()
    }
}
