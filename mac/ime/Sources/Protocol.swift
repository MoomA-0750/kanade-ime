// エンジンとのやりとりの1行（タブ区切り、欄の中の \ タブ 改行 CR は \\ \t \n \r）。docs/WINDOWS-PROTOCOL.md
import Foundation

enum Wire {
    static func encode(_ fields: [String]) -> String {
        fields.map { f in
            var s = ""
            for c in f.unicodeScalars {
                switch c {
                case "\\": s += "\\\\"
                case "\t": s += "\\t"
                case "\n": s += "\\n"
                case "\r": s += "\\r"
                default: s.unicodeScalars.append(c)
                }
            }
            return s
        }.joined(separator: "\t")
    }

    static func decode(_ line: String) -> [String] {
        var fields: [String] = []
        var cur = ""
        var escape = false
        for c in line.unicodeScalars {
            if escape {
                switch c {
                case "t": cur += "\t"
                case "n": cur += "\n"
                case "r": cur += "\r"
                default: cur.unicodeScalars.append(c)
                }
                escape = false
            } else if c == "\\" {
                escape = true
            } else if c == "\t" {
                fields.append(cur)
                cur = ""
            } else {
                cur.unicodeScalars.append(c)
            }
        }
        fields.append(cur)
        return fields
    }
}

/** 置き場所（エンジンの desktop の Os.kt と同じ） */
enum Paths {
    static let support = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Kanade")
    static var socket: String { ProcessInfo.processInfo.environment["KANADE_SOCKET"] ?? support.appendingPathComponent("engine.sock").path }
    static var engine: String { ProcessInfo.processInfo.environment["KANADE_ENGINE"] ?? support.appendingPathComponent("engine/bin/kanade-engine").path }
    static let log = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/Kanade/ime.log")
}

/** 表示の言語（エンジンの ui_language=auto と同じく、表示言語が日本語なら日本語） */
enum L {
    static let ja = (Locale.preferredLanguages.first ?? "").hasPrefix("ja")
    static func t(_ ja: String, _ en: String) -> String { self.ja ? ja : en }
}

/** 入力方式の記録（~/Library/Logs/Kanade/ime.log。256KB を超えたら前のものは捨てる） */
func klog(_ s: String) {
    let url = Paths.log
    try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss"
    let line = "[ime] \(f.string(from: Date())) \(s)\n"
    if let size = (try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? Int, size > 256_000 {
        try? FileManager.default.removeItem(at: url)
    }
    if let h = try? FileHandle(forWritingTo: url) {
        h.seekToEndOfFile()
        h.write(line.data(using: .utf8)!)
        try? h.close()
    } else {
        try? line.data(using: .utf8)!.write(to: url)
    }
}
