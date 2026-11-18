// macOS のキーを、エンジンが受ける Windows の仮想キー（WinKeyMapper.kt）へ写す。キーの割り当ては全部エンジンが決める
import AppKit
import Carbon.HIToolbox

struct WinKey {
    var vk = 0
    var ch = 0
    var mods = 0
}

enum VK {
    static let back = 0x08, tab = 0x09, enter = 0x0D, escape = 0x1B, space = 0x20
    static let kana = 0x15, oemAttn = 0xF0, rmenu = 0xA5, f12 = 0x7B
}

enum KeyMap {
    /** キーコード（kVK_*）→ 仮想キー。文字のキーは文字から決める */
    static let codes: [Int: Int] = [
        kVK_Delete: VK.back, kVK_Tab: VK.tab, kVK_Return: VK.enter, kVK_ANSI_KeypadEnter: VK.enter,
        kVK_Escape: VK.escape, kVK_Space: VK.space,
        kVK_PageUp: 0x21, kVK_PageDown: 0x22, kVK_End: 0x23, kVK_Home: 0x24,
        kVK_LeftArrow: 0x25, kVK_UpArrow: 0x26, kVK_RightArrow: 0x27, kVK_DownArrow: 0x28, kVK_ForwardDelete: 0x2E,
        kVK_F1: 0x70, kVK_F2: 0x71, kVK_F3: 0x72, kVK_F4: 0x73, kVK_F5: 0x74, kVK_F6: 0x75, kVK_F7: 0x76, kVK_F8: 0x77,
        kVK_F9: 0x78, kVK_F10: 0x79, kVK_F11: 0x7A, kVK_F12: 0x7B, kVK_F13: 0x7C, kVK_F14: 0x7D, kVK_F15: 0x7E,
        kVK_F16: 0x7F, kVK_F17: 0x80, kVK_F18: 0x81, kVK_F19: 0x82, kVK_F20: 0x83,
        // JIS 配列: かな → 日本語、英数 → 直接入力（エンジンの VK_KANA・VK_OEM_ATTN と同じ）
        kVK_JIS_Kana: VK.kana, kVK_JIS_Eisu: VK.oemAttn,
        kVK_RightOption: VK.rmenu,
    ]

    static func mods(_ flags: NSEvent.ModifierFlags) -> Int {
        var m = 0
        if flags.contains(.shift) { m |= 1 }
        if flags.contains(.control) { m |= 2 }
        if flags.contains(.option) { m |= 4 }
        if flags.contains(.command) { m |= 8 }
        return m
    }

    static func winKey(_ event: NSEvent) -> WinKey {
        var k = WinKey()
        let code = Int(event.keyCode)
        k.mods = mods(event.modifierFlags)
        if let vk = codes[code] {
            k.vk = vk
        } else if let base = event.charactersIgnoringModifiers?.unicodeScalars.first {
            // 文字のキー: 配列（Dvorak など）に合わせて、修飾なしの文字から A〜Z・0〜9 を決める
            let v = Int(base.value)
            if (0x61...0x7A).contains(v) { k.vk = v - 0x20 } else if (0x41...0x5A).contains(v) || (0x30...0x39).contains(v) { k.vk = v }
        }
        // 打った文字（Shift を含む）。Option・Control で変わった文字や、矢印などの私用領域の文字は送らない
        if let c = event.characters?.unicodeScalars.first, k.mods & (2 | 4 | 8) == 0 {
            let v = Int(c.value)
            if v >= 0x20 && v != 0x7F && !(0xF700...0xF8FF).contains(v) { k.ch = v }
        } else if k.mods & (2 | 4) != 0, k.mods & 8 == 0, let c = event.charactersIgnoringModifiers?.unicodeScalars.first {
            // Option か Control と一緒（エンジンはショートカットとしてアプリへ渡す。Control+; と Control+: は文字で見て半角英字にする）
            let v = Int(c.value)
            if v >= 0x20 && v != 0x7F && !(0xF700...0xF8FF).contains(v) { k.ch = v }
        }
        return k
    }

    // ---- 録音キーの名前（fcitx5 のキーの名前。設定の voice_hold_keys・voice_toggle_key）→ キーコード ----
    static let names: [String: Int] = {
        var n: [String: Int] = [
            "F1": kVK_F1, "F2": kVK_F2, "F3": kVK_F3, "F4": kVK_F4, "F5": kVK_F5, "F6": kVK_F6, "F7": kVK_F7, "F8": kVK_F8,
            "F9": kVK_F9, "F10": kVK_F10, "F11": kVK_F11, "F12": kVK_F12, "F13": kVK_F13, "F14": kVK_F14, "F15": kVK_F15,
            "F16": kVK_F16, "F17": kVK_F17, "F18": kVK_F18, "F19": kVK_F19, "F20": kVK_F20,
            "Alt_R": kVK_RightOption, "Alt_L": kVK_Option, "Meta_R": kVK_RightOption, "Meta_L": kVK_Option,
            "Control_R": kVK_RightControl, "Control_L": kVK_Control, "Shift_R": kVK_RightShift, "Shift_L": kVK_Shift,
            "Super_R": kVK_RightCommand, "Super_L": kVK_Command, "space": kVK_Space, "Escape": kVK_Escape,
            "Return": kVK_Return, "Tab": kVK_Tab, "Henkan": kVK_JIS_Kana, "Muhenkan": kVK_JIS_Eisu,
        ]
        let letters: [(String, Int)] = [
            ("a", kVK_ANSI_A), ("b", kVK_ANSI_B), ("c", kVK_ANSI_C), ("d", kVK_ANSI_D), ("e", kVK_ANSI_E), ("f", kVK_ANSI_F),
            ("g", kVK_ANSI_G), ("h", kVK_ANSI_H), ("i", kVK_ANSI_I), ("j", kVK_ANSI_J), ("k", kVK_ANSI_K), ("l", kVK_ANSI_L),
            ("m", kVK_ANSI_M), ("n", kVK_ANSI_N), ("o", kVK_ANSI_O), ("p", kVK_ANSI_P), ("q", kVK_ANSI_Q), ("r", kVK_ANSI_R),
            ("s", kVK_ANSI_S), ("t", kVK_ANSI_T), ("u", kVK_ANSI_U), ("v", kVK_ANSI_V), ("w", kVK_ANSI_W), ("x", kVK_ANSI_X),
            ("y", kVK_ANSI_Y), ("z", kVK_ANSI_Z),
        ]
        for (l, c) in letters { n[l] = c }
        return n
    }()

    /** 修飾キーそのもの（押した・離したは flagsChanged で届く）と、そのときに立つ印 */
    static let modifierFlag: [Int: CGEventFlags] = [
        kVK_RightOption: .maskAlternate, kVK_Option: .maskAlternate, kVK_RightControl: .maskControl, kVK_Control: .maskControl,
        kVK_RightShift: .maskShift, kVK_Shift: .maskShift, kVK_RightCommand: .maskCommand, kVK_Command: .maskCommand,
    ]

    /** "Control+Alt+space" → (キーコード, 修飾)。分からなければ nil */
    static func combo(_ name: String) -> (Int, CGEventFlags)? {
        let parts = name.split(separator: "+").map(String.init)
        guard let last = parts.last, let code = names[last] ?? names[last.lowercased()] else { return nil }
        var flags: CGEventFlags = []
        for p in parts.dropLast() {
            switch p {
            case "Control", "Ctrl": flags.insert(.maskControl)
            case "Alt", "Option": flags.insert(.maskAlternate)
            case "Shift": flags.insert(.maskShift)
            case "Super", "Command", "Cmd": flags.insert(.maskCommand)
            default: return nil
            }
        }
        return (code, flags)
    }
}
