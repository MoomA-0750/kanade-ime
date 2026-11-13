// Kanade の設定の画面（macOS）。~/Library/Application Support/Kanade の settings.properties・user_dictionary.txt・snippets.txt を書く。
// 保存するとエンジンが 2 秒ほどで読み直す。中身は Linux 版の設定の画面（linux/settings）と同じ
//   Kanade Settings.app --args [input|voice|dictionary|snippets|backup]
import AppKit
import SwiftUI
import UniformTypeIdentifiers

let support = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Kanade")
let ja = (Locale.preferredLanguages.first ?? "").hasPrefix("ja")
func T(_ j: String, _ e: String) -> String { ja ? j : e }

/** settings.properties を1行ずつ持ち、変えた行だけ書き換える（コメントと並びはそのまま） */
@MainActor @Observable
final class SettingsFile {
    private let url = support.appendingPathComponent("settings.properties")
    private var lines: [String] = []
    static let defaults: [String: String] = [
        "full_width_symbols": "true", "live_conversion": "true", "punctuation": "、。", "pair_brackets": "true",
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

enum Page: String, CaseIterable, Identifiable {
    case input, voice, dictionary, snippets, backup
    var id: String { rawValue }
    var title: String {
        switch self {
        case .input: T("入力", "Input")
        case .voice: T("音声入力", "Voice input")
        case .dictionary: T("ユーザー辞書", "User dictionary")
        case .snippets: T("スニペット", "Snippets")
        case .backup: T("設定の保存と読み込み", "Backup and restore")
        }
    }
    var symbol: String {
        switch self {
        case .input: "keyboard"
        case .voice: "mic"
        case .dictionary: "character.book.closed"
        case .snippets: "text.badge.plus"
        case .backup: "arrow.up.arrow.down"
        }
    }
}

@main
struct KanadeSettingsApp: App {
    var body: some Scene {
        Window(T("Kanade の設定", "Kanade Settings"), id: "main") {
            ContentView()
        }
        .defaultSize(width: 760, height: 560)
    }
}

struct ContentView: View {
    @State private var settings = SettingsFile()
    @State private var page: Page? = Page(rawValue: CommandLine.arguments.dropFirst().first { !$0.hasPrefix("-") } ?? "") ?? .input

    var body: some View {
        NavigationSplitView {
            List(Page.allCases, selection: $page) { p in
                Label(p.title, systemImage: p.symbol).tag(p)
            }
            .navigationSplitViewColumnWidth(min: 190, ideal: 210)
        } detail: {
            switch page ?? .input {
            case .input: InputPage(settings: settings)
            case .voice: VoicePage(settings: settings)
            case .dictionary: FilePage(file: "user_dictionary.txt", help: T("1行に「よみ<タブ>語」。# で始まる行は注釈", "One entry per line: reading<Tab>word. Lines starting with # are comments"))
            case .snippets: FilePage(file: "snippets.txt", help: T("1行に「打つ文字<タブ>置き換える文」。直接入力のときに打つと置き換わる", "One per line: trigger<Tab>replacement. Typing the trigger replaces it in direct input"))
            case .backup: BackupPage()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in settings.load() }
    }
}

struct InputPage: View {
    @Bindable var settings: SettingsFile
    var body: some View {
        Form {
            Section {
                Toggle(T("ライブ変換", "Live conversion"), isOn: settings.bool("live_conversion"))
                Toggle(T("数字・記号を全角にする", "Full-width numbers and symbols"), isOn: settings.bool("full_width_symbols"))
                Picker(T("句読点", "Punctuation"), selection: settings.string("punctuation")) {
                    ForEach(["、。", "，．", "、．", "，。"], id: \.self) { Text($0).tag($0) }
                }
                Toggle(T("閉じ括弧も入れる", "Insert closing brackets"), isOn: settings.bool("pair_brackets"))
                Toggle(T("選んだ変換を覚える", "Learn selected conversions"), isOn: settings.bool("learning"))
            }
            Section {
                Picker(T("画面の言語", "Display language"), selection: settings.string("ui_language")) {
                    Text(T("Mac に合わせる", "Same as Mac")).tag("auto")
                    Text("日本語").tag("ja")
                    Text("English").tag("en")
                }
            } footer: {
                Text(T("英字を打つときは、入力ソースを ABC などに切り替えます（Control+Space、地球儀キー、JIS 配列の英数キー）",
                       "To type English, switch the input source to ABC (Control+Space, the Globe key, or the Eisu key on JIS keyboards)"))
            }
        }
        .formStyle(.grouped)
        .navigationTitle(Page.input.title)
    }
}

struct VoicePage: View {
    @Bindable var settings: SettingsFile
    private let data = support.appendingPathComponent("engine/data")

    private func ready(_ dir: String) -> Bool { FileManager.default.fileExists(atPath: data.appendingPathComponent(dir).path) }

    private func label(_ name: String, _ dir: String) -> String {
        ready(dir) ? name : name + T("（未取得）", " (not downloaded)")
    }

    var body: some View {
        Form {
            Section {
                TextField(T("押している間だけ録音するキー", "Hold to record"), text: settings.string("voice_hold_keys"))
                TextField(T("押すたびに録音を始める・終えるキー", "Press to start/stop"), text: settings.string("voice_toggle_key"))
            } footer: {
                Text(T("キーの名前をカンマ区切りで（F12・Alt_R＝右 Option・Control_R・Super_R＝右 Command など）。組み合わせは Control+Alt+space のように書く。録音中の Esc で取り消す。どの入力ソースでも効き、アクセシビリティの許可が要る",
                       "Comma-separated key names (F12, Alt_R = right Option, Control_R, Super_R = right Command…). Combos like Control+Alt+space. Esc cancels. Works in any input source and needs Accessibility permission"))
            }
            Section {
                Picker(T("日本語の文を作るモデル", "Japanese model"), selection: settings.string("voice_final")) {
                    Text(label("Parakeet（日本語）", "parakeet-ja")).tag("parakeet")
                    Text(label("ReazonSpeech", "reazonspeech")).tag("reazon")
                    Text(label("SenseVoice", "sensevoice-2024-07-17")).tag("sensevoice")
                    Text(label("Whisper small", "ggml-small-q5_1.bin")).tag("whisper-small")
                    Text(label("Kotoba-Whisper", "ggml-kotoba-whisper-v2.0-q5_0.bin")).tag("whisper-kotoba")
                    Text(label("Whisper large-v3-turbo", "ggml-large-v3-turbo-q5_0.bin")).tag("whisper-turbo")
                }
                Picker(T("英語の文を作るモデル", "English model"), selection: settings.string("voice_final_en")) {
                    Text(label("Parakeet v3", "parakeet-v3")).tag("parakeet-en")
                    Text(label("Whisper small", "ggml-small-q5_1.bin")).tag("whisper-small")
                    Text(label("Whisper large-v3-turbo", "ggml-large-v3-turbo-q5_0.bin")).tag("whisper-turbo")
                }
                Toggle(T("「まる」「てん」を句読点にする", "Spoken punctuation (Japanese)"), isOn: settings.bool("spoken_punctuation"))
            } footer: {
                Text(T("Kanade のときは日本語、ほかの入力ソースのときは英語のモデルで書く。Parakeet と ReazonSpeech は install.sh --voice で落とせる。ほかは engine/data に自分で置く",
                       "Japanese in Kanade, English in other input sources. Parakeet and ReazonSpeech come with install.sh --voice; put others in engine/data yourself"))
            }
        }
        .formStyle(.grouped)
        .navigationTitle(Page.voice.title)
    }
}

struct FilePage: View {
    let file: String
    let help: String
    @State private var text = ""
    @State private var saved = ""

    private var url: URL { support.appendingPathComponent(file) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(help).font(.callout).foregroundStyle(.secondary)
            TextEditor(text: $text)
                .font(.body.monospaced())
                .scrollContentBackground(.hidden)
                .padding(6)
                .background(.background, in: .rect(cornerRadius: 8))
        }
        .padding()
        .navigationTitle(file == "snippets.txt" ? Page.snippets.title : Page.dictionary.title)
        .toolbar {
            ToolbarItem {
                Button(T("保存", "Save")) { save() }
                    .keyboardShortcut("s")
                    .disabled(text == saved)
            }
        }
        .task(id: file) { load() }
    }

    private func load() {
        text = (try? String(contentsOf: url, encoding: .utf8)) ?? ""
        saved = text
    }

    private func save() {
        try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        try? text.write(to: url, atomically: true, encoding: .utf8)
        saved = text
    }
}

struct BackupPage: View {
    @State private var message = ""
    @State private var busy = false
    @State private var confirmClear = false

    var body: some View {
        Form {
            Section {
                Button(T("Android 版の設定を読み込む…", "Import Android settings…")) { importAndroid() }
                Button(T("Android 版の形で書き出す…", "Export in Android format…")) { exportAndroid() }
            } footer: {
                Text(T("Android 版の「設定の書き出し」の JSON。ユーザー辞書とスニペットも入る",
                       "The JSON from Android's “Export settings”, including the user dictionary and snippets"))
            }
            Section {
                Button(T("覚えた変換を消す…", "Clear learned conversions…"), role: .destructive) { confirmClear = true }
            }
            if !message.isEmpty {
                Section { Text(message).textSelection(.enabled) }
            }
        }
        .formStyle(.grouped)
        .disabled(busy)
        .navigationTitle(Page.backup.title)
        .confirmationDialog(T("覚えた変換を消しますか", "Clear learned conversions?"), isPresented: $confirmClear) {
            Button(T("消す", "Clear"), role: .destructive) { run("clear-learning", "") }
        }
    }

    private func run(_ command: String, _ argument: String) {
        busy = true
        Task.detached {
            let (_, text) = engineRequest(command, argument)
            await MainActor.run {
                message = text
                busy = false
            }
        }
    }

    private func importAndroid() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.json]
        if panel.runModal() == .OK, let url = panel.url { run("import-android", url.path) }
    }

    private func exportAndroid() {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.json]
        panel.nameFieldStringValue = "kanade-settings-mac.json"
        if panel.runModal() == .OK, let url = panel.url { run("export-android", url.path) }
    }
}
