package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.KeyInput
import dev.mooma.kanade.core.WidthTarget

/**
 * DLL から届いたキー。[vk] は Windows の仮想キー（右 Alt は DLL が VK_RMENU に直して送る）、
 * [char] はそのときのキー配列での文字（ToUnicodeEx の結果。なければ 0）
 */
data class WinKey(val down: Boolean, val vk: Int, val char: Int, val mods: Int, val repeat: Boolean = false) {
    val shift get() = mods and Mods.SHIFT != 0
    val ctrl get() = mods and Mods.CTRL != 0
    val alt get() = mods and Mods.ALT != 0
    val win get() = mods and Mods.WIN != 0
}

/** 日本語のオン/オフに使うキー（Android 版の ToggleTrigger と同じもの） */
enum class WinToggle { RIGHT_ALT_TAP, CTRL_SPACE, F13 }

/**
 * Windows の仮想キーを [KeyInput] へ写す（Android 版の KeyMapper と同じ割り当て）。
 * US 配列でも JIS 配列でも、右 Alt の単押し・Ctrl+Space・変換/無変換・半角/全角でオン/オフする。
 */
class WinKeyMapper(var triggers: Set<WinToggle> = WinToggle.entries.toSet()) {
    /**
     * 右 Alt を押してから、Alt と一緒にほかのキーを押したか。右 Alt を離したとき、これが false なら単押し。
     * 「押した」を見て決めないのは、TSF に直接対応したアプリ（RichEdit など）では Alt を押したことが DLL に届かず、離したことだけが届くため
     */
    private var altCombo = false

    /** null なら IME は関与しない（修飾キー単体など） */
    fun onKeyDown(key: WinKey): KeyInput? {
        val vk = key.vk
        if (vk == VK.RMENU) {
            if (!key.repeat) altCombo = false
            return null
        }
        if (key.alt && vk !in MODIFIERS) altCombo = true

        when (vk) {
            VK.CONVERT, VK.KANA, VK.OEM_COPY, VK.IME_ON -> return KeyInput.SetJapanese(true)
            VK.NONCONVERT, VK.OEM_ATTN, VK.IME_OFF -> return KeyInput.SetJapanese(false)
            VK.KANJI, VK.OEM_AUTO, VK.OEM_ENLW -> return KeyInput.ToggleJapanese
        }
        if (vk == VK.F13 && WinToggle.F13 in triggers) return KeyInput.ToggleJapanese
        if (vk == VK.SPACE && key.ctrl && !key.alt && !key.win && WinToggle.CTRL_SPACE in triggers) return KeyInput.ToggleJapanese

        if (vk in MODIFIERS) return null

        // Ctrl+U/I/O/P/T は F6〜F10 と同じ（MS-IME と同じ割り当て）。未確定がなければエンジンがアプリへ渡す
        if (key.ctrl && !key.alt && !key.win && !key.shift) CTRL_WIDTH[vk]?.let { return KeyInput.ConvertWidth(it) }

        // Ctrl・Alt・Win と一緒に押されたキーはショートカットとしてアプリへ渡す
        if (key.ctrl || key.alt || key.win) return KeyInput.Other

        return when (vk) {
            VK.BACK -> KeyInput.Backspace
            VK.RETURN -> KeyInput.Enter
            VK.SPACE -> if (key.shift) KeyInput.ShiftSpace else KeyInput.Space
            VK.UP -> if (key.shift) KeyInput.Other else KeyInput.Up
            VK.DOWN -> if (key.shift) KeyInput.Other else KeyInput.Down
            VK.LEFT -> if (key.shift) KeyInput.ShiftLeft else KeyInput.Left
            VK.RIGHT -> if (key.shift) KeyInput.ShiftRight else KeyInput.Right
            VK.ESCAPE -> KeyInput.Escape
            VK.HOME -> if (key.shift) KeyInput.Other else KeyInput.Home
            VK.END -> if (key.shift) KeyInput.Other else KeyInput.End
            VK.DELETE -> KeyInput.DeleteForward
            VK.F6 -> KeyInput.ConvertWidth(WidthTarget.HIRAGANA)
            VK.F7 -> KeyInput.ConvertWidth(WidthTarget.KATAKANA)
            VK.F8 -> KeyInput.ConvertWidth(WidthTarget.HALF_KATAKANA)
            VK.F9 -> KeyInput.ConvertWidth(WidthTarget.FULL_ALNUM)
            VK.F10 -> KeyInput.ConvertWidth(WidthTarget.HALF_ALNUM)
            else -> if (key.char >= 0x20 && key.char != 0x7F && key.char < 0x10000) KeyInput.Text(key.char.toChar()) else KeyInput.Other
        }
    }

    /** OnTestKeyUp 用: [onKeyUp] で切り替えになるか（状態は変えない） */
    fun onKeyUpWouldToggle(key: WinKey) = key.vk == VK.RMENU && !altCombo && WinToggle.RIGHT_ALT_TAP in triggers

    /** 右 Alt の単押しが成立したら切り替えを返す */
    fun onKeyUp(key: WinKey): KeyInput? {
        if (key.vk != VK.RMENU) return null
        val toggle = onKeyUpWouldToggle(key)
        altCombo = false
        return if (toggle) KeyInput.ToggleJapanese else null
    }

    private companion object {
        val MODIFIERS = setOf(
            VK.SHIFT, VK.CONTROL, VK.MENU, VK.LSHIFT, VK.RSHIFT, VK.LCONTROL, VK.RCONTROL, VK.LMENU,
            VK.LWIN, VK.RWIN, VK.CAPITAL, VK.NUMLOCK, VK.SCROLL, VK.APPS,
        )
        val CTRL_WIDTH = mapOf(
            'U'.code to WidthTarget.HIRAGANA,
            'I'.code to WidthTarget.KATAKANA,
            'O'.code to WidthTarget.HALF_KATAKANA,
            'P'.code to WidthTarget.FULL_ALNUM,
            'T'.code to WidthTarget.HALF_ALNUM,
        )
    }
}

/** 使う仮想キー（WinUser.h の値） */
object VK {
    const val BACK = 0x08
    const val TAB = 0x09
    const val RETURN = 0x0D
    const val SHIFT = 0x10
    const val CONTROL = 0x11
    const val MENU = 0x12
    const val CAPITAL = 0x14
    const val KANA = 0x15
    const val IME_ON = 0x16
    const val KANJI = 0x19
    const val IME_OFF = 0x1A
    const val ESCAPE = 0x1B
    const val CONVERT = 0x1C
    const val NONCONVERT = 0x1D
    const val SPACE = 0x20
    const val PRIOR = 0x21
    const val NEXT = 0x22
    const val END = 0x23
    const val HOME = 0x24
    const val LEFT = 0x25
    const val UP = 0x26
    const val RIGHT = 0x27
    const val DOWN = 0x28
    const val DELETE = 0x2E
    const val LWIN = 0x5B
    const val RWIN = 0x5C
    const val APPS = 0x5D
    const val F1 = 0x70
    const val F6 = 0x75
    const val F7 = 0x76
    const val F8 = 0x77
    const val F9 = 0x78
    const val F10 = 0x79
    const val F12 = 0x7B
    const val F13 = 0x7C
    const val NUMLOCK = 0x90
    const val SCROLL = 0x91
    const val LSHIFT = 0xA0
    const val RSHIFT = 0xA1
    const val LCONTROL = 0xA2
    const val RCONTROL = 0xA3
    const val LMENU = 0xA4
    const val RMENU = 0xA5
    /** JIS: 英数（CapsLock） */
    const val OEM_ATTN = 0xF0
    /** JIS: カタカナ/ひらがな */
    const val OEM_COPY = 0xF2
    /** JIS: 半角/全角（押すたびに AUTO と ENLW が入れ替わる） */
    const val OEM_AUTO = 0xF3
    const val OEM_ENLW = 0xF4
}
