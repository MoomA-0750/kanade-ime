// 録音キーの名前（fcitx5 のキーの名前。Linux 版と同じで、入力ソースのアプリ mac/ime の KeyMap が読む）と、押したキーの取り方
import AppKit
import Carbon.HIToolbox
import SwiftUI

enum KeyNames {
    /** キーコード → 名前 */
    static let byCode: [Int: String] = {
        var n: [Int: String] = [
            kVK_F1: "F1", kVK_F2: "F2", kVK_F3: "F3", kVK_F4: "F4", kVK_F5: "F5", kVK_F6: "F6", kVK_F7: "F7", kVK_F8: "F8",
            kVK_F9: "F9", kVK_F10: "F10", kVK_F11: "F11", kVK_F12: "F12", kVK_F13: "F13", kVK_F14: "F14", kVK_F15: "F15",
            kVK_F16: "F16", kVK_F17: "F17", kVK_F18: "F18", kVK_F19: "F19", kVK_F20: "F20",
            kVK_RightOption: "Alt_R", kVK_Option: "Alt_L", kVK_RightControl: "Control_R", kVK_Control: "Control_L",
            kVK_RightShift: "Shift_R", kVK_Shift: "Shift_L", kVK_RightCommand: "Super_R", kVK_Command: "Super_L",
            kVK_Space: "space", kVK_Return: "Return", kVK_Tab: "Tab", kVK_JIS_Kana: "Henkan", kVK_JIS_Eisu: "Muhenkan",
        ]
        let letters = [kVK_ANSI_A, kVK_ANSI_B, kVK_ANSI_C, kVK_ANSI_D, kVK_ANSI_E, kVK_ANSI_F, kVK_ANSI_G, kVK_ANSI_H, kVK_ANSI_I,
                       kVK_ANSI_J, kVK_ANSI_K, kVK_ANSI_L, kVK_ANSI_M, kVK_ANSI_N, kVK_ANSI_O, kVK_ANSI_P, kVK_ANSI_Q, kVK_ANSI_R,
                       kVK_ANSI_S, kVK_ANSI_T, kVK_ANSI_U, kVK_ANSI_V, kVK_ANSI_W, kVK_ANSI_X, kVK_ANSI_Y, kVK_ANSI_Z]
        for (i, c) in letters.enumerated() { n[c] = String(UnicodeScalar(UInt8(0x61 + i))) }
        return n
    }()

    static let modifierCodes: Set<Int> = [kVK_RightOption, kVK_Option, kVK_RightControl, kVK_Control, kVK_RightShift, kVK_Shift,
                                          kVK_RightCommand, kVK_Command]

    /** 画面に出す名前（Mac の呼び方） */
    static func label(_ name: String) -> String {
        let labels: [String: (String, String)] = [
            "Alt_R": ("右 Option", "Right Option"), "Alt_L": ("左 Option", "Left Option"), "Control_R": ("右 Control", "Right Control"),
            "Control_L": ("左 Control", "Left Control"), "Super_R": ("右 Command", "Right Command"), "Super_L": ("左 Command", "Left Command"),
            "Shift_R": ("右 Shift", "Right Shift"), "Shift_L": ("左 Shift", "Left Shift"), "Control": ("Control", "Control"),
            "Alt": ("Option", "Option"), "Super": ("Command", "Command"), "space": ("Space", "Space"),
            "Henkan": ("かな", "Kana"), "Muhenkan": ("英数", "Eisu"),
        ]
        return name.split(separator: "+").map { part -> String in
            let p = String(part)
            if let l = labels[p] { return T(l.0, l.1) }
            return p.count == 1 ? p.uppercased() : p
        }.joined(separator: "+")
    }
}

/** 次に押したキーの名前を取る窓。[combo] なら Control などと組み合わせたキー（Control+Alt+space など）、でなければキー1つ */
struct KeyCaptureSheet: View {
    let combo: Bool
    let done: (String?) -> Void
    @State private var monitor: Any?

    var body: some View {
        VStack(spacing: 16) {
            Image(systemName: "keyboard").font(.largeTitle).foregroundStyle(.secondary)
            Text(T("キーを押してください", "Press a key")).font(.headline)
            Text(combo ? T("Control・Option などと組み合わせたキーを押します（Esc でやめる）", "Press a key together with Control, Option or similar (Esc to cancel)")
                 : T("F9・右 Option など、ふだん使わないキーを押します（Esc でやめる）", "Press a key you do not normally use, such as F9 or Right Option (Esc to cancel)"))
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button(T("やめる", "Cancel")) { finish(nil) }
                .keyboardShortcut(.cancelAction)
        }
        .padding(24)
        .frame(width: 360)
        .onAppear {
            monitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown, .flagsChanged]) { e in
                handle(e) ? nil : e
            }
        }
        .onDisappear {
            if let monitor { NSEvent.removeMonitor(monitor) }
        }
    }

    /** 使ったら true */
    private func handle(_ e: NSEvent) -> Bool {
        let code = Int(e.keyCode)
        if e.type == .flagsChanged {
            // 修飾キー1つを録音キーにする（押したときだけ。組み合わせのときは待つ）
            guard !combo, KeyNames.modifierCodes.contains(code), let name = KeyNames.byCode[code] else { return !combo }
            let flags = e.modifierFlags.intersection(.deviceIndependentFlagsMask)
            if flags.isEmpty { return true }
            finish(name)
            return true
        }
        if code == kVK_Escape {
            finish(nil)
            return true
        }
        guard let name = KeyNames.byCode[code] else { return true }
        if combo {
            var mods: [String] = []
            let f = e.modifierFlags
            if f.contains(.control) { mods.append("Control") }
            if f.contains(.option) { mods.append("Alt") }
            if f.contains(.shift) { mods.append("Shift") }
            if f.contains(.command) { mods.append("Super") }
            if mods.isEmpty { return true }
            finish((mods + [name]).joined(separator: "+"))
        } else {
            finish(name)
        }
        return true
    }

    private func finish(_ name: String?) {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
        done(name)
    }
}
