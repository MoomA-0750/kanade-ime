// メニューバーの Kanade の項目（常駐アプリのように独自に出す）。今のモードを「あ」「A」で見せ、押すとメニューが開く。
// 入力ソースのメニューバーの印は、モードを1つにしたので「か」で固定のまま（モードごとに替えるには macOS の入力ソースの切り替えが要る）
import AppKit

/** 入力メニュー（入力ソースの印のメニュー）とメニューバーの項目に共通の項目: エンジンへ送る名前と、日本語・英語の表示 */
enum MenuEntries {
    static let all: [(String, String, String)] = [
        ("settings", "設定…", "Settings…"), ("voice", "音声入力…", "Voice input…"), ("user-dictionary", "ユーザー辞書…", "User dictionary…"),
        ("snippets", "スニペット…", "Snippets…"), ("log", "記録", "Log"),
    ]
}

final class StatusMenu: NSObject {
    static let shared = StatusMenu()
    /** 「あ」「A」「か」で幅が変わると、左の項目が動いてしまう。いちばん広い字に合わせて固定する */
    private let item = NSStatusBar.system.statusItem(withLength: StatusMenu.fixedWidth())

    private static func fixedWidth() -> CGFloat {
        let font = NSFont.menuBarFont(ofSize: 0)
        let widest = ["あ", "A", "か"].map { ($0 as NSString).size(withAttributes: [.font: font]).width }.max() ?? 14
        return ceil(widest) + 14
    }
    private let toggleItem = NSMenuItem()

    func start() {
        let menu = NSMenu()
        toggleItem.target = self
        toggleItem.action = #selector(toggleMode)
        menu.addItem(toggleItem)
        menu.addItem(.separator())
        for (id, ja, en) in MenuEntries.all {
            let m = NSMenuItem(title: L.t(ja, en), action: #selector(open(_:)), keyEquivalent: "")
            m.representedObject = id
            m.target = self
            menu.addItem(m)
        }
        item.menu = menu
        item.button?.toolTip = "Kanade"
        item.button?.alignment = .center
        EngineLink.shared.onModeChange = { [weak self] _ in self?.refresh() }
        refresh()
    }

    /** 今のモードと、Kanade が使われているか（使われていないときは薄くする）を映す */
    func refresh() {
        let link = EngineLink.shared
        let ja = link.japanese
        item.button?.title = ja == nil ? "か" : (ja! ? "あ" : "A")
        item.button?.alphaValue = link.target == nil ? 0.5 : 1
        toggleItem.title = ja == false ? L.t("日本語入力にする", "Switch to Japanese") : L.t("英字入力にする", "Switch to English")
    }

    @objc private func toggleMode() {
        let link = EngineLink.shared
        guard link.ensureConnected() else { return }
        link.send(["mode", link.japanese == false ? "1" : "0"])
    }

    @objc private func open(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String, EngineLink.shared.ensureConnected() else { return }
        EngineLink.shared.send(["menu", id])
    }
}
