// 入力欄ごとの入力方式（InputMethodKit）。キーをエンジンへ送り、返ってきた命令で入力中の文字・確定・候補の窓を書く
import AppKit
import Carbon.HIToolbox
import InputMethodKit

@objc(KanadeInputController)
final class KanadeInputController: IMKInputController, CommandTarget {
    private var composing = false
    private var japanese = true
    private var wantJapanese = false
    private var down = Set<Int>()
    private var rightOptionDown = false
    private static let context = 64

    private var textClient: (any IMKTextInput & NSObjectProtocol)? { client() }

    override func recognizedEvents(_ sender: Any!) -> Int {
        Int(NSEvent.EventTypeMask([.keyDown, .flagsChanged]).rawValue)
    }

    override func activateServer(_ sender: Any!) {
        super.activateServer(sender)
        EngineLink.shared.target = self
        // Kanade に入ったら（入力欄に戻ったときも）いつも日本語にする。英字は macOS の入力ソースの切り替え（ABC など）が受け持つ
        wantJapanese = true
        guard EngineLink.shared.ensureConnected() else { return }
        sendFocus()
    }

    override func deactivateServer(_ sender: Any!) {
        commitAll()
        if EngineLink.shared.connected { EngineLink.shared.send(["focus", "0"]) }
        if EngineLink.shared.target === self { EngineLink.shared.target = nil }
        Panels.hideAll()
        super.deactivateServer(sender)
    }

    /** アプリが入力中の文字を終わらせたい（マウスで入力欄を触ったなど）。入力中の文字は確定する（Windows 版と同じ） */
    override func commitComposition(_ sender: Any!) {
        commitAll()
        Panels.candidates.orderOut(nil)
    }

    private func sendFocus() {
        EngineLink.shared.send(["focus", "1"])
        if wantJapanese {
            EngineLink.shared.send(["mode", "1"])
            wantJapanese = false
        }
    }

    override func handle(_ event: NSEvent!, client sender: Any!) -> Bool {
        guard let event else { return false }
        EngineLink.shared.target = self
        var k: WinKey
        var release = false
        var repeatKey = false
        switch event.type {
        case .keyDown:
            k = KeyMap.winKey(event)
            repeatKey = event.isARepeat
        case .flagsChanged:
            // 修飾キーのうち使うのは、右 Option の単押し（日本語のオン/オフ。エンジンが決める）だけ
            guard Int(event.keyCode) == kVK_RightOption else { return false }
            let pressed = event.modifierFlags.contains(.option)
            if pressed == rightOptionDown { return false }
            rightOptionDown = pressed
            k = WinKey(vk: VK.rmenu, ch: 0, mods: KeyMap.mods(event.modifierFlags))
            release = !pressed
        default:
            return false
        }
        if k.vk == 0 && k.ch == 0 { return false }
        let link = EngineLink.shared
        guard link.ensureConnected() else { return false }
        if wantJapanese { sendFocus() }
        if !composing && !release { sendContext() }
        let id = link.newId()
        guard let consumed = link.request(id, ["key", String(id), release ? "u" : "d", String(k.vk), String(k.ch), String(k.mods), repeatKey ? "1" : "0"]) else {
            // エンジンが遅い（起動の直後など）。キーは食べて、返事が来たら書く
            return link.connected && !release
        }
        return release ? false : consumed
    }

    // ---- エンジンからの命令 ----

    func apply(_ commands: [[String]]) {
        guard let client = textClient else { return }
        for c in commands {
            switch c[0] {
            case "compose" where c.count >= 2:
                setMarked(client, c)
            case "commit" where c.count >= 2:
                client.insertText(c[1], replacementRange: NSRange(location: NSNotFound, length: 0))
                composing = false
            case "mode" where c.count >= 2:
                let on = c[1] == "1"
                if on != japanese {
                    japanese = on
                    Panels.indicator.show(on ? "あ" : "A", ms: 800, caret: caretRect(client))
                }
            case "cands" where c.count >= 3:
                Panels.candidates.onSelect = { i in
                    if EngineLink.shared.connected { EngineLink.shared.send(["select", String(i)]) }
                }
                Panels.candidates.show(c, caret: caretRect(client, at: focusedStart))
            case "hidecands":
                Panels.candidates.orderOut(nil)
            case "indicator" where c.count >= 2:
                Panels.indicator.show(c[1], ms: c.count >= 3 ? Int(c[2]) ?? 0 : 0, caret: caretRect(client))
            case "hideindicator":
                Panels.indicator.hide()
            case "replace" where c.count >= 4:
                replaceBeforeCursor(client, c[1], c[2], c[3])
            default:
                break
            }
        }
    }

    private var focusedStart = 0

    /** compose <文字> <カーソル> <選んでいる文節> <文節の長さ,…>。長さとカーソルは UTF-16 の数（NSString と同じ） */
    private func setMarked(_ client: any IMKTextInput & NSObjectProtocol, _ c: [String]) {
        let text = c[1]
        composing = !text.isEmpty
        let none = NSRange(location: NSNotFound, length: 0)
        if text.isEmpty {
            client.setMarkedText("", selectionRange: NSRange(location: 0, length: 0), replacementRange: none)
            focusedStart = 0
            return
        }
        let length = (text as NSString).length
        let focused = c.count >= 4 ? Int(c[3]) ?? -1 : -1
        let lengths = c.count >= 5 && !c[4].isEmpty ? c[4].split(separator: ",").compactMap { Int($0) } : []
        let s = NSMutableAttributedString(string: text)
        var start = 0
        focusedStart = -1
        if lengths.count <= 1 {
            let style = focused == 0 ? kTSMHiliteSelectedConvertedText : kTSMHiliteRawText
            apply(style: style, to: s, range: NSRange(location: 0, length: length))
            if focused == 0 { focusedStart = 0 }
        } else {
            for (i, n) in lengths.enumerated() {
                let len = i == lengths.count - 1 ? length - start : min(n, length - start)
                if len <= 0 { break }
                let isFocused = i == focused
                if isFocused { focusedStart = start }
                apply(style: isFocused ? kTSMHiliteSelectedConvertedText : kTSMHiliteConvertedText, to: s, range: NSRange(location: start, length: len))
                start += len
            }
        }
        let cursor = c.count >= 3 ? Int(c[2]) ?? -1 : -1
        let at = focusedStart >= 0 ? focusedStart : (cursor < 0 ? length : min(cursor, length))
        if focusedStart < 0 { focusedStart = 0 }
        client.setMarkedText(s, selectionRange: NSRange(location: at, length: 0), replacementRange: none)
    }

    private func apply(style: Int, to s: NSMutableAttributedString, range: NSRange) {
        if let attrs = mark(forStyle: style, at: range) as? [NSAttributedString.Key: Any] {
            s.addAttributes(attrs, range: range)
        } else {
            s.addAttribute(.underlineStyle, value: (style == kTSMHiliteSelectedConvertedText ? NSUnderlineStyle.thick : .single).rawValue, range: range)
        }
    }

    /** 入力中の文字（[at] 文字目）の行の、画面上の四角 */
    private func caretRect(_ client: any IMKTextInput & NSObjectProtocol, at: Int = 0) -> NSRect {
        var rect = NSRect.zero
        client.attributes(forCharacterIndex: at, lineHeightRectangle: &rect)
        if rect == .zero {
            let m = NSEvent.mouseLocation
            rect = NSRect(x: m.x, y: m.y - 20, width: 1, height: 20)
        }
        return rect
    }

    /** 入力を始める前の、カーソルの前後の文（zenz の文脈） */
    private func sendContext() {
        guard let client = textClient else { return }
        let sel = client.selectedRange()
        guard sel.location != NSNotFound else { return }
        let total = client.length()
        let from = max(0, sel.location - Self.context)
        let left = client.attributedSubstring(from: NSRange(location: from, length: sel.location - from))?.string ?? ""
        let rStart = sel.location + sel.length
        let rLen = total > rStart ? min(Self.context, total - rStart) : 0
        let right = rLen > 0 ? client.attributedSubstring(from: NSRange(location: rStart, length: rLen))?.string ?? "" : ""
        if left.isEmpty && right.isEmpty { return }
        EngineLink.shared.send(["ctx", left, right])
    }

    /** スニペット（直接入力中）: カーソルの前が [expected] なら消して [replacement] を入れる。違えば打った文字を入れる */
    private func replaceBeforeCursor(_ client: any IMKTextInput & NSObjectProtocol, _ expected: String, _ typed: String, _ replacement: String) {
        let typedText = Int(typed).flatMap { Unicode.Scalar($0) }.map { String(Character($0)) } ?? ""
        let sel = client.selectedRange()
        let n = (expected as NSString).length
        if sel.location != NSNotFound && sel.length == 0 && sel.location >= n {
            let r = NSRange(location: sel.location - n, length: n)
            if client.attributedSubstring(from: r)?.string == expected {
                client.insertText(replacement, replacementRange: r)
                return
            }
        }
        client.insertText(typedText, replacementRange: NSRange(location: NSNotFound, length: 0))
    }

    private func commitAll() {
        guard composing, EngineLink.shared.connected else { return }
        EngineLink.shared.target = self
        let id = EngineLink.shared.newId()
        _ = EngineLink.shared.request(id, ["commitall", String(id)])
        composing = false
    }

    // ---- 入力メニュー（メニューバーの入力ソースのメニュー） ----

    override func menu() -> NSMenu! {
        let menu = NSMenu()
        let items: [(String, String, String)] = [
            ("settings", "設定…", "Settings…"), ("voice", "音声入力…", "Voice input…"), ("user-dictionary", "ユーザー辞書…", "User dictionary…"),
            ("snippets", "スニペット…", "Snippets…"), ("log", "記録", "Log"),
        ]
        for (id, ja, en) in items {
            let item = NSMenuItem(title: L.t(ja, en), action: #selector(menuAction(_:)), keyEquivalent: "")
            item.representedObject = id
            item.target = self
            menu.addItem(item)
        }
        return menu
    }

    @objc func menuAction(_ sender: Any?) {
        // IMK は項目そのものではなく、kIMKCommandMenuItemName に項目を入れた辞書を渡す
        let item = (sender as? NSMenuItem) ?? ((sender as? [String: Any])?[kIMKCommandMenuItemName] as? NSMenuItem)
        guard let id = item?.representedObject as? String else { return }
        if EngineLink.shared.ensureConnected() { EngineLink.shared.send(["menu", id]) }
    }
}
