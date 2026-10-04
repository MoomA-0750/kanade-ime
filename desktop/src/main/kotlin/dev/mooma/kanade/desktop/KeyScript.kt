package dev.mooma.kanade.desktop

/**
 * 試験用のキー列。文字はそのまま打ち、特別なキーは `{Space}`・`{Ctrl+Space}`・`{RAlt}`（単押し）のように書く。
 * US 配列のつもりで仮想キーを付ける（文字は DLL の ToUnicodeEx の結果に当たるものをそのまま入れる）
 */
object KeyScript {
    fun parse(script: String): List<WinKey> {
        val keys = mutableListOf<WinKey>()
        var i = 0
        while (i < script.length) {
            val c = script[i]
            if (c == '{') {
                val end = script.indexOf('}', i + 1)
                require(end > i) { "閉じていない { : $script" }
                keys += named(script.substring(i + 1, end))
                i = end + 1
                continue
            }
            keys += press(vkFor(c), c.code, if (c.isUpperCase()) Mods.SHIFT else 0)
            i++
        }
        return keys
    }

    private fun named(spec: String): List<WinKey> {
        val parts = spec.split('+')
        var mods = 0
        for (m in parts.dropLast(1)) {
            mods = mods or when (m.lowercase()) {
                "shift" -> Mods.SHIFT
                "ctrl" -> Mods.CTRL
                "alt" -> Mods.ALT
                "win" -> Mods.WIN
                else -> error("修飾キーが分からない: $m")
            }
        }
        val name = parts.last()
        if (name.equals("RAlt", ignoreCase = true)) {
            return listOf(WinKey(true, VK.RMENU, 0, mods or Mods.ALT), WinKey(false, VK.RMENU, 0, mods))
        }
        val vk = NAMES[name.lowercase()] ?: if (name.length == 1) vkFor(name[0]) else error("キーの名前が分からない: $name")
        val char = when {
            mods and (Mods.CTRL or Mods.ALT or Mods.WIN) != 0 -> 0
            vk == VK.SPACE -> ' '.code
            name.length == 1 -> name[0].code
            else -> 0
        }
        return press(vk, char, mods)
    }

    private fun press(vk: Int, char: Int, mods: Int) = listOf(WinKey(true, vk, char, mods), WinKey(false, vk, 0, mods))

    private fun vkFor(c: Char): Int = when (c) {
        in 'a'..'z' -> c.uppercaseChar().code
        in 'A'..'Z', in '0'..'9' -> c.code
        ' ' -> VK.SPACE
        '-' -> 0xBD
        ',' -> 0xBC
        '.' -> 0xBE
        else -> 0
    }

    private val NAMES = mapOf(
        "space" to VK.SPACE, "enter" to VK.RETURN, "bs" to VK.BACK, "backspace" to VK.BACK, "esc" to VK.ESCAPE,
        "tab" to VK.TAB, "left" to VK.LEFT, "right" to VK.RIGHT, "up" to VK.UP, "down" to VK.DOWN,
        "home" to VK.HOME, "end" to VK.END, "del" to VK.DELETE,
        "f6" to VK.F6, "f7" to VK.F7, "f8" to VK.F8, "f9" to VK.F9, "f10" to VK.F10, "f12" to VK.F12, "f13" to VK.F13,
        "henkan" to VK.CONVERT, "muhenkan" to VK.NONCONVERT, "zenkaku" to VK.OEM_AUTO, "kana" to VK.OEM_COPY,
    )
}
