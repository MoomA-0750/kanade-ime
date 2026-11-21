// 入力方式に関わらない音声入力。システム全体のキーを見て（CGEventTap。アクセシビリティの許可が要る）、
// 録音キーを押した・離したをエンジンへ送る。押し方の判定はエンジン（長押しは押している間だけ録音、短く押すと聞き続けるモード）。Kanade のときは日本語、ほかの入力方式のときは英語のモデルで文字にする（エンジンが選ぶ）。
// Kanade が選ばれていないときの確定は、ここ（[apply]）で前のアプリへ書く
import AppKit
import ApplicationServices
import AVFoundation
import Carbon.HIToolbox

final class VoiceInput: CommandTarget {
    private var holdKeys: [Int] = [kVK_F12, kVK_RightOption]
    private var toggleKey: (Int, CGEventFlags)? = (kVK_Space, [.maskControl, .maskAlternate])
    /** エンジンが知らせる音声入力の様子（off・hold・listen） */
    private var state = "off"
    private var down = Set<Int>()
    private var tap: CFMachPort?
    private var retry: Timer?
    /** 聞き続けるモードのあいだ、入力欄が替わったかを見る（Kanade が選ばれていないときは知らせが無いため） */
    private var focusTimer: Timer?
    private var focusedElement: AXUIElement?
    private var focusedApp: pid_t = 0

    /** エンジンからの音声入力の様子 */
    func setState(_ s: String) {
        state = s
        if s == "listen" { watchFocus() } else { stopWatchingFocus() }
    }

    private func watchFocus() {
        guard focusTimer == nil else { return }
        (focusedElement, focusedApp) = Self.focus()
        focusTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self, self.state == "listen" else { return }
            let (element, app) = Self.focus()
            var moved = app != self.focusedApp
            if let a = element, let b = self.focusedElement, !CFEqual(a, b) { moved = true }
            if moved {
                klog("voice: focus moved; stop listening")
                EngineLink.shared.send(["voice", "blur"])
                self.stopWatchingFocus()
            }
        }
    }

    private func stopWatchingFocus() {
        focusTimer?.invalidate()
        focusTimer = nil
        focusedElement = nil
    }

    /** いまフォーカスのある要素（アクセシビリティで取れなければ nil）と前面のアプリ */
    private static func focus() -> (AXUIElement?, pid_t) {
        let app = NSWorkspace.shared.frontmostApplication?.processIdentifier ?? 0
        var focused: CFTypeRef?
        let system = AXUIElementCreateSystemWide()
        guard AXUIElementCopyAttributeValue(system, kAXFocusedUIElementAttribute as CFString, &focused) == .success, let el = focused else {
            return (nil, app)
        }
        return ((el as! AXUIElement), app)
    }

    /** 日本語⇔英字の Control+Space・🌐 を Kanade が取るか（設定の mac_switch_keys が KANADE）。MACOS なら macOS の入力ソースの切り替えに任せる */
    private var takeSwitchKeys = true

    func setKeys(hold: String, toggle: String, switchKeys: String) {
        takeSwitchKeys = switchKeys != "MACOS"
        holdKeys = hold.split(separator: ",").compactMap { KeyMap.names[String($0)] }
        toggleKey = toggle.isEmpty ? nil : KeyMap.combo(toggle)
        klog("voice keys: hold=\(hold) toggle=\(toggle)")
    }

    /** キーを見始める。許可がまだなら、システム設定の許可を求め、許可されるまで数秒ごとに試す */
    func start() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        if !AXIsProcessTrustedWithOptions(options) { klog("accessibility is not allowed yet") }
        Permissions.watch()
        if createTap() { return }
        retry = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] t in
            if self?.createTap() == true { t.invalidate() }
        }
    }

    private func createTap() -> Bool {
        if tap != nil { return true }
        let mask = (1 << CGEventType.keyDown.rawValue) | (1 << CGEventType.keyUp.rawValue) | (1 << CGEventType.flagsChanged.rawValue)
        let me = Unmanaged.passUnretained(self).toOpaque()
        guard let port = CGEvent.tapCreate(tap: .cgSessionEventTap, place: .headInsertEventTap, options: .defaultTap,
                                           eventsOfInterest: CGEventMask(mask), callback: { _, type, event, info in
            let voice = Unmanaged<VoiceInput>.fromOpaque(info!).takeUnretainedValue()
            return voice.onEvent(type, event)
        }, userInfo: me) else { return false }
        tap = port
        let src = CFMachPortCreateRunLoopSource(nil, port, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), src, .commonModes)
        CGEvent.tapEnable(tap: port, enable: true)
        klog("voice key tap ready")
        return true
    }

    private func onEvent(_ type: CGEventType, _ event: CGEvent) -> Unmanaged<CGEvent>? {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let tap { CGEvent.tapEnable(tap: tap, enable: true) }
            return Unmanaged.passUnretained(event)
        }
        // 自分で書いた文字（下の type）は見ない
        if event.getIntegerValueField(.eventSourceUserData) == Self.marker { return Unmanaged.passUnretained(event) }
        // リモートデスクトップなどが手前のときは、録音キーも含めて何も取らず、向こう側のホストへ通す
        // （録音中・取ったキーを押している最中は、押したキーの離しまで扱う）
        if state == "off" && down.isEmpty && switchDown.isEmpty && RemoteApps.isFrontmost { return Unmanaged.passUnretained(event) }
        let code = Int(event.getIntegerValueField(.keyboardEventKeycode))
        if switchKey(type, code, event.flags) { return nil }
        var release: Bool
        switch type {
        case .keyDown: release = false
        case .keyUp: release = true
        case .flagsChanged:
            guard let flag = KeyMap.modifierFlag[code] else { return Unmanaged.passUnretained(event) }
            release = !event.flags.contains(flag)
        default: return Unmanaged.passUnretained(event)
        }
        return handle(code: code, flags: event.flags, release: release, repeatKey: event.getIntegerValueField(.keyboardEventAutorepeat) != 0)
            ? nil : Unmanaged.passUnretained(event)
    }

    // ---- 日本語と英字の切り替え（Kanade のとき。macOS の入力ソースの切り替えは遅れたり効かなかったりするので、先に取ってエンジンへ渡す） ----

    private var switchDown = Set<Int>()
    private var fnClean = false

    /** Control+Space と 🌐 キー（ほかのキーと一緒でなく単独で押して離したとき）を、エンジンの Ctrl+Space（日本語⇔英字）として渡す。使ったら true */
    private func switchKey(_ type: CGEventType, _ code: Int, _ flags: CGEventFlags) -> Bool {
        let link = EngineLink.shared
        guard takeSwitchKeys, link.target != nil, link.connected, !RemoteApps.isFrontmost else { return false }
        func toggle() {
            let k = (vk: VK.space, ch: 0x20, mods: 2)
            for phase in ["d", "u"] {
                let id = link.newId()
                _ = link.request(id, ["key", String(id), phase, String(k.vk), String(k.ch), String(k.mods), "0"])
            }
        }
        switch type {
        case .keyDown where code == kVK_Space && flags.intersection([.maskControl, .maskAlternate, .maskShift, .maskCommand]) == .maskControl:
            if switchDown.insert(code).inserted { toggle() }
            return true
        case .keyUp where switchDown.remove(code) != nil:
            return true
        case .keyDown:
            fnClean = false // 🌐 を押しているあいだにほかのキーを打った（Fn+矢印など）
            return false
        case .flagsChanged where code == kVK_Function:
            if flags.contains(.maskSecondaryFn) {
                fnClean = true
            } else if fnClean {
                fnClean = false
                toggle()
            }
            return true
        default:
            return false
        }
    }

    /** 使ったら true（アプリへ渡さない） */
    private func handle(code: Int, flags: CGEventFlags, release: Bool, repeatKey: Bool) -> Bool {
        let link = EngineLink.shared
        if release {
            // 押したときに使ったキーは、離したときも使う（アプリへ片方だけ届かないように）
            guard down.remove(code) != nil else { return false }
            if holdKeys.contains(code) { link.send(["voice", "up"]) }
            return true
        }
        let wasDown = down.contains(code)
        // 録音中・聞き続けるモード中の Esc（エンジンが知らせる様子で決める）
        if state != "off" && code == kVK_Escape {
            down.insert(code)
            // 終えたかはエンジンが voicestate で知らせる（打っている途中なら、入力中の文字の Esc になって聞き続ける）
            link.send(["voice", "cancel"])
            return true
        }
        let hold = holdKeys.contains(code)
        let mods = flags.intersection([.maskControl, .maskAlternate, .maskShift, .maskCommand])
        let toggle = toggleKey.map { $0.0 == code && mods == $0.1 } ?? false
        if !hold && !toggle { return false }
        // 修飾キーを録音キーにしたとき、ほかの修飾と一緒に押したもの（Option+Shift など）は普通の操作として通す
        if hold && KeyMap.modifierFlag[code] != nil && mods.subtracting(KeyMap.modifierFlag[code]!) != [] { return false }
        down.insert(code)
        if wasDown || repeatKey { return true }
        if state == "off" {
            guard link.ensureConnected() else {
                showIndicator(L.t("Kanade を起動しています…", "Starting Kanade…"), ms: 2000)
                return true
            }
            // エンジンの知らせ（voicestate）が届くまでの Esc にも備える
            state = hold ? "hold" : "listen"
        }
        // 押し方（短く押したら聞き続けるモード）・始めの音・終わりはエンジンが決める
        link.send(["voice", hold ? "down" : "toggle", link.target != nil ? "ja" : "en"])
        return true
    }

    // ---- Kanade が選ばれていないときの書き先 ----

    func apply(_ commands: [[String]]) {
        for c in commands {
            switch c[0] {
            case "commit" where c.count >= 2: type(c[1])
            case "indicator" where c.count >= 2: showIndicator(c[1], ms: c.count >= 3 ? Int(c[2]) ?? 0 : 0)
            case "hideindicator": Panels.indicator.hide()
            case "voiceaction" where c.count >= 2: voiceAction(c[1], c.count >= 3 ? c[2] : "")
            default: break
            }
        }
    }

    private static let marker: Int64 = 0x4B414E41 // "KANA"

    /** キーを1回押して離す（声のコマンド。自分のタップでは見ないよう印を付ける） */
    static func postKey(_ code: Int, flags: CGEventFlags = [], times: Int = 1) {
        for _ in 0..<times {
            for keyDown in [true, false] {
                guard let e = CGEvent(keyboardEventSource: nil, virtualKey: CGKeyCode(code), keyDown: keyDown) else { continue }
                e.flags = flags
                e.setIntegerValueField(.eventSourceUserData, value: marker)
                e.post(tap: .cgSessionEventTap)
            }
        }
    }

    /**
     * 声のコマンド（Kanade が選ばれていないとき）: send は Return、next は Tab、clear は Command+A と Delete、
     * undo はアクセシビリティで欄の文とカーソルを読み、カーソルの前が [text] なら Delete を文字数ぶん（読めなければ何もしない）
     */
    private func voiceAction(_ kind: String, _ text: String) {
        switch kind {
        case "send": Self.postKey(kVK_Return)
        case "next": Self.postKey(kVK_Tab)
        case "clear":
            Self.postKey(kVK_ANSI_A, flags: .maskCommand)
            Self.postKey(kVK_Delete)
        case "undo":
            guard !text.isEmpty, Self.textBeforeCaret(length: (text as NSString).length) == text else {
                klog("voice: undo skipped (text before the caret does not match)")
                return
            }
            Self.postKey(kVK_Delete, times: text.count)
        default: break
        }
    }

    /** いまの入力欄の、カーソルの前の [length] 文字（UTF-16）。読めなければ nil */
    private static func textBeforeCaret(length: Int) -> String? {
        guard let el = focus().0 else { return nil }
        var value: CFTypeRef?
        var range: CFTypeRef?
        guard AXUIElementCopyAttributeValue(el, kAXValueAttribute as CFString, &value) == .success, let text = value as? String,
              AXUIElementCopyAttributeValue(el, kAXSelectedTextRangeAttribute as CFString, &range) == .success, let r = range else { return nil }
        var sel = CFRange()
        guard AXValueGetValue(r as! AXValue, .cfRange, &sel), sel.length == 0, sel.location >= length else { return nil }
        let ns = text as NSString
        guard sel.location <= ns.length else { return nil }
        return ns.substring(with: NSRange(location: sel.location - length, length: length))
    }

    /** 前のアプリへ文字を書く。いまの入力ソースがキー配列（ABC など）なら文字のキーとして、ほかの入力方式ならペーストで */
    private func type(_ text: String) {
        if text.isEmpty { return }
        let current = TISCopyCurrentKeyboardInputSource().takeRetainedValue()
        let kind = Unmanaged<CFString>.fromOpaque(TISGetInputSourceProperty(current, kTISPropertyInputSourceType)).takeUnretainedValue() as String
        if kind == (kTISTypeKeyboardLayout as String) {
            let units = Array(text.utf16)
            var i = 0
            while i < units.count {
                let chunk = Array(units[i..<min(i + 20, units.count)])
                for keyDown in [true, false] {
                    guard let e = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: keyDown) else { continue }
                    e.flags = []
                    e.setIntegerValueField(.eventSourceUserData, value: Self.marker)
                    chunk.withUnsafeBufferPointer { e.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: $0.baseAddress) }
                    e.post(tap: .cgSessionEventTap)
                }
                i += 20
            }
            return
        }
        // ほかの入力方式は打った文字を変換してしまうので、クリップボードを借りて Command+V で入れ、少しあとに戻す
        let pb = NSPasteboard.general
        let saved = pb.pasteboardItems?.map { item -> NSPasteboardItem in
            let copy = NSPasteboardItem()
            for t in item.types { if let d = item.data(forType: t) { copy.setData(d, forType: t) } }
            return copy
        } ?? []
        pb.clearContents()
        pb.setString(text, forType: .string)
        for keyDown in [true, false] {
            guard let e = CGEvent(keyboardEventSource: nil, virtualKey: CGKeyCode(kVK_ANSI_V), keyDown: keyDown) else { continue }
            e.flags = .maskCommand
            e.setIntegerValueField(.eventSourceUserData, value: Self.marker)
            e.post(tap: .cgSessionEventTap)
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { // apple-ui-allow: CGEventTap の中から呼ぶ、画面と関係ない後片付け
            pb.clearContents()
            if !saved.isEmpty { pb.writeObjects(saved) }
        }
    }

    /** カーソルの近く（アクセシビリティで取れなければマウスの近く）に出す */
    private func showIndicator(_ text: String, ms: Int) {
        Panels.indicator.show(text, ms: ms, caret: Self.focusedCaret() ?? {
            let m = NSEvent.mouseLocation
            return NSRect(x: m.x, y: m.y - 24, width: 1, height: 20)
        }())
    }

    /** いまの入力欄のカーソルの四角（画面の座標、左下が原点） */
    static func focusedCaret() -> NSRect? {
        let system = AXUIElementCreateSystemWide()
        var focused: CFTypeRef?
        guard AXUIElementCopyAttributeValue(system, kAXFocusedUIElementAttribute as CFString, &focused) == .success, let el = focused else { return nil }
        let element = el as! AXUIElement
        var range: CFTypeRef?
        guard AXUIElementCopyAttributeValue(element, kAXSelectedTextRangeAttribute as CFString, &range) == .success, let r = range else { return nil }
        var bounds: CFTypeRef?
        guard AXUIElementCopyParameterizedAttributeValue(element, kAXBoundsForRangeParameterizedAttribute as CFString, r, &bounds) == .success,
              let b = bounds else { return nil }
        var rect = CGRect.zero
        guard AXValueGetValue(b as! AXValue, .cgRect, &rect), rect.height > 0 else { return nil }
        // アクセシビリティは左上が原点。主の画面の高さで反す
        let h = NSScreen.screens.first?.frame.height ?? 0
        return NSRect(x: rect.minX, y: h - rect.maxY, width: max(rect.width, 1), height: rect.height)
    }
}

/**
 * 許可の様子（アクセシビリティ・マイク）を ~/Library/Application Support/Kanade/permissions.properties に書く。
 * 許可は Kanade.app に付くので、設定の画面（別のアプリ）からは調べられない。設定の画面はこれを読んで、足りない許可を知らせる。
 * 3 秒ごとに調べ、変わったときだけ書く
 */
enum Permissions {
    private static var last = ""
    private static var timer: Timer?

    static func watch() {
        write()
        timer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { _ in write() }
    }

    static func write() {
        let mic: String
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized: mic = "authorized"
        case .denied: mic = "denied"
        case .restricted: mic = "restricted"
        default: mic = "not_determined"
        }
        let text = "# Kanade の入力方式が書く許可の様子（設定の画面が読む）\naccessibility=\(AXIsProcessTrusted())\nmicrophone=\(mic)\n"
        guard text != last else { return }
        last = text
        let dir = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Application Support/Kanade")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try? text.write(to: dir.appendingPathComponent("permissions.properties"), atomically: true, encoding: .utf8)
        klog("permissions: accessibility=\(AXIsProcessTrusted()) microphone=\(mic)")
    }
}
