// 設定の画面の入口と、各ページ（入力・音声入力・ユーザー辞書・スニペット・保存と読み込み・使い方）。中身は Linux 版の設定の画面と同じ
import AppKit
import SwiftUI
import UniformTypeIdentifiers

enum Page: String, CaseIterable, Identifiable {
    case input, voice, dictionary, snippets, backup, usage, attention
    var id: String { rawValue }
    /** サイドバーにいつも並ぶページ（attention は確かめてほしいことがあるときだけ上に出る） */
    static let main: [Page] = [.input, .voice, .dictionary, .snippets, .backup, .usage]
    var title: String {
        switch self {
        case .attention: T("確かめてほしいこと", "Needs your attention")
        case .input: T("入力", "Input")
        case .voice: T("音声入力", "Voice input")
        case .dictionary: T("ユーザー辞書", "User dictionary")
        case .snippets: T("スニペット", "Snippets")
        case .backup: T("設定の保存と読み込み", "Backup and restore")
        case .usage: T("使い方", "How to use")
        }
    }
    var symbol: String {
        switch self {
        case .input: "keyboard"
        case .voice: "mic"
        case .dictionary: "character.book.closed"
        case .snippets: "text.badge.plus"
        case .backup: "arrow.up.arrow.down"
        case .usage: "questionmark.circle"
        case .attention: "exclamationmark.triangle.fill"
        }
    }
}

/** 開くページ（起動の引数。開いたまま入力メニューから選んだときは、アプリへの URL で届く） */
@MainActor @Observable
final class Navigation {
    static let shared = Navigation()
    var page: Page? = Page(rawValue: CommandLine.arguments.dropFirst().first { !$0.hasPrefix("-") } ?? "") ?? .input
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    func applicationDidFinishLaunching(_ notification: Notification) {
        // 開発用: --snapshot <フォルダ> で、全部のページを明るい・暗い見た目で撮って PNG に書き、終わる（ssh からは画面を撮れないため）。
        // サイドバーのガラスは窓の外（WindowServer）で合わせて描くので写らない
        let args = CommandLine.arguments
        if let i = args.firstIndex(of: "--snapshot"), i + 1 < args.count {
            Task { @MainActor in await Self.snapshot(to: URL(fileURLWithPath: args[i + 1])) }
        }
        // 開発用: --download <モデル> <結果のファイル> で、画面と同じ処理でモデルを落とし（2 秒で一度止めて続きから）、結果を書いて終わる
        if let i = args.firstIndex(of: "--download"), i + 2 < args.count, let model = voiceModels.first(where: { $0.id == args[i + 1] }) {
            let out = URL(fileURLWithPath: args[i + 2])
            Task {
                let first = Task.detached { await Downloads.download(model) { _ in } }
                try? await Task.sleep(for: .seconds(2))
                first.cancel()
                let stopped = await first.value
                let part = ((try? FileManager.default.attributesOfItem(atPath: model.target(model.files[0].0).path + ".part"))?[.size] as? Int64) ?? 0
                let second = await Downloads.download(model) { _ in }
                try? "stopped=\(stopped) part=\(part) resumed=\(second) ready=\(model.ready)\n".write(to: out, atomically: true, encoding: .utf8)
                await MainActor.run { NSApp.terminate(nil) }
            }
        }
    }

    @MainActor
    static func snapshot(to dir: URL) async {
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? await Task.sleep(for: .seconds(1))
        for (name, look) in [("light", NSAppearance.Name.aqua), ("dark", NSAppearance.Name.darkAqua)] {
            NSApp.appearance = NSAppearance(named: look)
            for page in Page.allCases {
                Navigation.shared.page = page
                try? await Task.sleep(for: .milliseconds(900))
                guard let window = NSApp.windows.first(where: { $0.isVisible }), let view = window.contentView?.superview ?? window.contentView,
                      let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { continue }
                view.cacheDisplay(in: view.bounds, to: rep)
                try? rep.representation(using: .png, properties: [:])?.write(to: dir.appendingPathComponent("\(page.rawValue)-\(name).png"))
            }
        }
        NSApp.terminate(nil)
    }
}

@main
struct KanadeSettingsApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate

    var body: some Scene {
        Window(T("Kanade の設定", "Kanade Settings"), id: "main") {
            ContentView()
                .onOpenURL { url in
                    // kanade-settings://voice など（engine/bin/kanade-settings が開いたままの画面のページを変える）
                    if let p = Page(rawValue: url.host() ?? "") { Navigation.shared.page = p }
                }
        }
        .defaultSize(width: 820, height: 600)
    }
}

struct ContentView: View {
    @State private var settings = SettingsFile()
    @Bindable private var nav = Navigation.shared
    @Bindable private var attention = Attention.shared

    var body: some View {
        NavigationSplitView {
            List(selection: $nav.page) {
                if !attention.issues.isEmpty {
                    Section { AttentionRow(issues: attention.issues).tag(Page.attention) }
                }
                Section {
                    ForEach(Page.main) { p in
                        Label(p.title, systemImage: p.symbol).tag(p)
                    }
                }
            }
            .navigationSplitViewColumnWidth(min: 200, ideal: 220)
        } detail: {
            Group {
                switch nav.page ?? .input {
                case .input: InputPage(settings: settings)
                case .voice: VoicePage(settings: settings)
                case .dictionary: PairPage(snippets: false)
                case .snippets: PairPage(snippets: true)
                case .backup: BackupPage()
                case .usage: UsagePage()
                case .attention: AttentionPage()
                }
            }
            .id((nav.page ?? .input).rawValue + (settings.values["ui_language"] ?? ""))
        }
        .id(settings.values["ui_language"] ?? "auto")
        .onChange(of: settings.values["ui_language"]) { _, value in ja = uiJapanese(value ?? "auto") }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            settings.load()
            attention.refresh(settings: settings.values)
        }
        .onChange(of: settings.values) { _, values in attention.refresh(settings: values) }
    }
}

// ---- 入力 ----

struct InputPage: View {
    @Bindable var settings: SettingsFile
    @State private var confirmClear = false
    @State private var message: String?

    var body: some View {
        Form {
            Section(T("変換", "Conversion")) {
                Toggle(isOn: settings.bool("live_conversion")) {
                    Text(T("ライブ変換", "Live conversion"))
                    Text(T("打つたびに変換した結果を出します。切ると Space を押すまでかなのままです",
                           "Shows the converted text as you type. When off, text stays in kana until you press Space"))
                }
                Picker(selection: settings.string("zenz_mode")) {
                    Text(T("確かめて直す", "Verify")).tag("CORRECT")
                    Text(T("並べ替え", "Rerank")).tag("RERANK")
                } label: {
                    Text(T("zenz の使い方", "zenz mode"))
                    Text(T("「確かめて直す」は軽く、「並べ替え」は打つたびに CPU を多く使います",
                           "Verify is light; Rerank uses much more CPU on each keystroke"))
                }
                Toggle(isOn: settings.bool("full_width_symbols")) {
                    Text(T("数字・記号を全角にする", "Full-width numbers and symbols"))
                    Text(T("日本語入力中の数字・記号・大文字を全角で入れます（Space は半角のまま）",
                           "Types numbers, symbols and capital letters in full width while typing Japanese (Space stays half width)"))
                }
                Picker(selection: settings.string("punctuation")) {
                    // 「、。」だけでは小さく読みにくいので、1つずつ鉤括弧で囲んで出す
                    ForEach(["、。", "，．", "、．", "，。"], id: \.self) { p in Text(p.map { "「\($0)」" }.joined()).tag(p) }
                } label: {
                    Text(T("句読点", "Punctuation"))
                    Text(T("「,」と「.」で入る句読点です", "The marks typed with the comma and period keys"))
                }
                Toggle(isOn: settings.bool("pair_brackets")) {
                    Text(T("括弧を対で入れる", "Insert brackets in pairs"))
                    Text(T("開き括弧を打つと閉じ括弧も入れ、カーソルを中に置きます。空の括弧で Space を押すと括弧の種類が替わります",
                           "Typing an opening bracket also inserts the closing one and puts the cursor between them. Press Space in empty brackets to change their kind"))
                }
                Toggle(isOn: settings.bool("emoji_shortcodes")) {
                    Text(T("絵文字を名前で入れる", "Emoji by name"))
                    Text(T("入り口の文字（既定は :）に続けて絵文字の英語の名前を打つと、候補から選べます（:rocket → 🚀）。名前は Slack・GitHub と同じ。Enter・数字・入り口の文字で入り、Esc でふつうの文字に戻ります",
                           "Type the start character (: by default) and an emoji name to pick it from the candidates (:rocket → 🚀). Names are the same as on Slack and GitHub. Enter, a number or the start character inserts it; Esc types the letters as usual"))
                }
                Picker(selection: settings.string("emoji_prefix")) {
                    ForEach([":", ";", "/", "@", "!"], id: \.self) { p in Text(p).tag(p) }
                } label: {
                    Text(T("絵文字の名前の入り口の文字", "Character that starts an emoji name"))
                }
                Toggle(isOn: settings.bool("emoji_prefix_twice")) {
                    Text(T("2回続けて打ったときだけ始める", "Start only when typed twice"))
                    Text(T("入り口の文字を1回打つとふつうの文字になり、2回続けて打つと絵文字の名前を打てます（::rocket → 🚀）",
                           "Typing the start character once types it as usual; typing it twice in a row starts an emoji name (::rocket → 🚀)"))
                }
            }
            Section {
                Picker(selection: settings.string("key_style")) {
                    Text(T("macOS と同じ", "Same as macOS")).tag("MAC")
                    Text(T("Windows 版・Android 版と同じ", "Same as Windows and Android")).tag("PC")
                } label: {
                    Text(T("入力中の Control のキー", "Control keys while typing"))
                    Text(settings.values["key_style"] == "PC"
                         ? T("Control+U・I・O・P・T で、ひらがな・カタカナ・半角カタカナ・全角英数・半角英数にします（MS-IME と同じ）",
                             "Control+U, I, O, P and T change the text to hiragana, katakana, half-width katakana, full-width and half-width letters (as in Microsoft IME)")
                         : T("Control+J・K・L・; で、ひらがな・カタカナ・全角英字・半角英字にします（Control+: も半角英字）",
                             "Control+J, K, L and ; change the text to hiragana, katakana, full-width and half-width letters (Control+: also gives half-width letters)"))
                }
            } header: {
                Text(T("キーの割り当て", "Key assignments"))
            } footer: {
                Text(T("F6〜F10 はどちらでも使えます", "F6 to F10 work either way"))
            }
            Section {
                LabeledContent(T("切り替えのキーを変える", "Change the switching keys")) {
                    Button(T("キーボードの設定を開く", "Open Keyboard settings")) {
                        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.Keyboard-Settings.extension")!)
                    }
                }
            } header: {
                Text(T("日本語と英字の切り替え", "Switching between Japanese and English"))
            } footer: {
                Text(T("「Kanade ひらがな」と「Kanade 英字」は macOS の入力ソースのモードです。入力メニュー・Control+Space・地球儀キーで切り替えます。JIS 配列のかな・英数キー、F13 でも切り替わります",
                       "Kanade Hiragana and Kanade English are input modes of the macOS input source. Switch with the input menu, Control+Space or the Globe key. The Kana and Eisu keys on JIS keyboards and F13 also switch"))
            }
            Section(T("学習", "Learning")) {
                Toggle(isOn: settings.bool("learning")) {
                    Text(T("選んだ変換を覚える", "Remember chosen conversions"))
                    Text(T("候補から選んで確定したものを、次から先に出します", "Candidates you pick are shown first next time"))
                }
                LabeledContent {
                    Button(T("消す…", "Clear…"), role: .destructive) { confirmClear = true }
                } label: {
                    Text(T("覚えたものを消す", "Clear learned conversions"))
                    Text(T("学習した変換をすべて消します（ユーザー辞書は消えません）", "Deletes everything learned (your user dictionary is kept)"))
                }
            }
            Section(T("表示", "Display")) {
                Picker(selection: settings.string("ui_language")) {
                    Text(T("Mac に合わせる", "Same as the Mac")).tag("auto")
                    Text("日本語").tag("ja")
                    Text("English").tag("en")
                } label: {
                    Text(T("表示の言語（Language）", "Language（表示の言語）"))
                    Text(T("この画面・入力メニュー・カーソルの近くの表示の言語です", "The language of this window, the input menu and the messages near the cursor"))
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle(Page.input.title)
        .confirmationDialog(T("覚えたものを消しますか？", "Clear what Kanade has learned?"), isPresented: $confirmClear) {
            Button(T("消す", "Clear"), role: .destructive) {
                Task.detached {
                    let (_, text) = engineRequest("clear-learning", "")
                    await MainActor.run { message = text }
                }
            }
        } message: {
            Text(T("元に戻せません。ユーザー辞書は残ります", "This cannot be undone. Your user dictionary is kept"))
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK") { message = nil }
        }
    }
}

// ---- 音声入力 ----

struct VoicePage: View {
    @Bindable var settings: SettingsFile
    @Bindable private var downloads = Downloads.shared
    @Bindable private var attention = Attention.shared
    @State private var capture: Bool?

    private var holds: [String] { (settings.values["voice_hold_keys"] ?? "").split(separator: ",").map(String.init).filter { !$0.isEmpty } }

    var body: some View {
        Form {
            Section {
                ForEach(holds, id: \.self) { key in
                    LabeledContent {
                        Button(T("外す", "Remove"), systemImage: "minus.circle") {
                            settings.set("voice_hold_keys", holds.filter { $0 != key }.joined(separator: ","))
                        }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.borderless)
                    } label: {
                        Label {
                            Text(KeyNames.label(key))
                            Text(T("押している間だけ録音し、離すと文字にします", "Records while held and writes the text when released"))
                        } icon: { Image(systemName: "mic") }
                    }
                }
                Button(T("押している間だけ録音するキーを足す…", "Add a hold-to-record key…"), systemImage: "plus") { capture = false }
                LabeledContent {
                    HStack {
                        Button(T("変える…", "Change…")) { capture = true }
                        if !(settings.values["voice_toggle_key"] ?? "").isEmpty {
                            Button(T("使わない", "Turn off")) { settings.set("voice_toggle_key", "") }
                        }
                    }
                } label: {
                    Label {
                        Text(T("押すたびに始める・終える", "Press to start and stop"))
                        Text((settings.values["voice_toggle_key"] ?? "").isEmpty ? T("使わない", "Not used") : KeyNames.label(settings.values["voice_toggle_key"]!))
                    } icon: { Image(systemName: "record.circle") }
                }
            } header: {
                Text(T("録音キー", "Record keys"))
            } footer: {
                Text(T("どの入力ソースでも使えます。Kanade のときは日本語、ほかの入力ソース（ABC など）のときは英語で書きます。録音中に Esc で取り消します。使うにはアクセシビリティの許可が要ります",
                       "Work with any input source. With Kanade you get Japanese, with other input sources (such as ABC) you get English. Press Esc while recording to cancel. Needs Accessibility permission"))
            }
            Section(T("書き方", "Writing")) {
                Toggle(isOn: settings.bool("voice_sounds")) {
                    Text(T("録音の始めと終わりに音を鳴らす", "Sound when recording starts and stops"))
                    Text(T("録音を始めたときに上がる短い音、終えたときに下がる短い音を鳴らします（システム設定の「ユーザインターフェイスのサウンドエフェクトを再生」を切っていると鳴りません）",
                           "Plays a short rising sound when recording starts and a falling one when it stops (not played when “Play user interface sound effects” is off in System Settings)"))
                }
                Toggle(isOn: settings.bool("spoken_punctuation")) {
                    Text(T("話した句読点", "Spoken punctuation"))
                    Text(T("日本語で「まる」「てん」「かいぎょう」などを、句読点や改行にします",
                           "In Japanese, turns spoken words such as maru, ten and kaigyou into punctuation and line breaks"))
                }
            }
            ModelSection(settings: settings, key: "voice_final", title: T("日本語の文を作るモデル", "Model for Japanese"),
                         footer: T("録音を終えたときに、句読点付きの文を作るモデルです。使うには落としておきます（途中で止めても続きから落とせます）。ReazonSpeech は話している途中の文字にも使います",
                                   "Writes the sentence with punctuation when recording ends. Download it to use it (a stopped download resumes). ReazonSpeech is also used for the text shown while you speak"),
                         models: voiceModels.filter(\.japanese))
            ModelSection(settings: settings, key: "voice_final_en", title: T("英語の文を作るモデル", "Model for English"),
                         footer: T("Kanade 以外の入力ソースのときに使います", "Used when another input source is active"),
                         models: voiceModels.filter(\.english))
            Section {
                let perms = attention.perms
                LabeledContent {
                    Button(T("許可の設定を開く", "Open privacy settings")) {
                        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!)
                    }
                } label: {
                    Text(T("アクセシビリティ", "Accessibility"))
                    PermissionState(value: perms["accessibility"].map { $0 == "true" ? "on" : "off" })
                }
                LabeledContent {
                    Button(T("許可の設定を開く", "Open privacy settings")) {
                        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone")!)
                    }
                } label: {
                    Text(T("マイク", "Microphone"))
                    PermissionState(value: perms["microphone"].map { $0 == "authorized" ? "on" : $0 == "not_determined" ? "ask" : "off" })
                }
            } header: {
                Text(T("許可", "Permissions"))
            } footer: {
                Text(T("どちらも「Kanade」を入にします", "Turn on Kanade in both"))
            }
        }
        .formStyle(.grouped)
        .navigationTitle(Page.voice.title)
        .sheet(item: Binding(get: { capture.map { CaptureKind(combo: $0) } }, set: { capture = $0?.combo })) { kind in
            KeyCaptureSheet(combo: kind.combo) { name in
                capture = nil
                guard let name else { return }
                if kind.combo {
                    settings.set("voice_toggle_key", name)
                } else if !holds.contains(name) {
                    settings.set("voice_hold_keys", (holds + [name]).joined(separator: ","))
                }
            }
        }
    }
}

/** 許可の様子（入力方式が書いたもの）。入力方式がまだ動いていなければ何も出さない */
struct PermissionState: View {
    let value: String?

    var body: some View {
        switch value {
        case "on": Label(T("許可されています", "Allowed"), systemImage: "checkmark.circle.fill").foregroundStyle(.green)
        case "off": Label(T("許可されていません", "Not allowed"), systemImage: "xmark.circle.fill").foregroundStyle(.red)
        case "ask": Label(T("初めて録音するときに尋ねられます", "You will be asked the first time you record"), systemImage: "questionmark.circle")
        default: EmptyView()
        }
    }
}

struct CaptureKind: Identifiable {
    let combo: Bool
    var id: Bool { combo }
}

struct ModelSection: View {
    @Bindable var settings: SettingsFile
    let key: String
    let title: String
    let footer: String
    let models: [VoiceModel]
    @Bindable private var downloads = Downloads.shared

    var body: some View {
        Section {
            let _ = downloads.revision
            ForEach(models) { m in
                let ready = m.ready
                let current = settings.values[key] == m.id
                HStack(spacing: Kasane.Space.s3) {
                    Image(systemName: current ? "largecircle.fill.circle" : "circle")
                        .foregroundStyle(current ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                        .opacity(ready ? 1 : 0.4)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(m.name)（\(m.size)）")
                        Text(T(m.ja, m.en)).font(.caption).foregroundStyle(.secondary)
                        if let error = downloads.errors[m.id] {
                            Label(error, systemImage: "exclamationmark.triangle.fill").font(.caption).foregroundStyle(.red)
                        }
                    }
                    Spacer()
                    if let f = downloads.progress[m.id] {
                        ProgressView(value: f).frame(width: 120)
                        Text("\(Int(f * 100))%").monospacedDigit().foregroundStyle(.secondary)
                        Button(T("止める", "Stop"), systemImage: "stop.circle") { downloads.stop(m.id) }
                            .labelStyle(.iconOnly).buttonStyle(.borderless)
                    } else if ready {
                        if current { Text(T("使用中", "In use")).foregroundStyle(.secondary) }
                        Button(T("消す（ゴミ箱へ）", "Delete (to Trash)"), systemImage: "trash") { downloads.delete(m) }
                            .labelStyle(.iconOnly).buttonStyle(.borderless)
                    } else {
                        Button(T("落とす", "Download")) {
                            downloads.start(m) { settings.set(key, m.id) }
                        }
                    }
                }
                .contentShape(.rect)
                .onTapGesture { if ready { settings.set(key, m.id) } }
            }
        } header: {
            Text(title)
        } footer: {
            Text(footer)
        }
    }
}

// ---- ユーザー辞書・スニペット ----

struct PairPage: View {
    let snippets: Bool
    @State private var entries: [PairEntry] = []
    @State private var query = ""
    @State private var textMode = false
    @State private var text = ""
    @State private var editing: EditTarget?
    @State private var saved = false

    private var url: URL { support.appendingPathComponent(snippets ? "snippets.txt" : "user_dictionary.txt") }
    private var keyLabel: String { snippets ? T("打つ文字", "Typed text") : T("読み", "Reading") }
    private var valueLabel: String { snippets ? T("置き換える文", "Replacement") : T("単語", "Word") }
    private var shown: [PairEntry] { query.isEmpty ? entries : entries.filter { $0.key.contains(query) || $0.value.contains(query) } }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(snippets
                 ? T("Kanade で打った文字がキーと一致した瞬間に、文に置き換えます。例: 「->」→「→」、「@@」→ メールアドレス。改行は \\n と書きます",
                     "As soon as what you type with Kanade matches a key, it is replaced with the text. Examples: -> becomes →, @@ becomes your email address. Write \\n for a line break")
                 : T("読みと単語を登録すると、変換の候補の先頭に出ます", "Words you add here are shown first among the conversion candidates for their reading"))
                .font(.callout).foregroundStyle(.secondary)
                .padding([.horizontal, .top])
            if textMode {
                TextEditor(text: $text)
                    .font(.body.monospaced())
                    .padding()
            } else {
                List {
                    if shown.isEmpty {
                        Text(query.isEmpty ? T("まだありません", "Nothing yet") : T("見つかりません", "No matches")).foregroundStyle(.secondary)
                    }
                    ForEach(shown) { e in
                        Button { editing = EditTarget(entry: e) } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(e.value)
                                    Text(e.key).font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                            }
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button(T("消す", "Delete"), role: .destructive) { change(e, nil) }
                        }
                    }
                }
                .searchable(text: $query, prompt: T("探す", "Search"))
            }
        }
        .navigationTitle(snippets ? Page.snippets.title : Page.dictionary.title)
        .toolbar {
            ToolbarItem {
                Toggle(T("テキストで編集", "Edit as text"), systemImage: "doc.plaintext", isOn: $textMode)
            }
            if textMode {
                ToolbarItem {
                    Button(T("保存", "Save")) {
                        PairFile.write(url, text)
                        reload()
                    }
                    .keyboardShortcut("s")
                }
            } else {
                ToolbarItem {
                    Button(T("足す", "Add"), systemImage: "plus") { editing = EditTarget(entry: nil) }
                }
            }
        }
        .onChange(of: textMode) { _, on in if on { text = PairFile.read(url) } else { reload() } }
        .task(id: snippets) { reload() }
        .sheet(item: $editing) { target in
            PairEditor(entry: target.entry, keyLabel: keyLabel, valueLabel: valueLabel) { result in
                editing = nil
                switch result {
                case .save(let k, let v): change(target.entry, "\(k) \(v)")
                case .delete: change(target.entry, nil)
                case .cancel: break
                }
            }
        }
    }

    private func reload() { entries = PairFile.entries(url) }

    private func change(_ entry: PairEntry?, _ line: String?) {
        PairFile.change(url, entry, line)
        reload()
    }
}

struct EditTarget: Identifiable {
    let entry: PairEntry?
    var id: Int { entry?.line ?? -1 }
}

enum PairEditResult {
    case save(String, String), delete, cancel
}

struct PairEditor: View {
    let entry: PairEntry?
    let keyLabel: String
    let valueLabel: String
    let done: (PairEditResult) -> Void
    @State private var key = ""
    @State private var value = ""

    private var valid: Bool {
        let k = key.trimmingCharacters(in: .whitespaces)
        return !k.isEmpty && !k.contains(" ") && !k.contains("\t") && !value.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Kasane.Space.s4) {
            Text(entry == nil ? T("足す", "Add") : T("直す", "Edit")).font(.headline)
            Form {
                TextField(keyLabel, text: $key)
                TextField(valueLabel, text: $value)
            }
            .formStyle(.grouped)
            HStack {
                if entry != nil {
                    Button(T("消す", "Delete"), role: .destructive) { done(.delete) }
                }
                Spacer()
                Button(T("やめる", "Cancel")) { done(.cancel) }
                    .keyboardShortcut(.cancelAction)
                Button(T("保存", "Save")) {
                    done(.save(key.trimmingCharacters(in: .whitespaces), value.trimmingCharacters(in: .whitespaces)))
                }
                .keyboardShortcut(.defaultAction)
                .disabled(!valid)
            }
        }
        .padding(Kasane.Space.s5)
        .frame(width: 420)
        .onAppear {
            key = entry?.key ?? ""
            value = entry?.value ?? ""
        }
    }
}

// ---- 設定の保存と読み込み ----

struct BackupPage: View {
    @State private var message: String?
    @State private var busy = false
    @State private var confirmRemove = false
    @State private var removed = false

    var body: some View {
        Form {
            Section {
                LabeledContent {
                    Button(T("ファイルを選ぶ…", "Choose file…")) { importFile() }
                } label: {
                    Text(T("読み込む", "Import"))
                    Text(T("ほかの Kanade（Android・Windows・Linux・Mac）で書き出したファイル（kanade-settings-….json）を読みます。今のユーザー辞書・スニペットは .bak に残します",
                           "Reads a file exported from another Kanade (Android, Windows, Linux or Mac), kanade-settings-….json. The current dictionary and snippets are kept as .bak"))
                }
                LabeledContent {
                    Button(T("保存先を選ぶ…", "Choose where…")) { exportFile() }
                } label: {
                    Text(T("書き出す", "Export"))
                    Text(T("Android・Windows・Linux・Mac の Kanade で読み込める形で書き出します", "Writes a file that Kanade on Android, Windows, Linux and Mac can import"))
                }
            } header: {
                Text(T("ほかの Kanade との行き来", "Moving between devices"))
            } footer: {
                Text(T("設定・ユーザー辞書・スニペットを1つのファイルにします。Android 版の「設定の書き出し」「設定の読み込み」と同じ形です。録音キーや英語のモデルなど PC の版だけの項目は、Windows・Linux・Mac のあいだで引き継ぎ、Android 版は読み飛ばします",
                       "Settings, the user dictionary and snippets in one file, the same format as Export and Import settings in the Android app. Desktop-only items such as record keys and the English model carry over between Windows, Linux and Mac; the Android app skips them"))
            }
            Section(T("ファイル", "Files")) {
                LabeledContent {
                    Button(T("開く", "Open")) { open(support) }
                } label: {
                    Text(T("設定のフォルダーを開く", "Open the settings folder"))
                    Text(support.path)
                }
                LabeledContent {
                    Button(T("開く", "Open")) { open(logFile) }
                } label: {
                    Text(T("記録を開く", "Open the log"))
                    Text(logFile.path)
                }
            }
            Section(T("取り除く", "Remove")) {
                LabeledContent {
                    Button(T("取り除く…", "Remove…"), role: .destructive) { confirmRemove = true }
                } label: {
                    Text(T("Kanade を取り除く", "Remove Kanade"))
                    Text(T("入力方式・この画面・エンジンをゴミ箱へ移します。Mac のパスワードを求めます",
                           "Moves the input method, this window and the engine to the Trash. Asks for your Mac password"))
                }
            }
        }
        .formStyle(.grouped)
        .disabled(busy)
        .navigationTitle(Page.backup.title)
        .confirmationDialog(T("Kanade を取り除きますか？", "Remove Kanade?"), isPresented: $confirmRemove) {
            Button(T("取り除く（設定と辞書は残す）", "Remove (keep settings and dictionary)"), role: .destructive) { remove(removeData: false) }
            Button(T("設定・辞書・音声のモデルも取り除く", "Also remove settings, dictionary and voice models"), role: .destructive) { remove(removeData: true) }
        } message: {
            Text(T("ゴミ箱へ移すので、空にするまでは戻せます。設定・ユーザー辞書・スニペット・学習・落とした音声のモデルは、残すと入れ直したときにそのまま使えます",
                   "Everything goes to the Trash, so you can restore it until you empty it. If you keep settings, the user dictionary, snippets, learning and downloaded voice models, a reinstall picks them up"))
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK") {
                message = nil
                if removed { NSApp.terminate(nil) }
            }
        }
    }

    private func remove(removeData: Bool) {
        busy = true
        // NSAppleScript（パスワードを求める）は main thread で動かす
        Task { @MainActor in
            let outcome = Uninstaller.run(removeData: removeData)
            do {
                busy = false
                switch outcome {
                case .removed:
                    removed = true
                    message = T("Kanade を取り除きました。入力メニューに残っていれば、ログアウトすると消えます",
                                "Kanade has been removed. If it still shows in the input menu, it goes away when you log out")
                case .cancelled:
                    break
                case .failed(let text):
                    message = T("取り除けませんでした: ", "Could not remove Kanade: ") + text
                }
            }
        }
    }

    private func open(_ url: URL) {
        if FileManager.default.fileExists(atPath: url.path) { NSWorkspace.shared.open(url) } else { message = T("まだありません", "Does not exist yet") }
    }

    private func run(_ work: @escaping @Sendable () -> (Bool, String)) {
        busy = true
        Task.detached {
            let (_, text) = work()
            await MainActor.run {
                message = text
                busy = false
            }
        }
    }

    private func importFile() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.json]
        panel.directoryURL = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first
        if panel.runModal() == .OK, let url = panel.url { run { importSettings(url.path) } }
    }

    private func exportFile() {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [.json]
        let f = DateFormatter()
        f.dateFormat = "yyyyMMdd"
        panel.nameFieldStringValue = "kanade-settings-\(f.string(from: Date())).json"
        if panel.runModal() == .OK, let url = panel.url { run { engineRequest("export-android", url.path) } }
    }
}

// ---- 使い方 ----

struct UsagePage: View {
    var body: some View {
        Form {
            section(T("打つ", "Typing"), [
                T("入力メニューの「Kanade ひらがな」で日本語、「Kanade 英字」で英字。Control+Space・地球儀キー・かな／英数キーでも切り替わります",
                  "Kanade Hiragana in the input menu types Japanese, Kanade English types letters. Control+Space, the Globe key and the Kana/Eisu keys also switch"),
                T("ローマ字で打つと、打つたびに変換した結果が出ます（ライブ変換）。Return で確定",
                  "Type in romaji and the converted text appears as you type (live conversion). Return commits"),
                T("Space で変換し、候補の窓から Space・↑↓・数字・クリックで選びます。←→ で文節を移り、Shift+←→ で区切りを変えます",
                  "Space converts; choose with Space, ↑↓, numbers or a click. ←→ moves between phrases, Shift+←→ changes the boundaries"),
                T("Control+J・K・L・; でひらがな・カタカナ・全角英字・半角英字（macOS と同じ。「入力」で Control+U/I/O/P/T にも替えられます）。F6〜F10 でひらがな・カタカナ・半角カナ・全角英数・半角英数",
                  "Control+J, K, L and ; convert to hiragana, katakana, full-width and half-width letters (as in macOS; you can switch to Control+U/I/O/P/T under Input). F6–F10 convert to hiragana, katakana, half-width kana, full-width and half-width letters"),
            ])
            section(T("音声入力", "Voice input"), [
                T("録音キー（既定は右 Option と F12）を押している間だけ録音し、離すと句読点付きの文が入ります",
                  "Hold a record key (Right Option or F12 by default) to record; release it to type the sentence with punctuation"),
                T("Control+Option+Space は押すたびに録音を始める・終えます。録音中の Esc で取り消します",
                  "Control+Option+Space starts and stops recording. Esc cancels while recording"),
                T("Kanade のときは日本語、ほかの入力ソースのときは英語で書きます。話している途中の文字はカーソルの近くに出ます",
                  "With Kanade you get Japanese, with other input sources English. The text appears near the cursor while you speak"),
            ])
            section(T("設定", "Settings"), [
                T("この画面で変えたものは、すぐ（2 秒ほどで）効きます", "Changes made here take effect within about two seconds"),
                T("入力メニューの「設定…」からもこの画面を開けます", "You can also open this window from Settings… in the input menu"),
            ])
        }
        .formStyle(.grouped)
        .navigationTitle(Page.usage.title)
    }

    private func section(_ title: String, _ items: [String]) -> some View {
        Section(title) {
            ForEach(items, id: \.self) { Text($0) }
        }
    }
}
