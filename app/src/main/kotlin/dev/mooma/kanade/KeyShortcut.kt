package dev.mooma.kanade

import android.view.KeyEvent

/**
 * 物理キーボードのショートカット（修飾キー＋キー）。設定に「ctrl+shift+50」のような文字で残す（数はキーコード）。
 * 修飾は Ctrl・Shift・Alt・Meta の4つを左右の区別なく見て、全部が一致したときだけ当たる（Ctrl+Shift+V は Ctrl+V では当たらない）
 */
data class KeyShortcut(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    fun matches(event: KeyEvent): Boolean =
        event.keyCode == keyCode && event.isCtrlPressed == ctrl && event.isShiftPressed == shift &&
            event.isAltPressed == alt && event.isMetaPressed == meta

    /** 画面に出す名前（「Ctrl+Shift+V」） */
    fun label(): String = buildList {
        if (ctrl) add("Ctrl")
        if (shift) add("Shift")
        if (alt) add("Alt")
        if (meta) add("Meta")
        add(keyLabel(keyCode))
    }.joinToString("+")

    fun encode(): String = buildList {
        if (ctrl) add("ctrl")
        if (shift) add("shift")
        if (alt) add("alt")
        if (meta) add("meta")
        add(keyCode.toString())
    }.joinToString("+")

    companion object {
        /** クリップボードを開くキーの既定（Ctrl+Shift+V） */
        val CLIPBOARD_DEFAULT = KeyShortcut(KeyEvent.KEYCODE_V, ctrl = true, shift = true)

        /** 押したキーから作る。修飾キーだけのとき（まだ本体を押していない）は null */
        fun from(event: KeyEvent): KeyShortcut? {
            if (KeyEvent.isModifierKey(event.keyCode)) return null
            return KeyShortcut(event.keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed, event.isMetaPressed)
        }

        fun decode(s: String): KeyShortcut? {
            val parts = s.split('+')
            val code = parts.lastOrNull()?.toIntOrNull() ?: return null
            val mods = parts.dropLast(1).toSet()
            return KeyShortcut(code, "ctrl" in mods, "shift" in mods, "alt" in mods, "meta" in mods)
        }

        fun keyLabel(keyCode: Int): String {
            val name = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
            return if (name.length == 1) name else name.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}
