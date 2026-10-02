package dev.mooma.kanade

import android.view.KeyCharacterMap
import android.view.KeyEvent
import dev.mooma.kanade.core.KeyInput
import dev.mooma.kanade.core.WidthTarget

/**
 * Android の [KeyEvent] を [KeyInput] へ写す。どの物理キーを何に使うかはここだけで決める。
 * 右 Alt の単押しは「押して、他のキーを挟まずに離した」ことを見るので状態を持つ。
 */
class KeyMapper(var triggers: Set<ToggleTrigger>) {
    private var rightAltAlone = false

    /** null なら IME は関与しない（修飾キー単体など） */
    fun onKeyDown(event: KeyEvent): KeyInput? {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_ALT_RIGHT) {
            if (event.repeatCount == 0) rightAltAlone = true
            return null
        }
        rightAltAlone = false

        when (code) {
            KeyEvent.KEYCODE_HENKAN, KeyEvent.KEYCODE_KATAKANA_HIRAGANA -> return KeyInput.SetJapanese(true)
            KeyEvent.KEYCODE_MUHENKAN, KeyEvent.KEYCODE_EISU -> return KeyInput.SetJapanese(false)
            KeyEvent.KEYCODE_ZENKAKU_HANKAKU -> return KeyInput.ToggleJapanese
        }
        if (code == KeyEvent.KEYCODE_F13 && ToggleTrigger.F13 in triggers) return KeyInput.ToggleJapanese
        if (code == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed &&
            ToggleTrigger.CTRL_SPACE in triggers
        ) return KeyInput.ToggleJapanese

        if (KeyEvent.isModifierKey(code) || code == KeyEvent.KEYCODE_CAPS_LOCK) return null

        // Ctrl+U/I/O/P/T は F6〜F10 と同じ（MS-IME と同じ割り当て）。未確定がなければエンジンがアプリへ渡す
        if (event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed && !event.isShiftPressed) {
            CTRL_WIDTH[code]?.let { return KeyInput.ConvertWidth(it) }
        }

        // Ctrl・Alt・Meta と一緒に押されたキーはショートカットとしてアプリへ渡す
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return KeyInput.Other

        return when (code) {
            KeyEvent.KEYCODE_DEL -> KeyInput.Backspace
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> KeyInput.Enter
            KeyEvent.KEYCODE_SPACE -> if (event.isShiftPressed) KeyInput.ShiftSpace else KeyInput.Space
            KeyEvent.KEYCODE_DPAD_UP -> if (event.isShiftPressed) KeyInput.Other else KeyInput.Up
            KeyEvent.KEYCODE_DPAD_DOWN -> if (event.isShiftPressed) KeyInput.Other else KeyInput.Down
            KeyEvent.KEYCODE_ESCAPE -> KeyInput.Escape
            KeyEvent.KEYCODE_F6 -> KeyInput.ConvertWidth(WidthTarget.HIRAGANA)
            KeyEvent.KEYCODE_F7 -> KeyInput.ConvertWidth(WidthTarget.KATAKANA)
            KeyEvent.KEYCODE_F8 -> KeyInput.ConvertWidth(WidthTarget.HALF_KATAKANA)
            KeyEvent.KEYCODE_F9 -> KeyInput.ConvertWidth(WidthTarget.FULL_ALNUM)
            KeyEvent.KEYCODE_F10 -> KeyInput.ConvertWidth(WidthTarget.HALF_ALNUM)
            else -> {
                val unicode = event.unicodeChar
                if (unicode != 0 && (unicode and KeyCharacterMap.COMBINING_ACCENT) == 0 && unicode >= 0x20) {
                    KeyInput.Text(unicode.toChar())
                } else {
                    KeyInput.Other
                }
            }
        }
    }

    /** 右 Alt の単押しが成立したら切り替えを返す */
    fun onKeyUp(event: KeyEvent): KeyInput? {
        if (event.keyCode == KeyEvent.KEYCODE_ALT_RIGHT && rightAltAlone && ToggleTrigger.RIGHT_ALT_TAP in triggers) {
            rightAltAlone = false
            return KeyInput.ToggleJapanese
        }
        return null
    }

    fun consumesRightAlt() = ToggleTrigger.RIGHT_ALT_TAP in triggers

    private companion object {
        val CTRL_WIDTH = mapOf(
            KeyEvent.KEYCODE_U to WidthTarget.HIRAGANA,
            KeyEvent.KEYCODE_I to WidthTarget.KATAKANA,
            KeyEvent.KEYCODE_O to WidthTarget.HALF_KATAKANA,
            KeyEvent.KEYCODE_P to WidthTarget.FULL_ALNUM,
            KeyEvent.KEYCODE_T to WidthTarget.HALF_ALNUM,
        )
    }
}
