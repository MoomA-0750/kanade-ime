package dev.mooma.kanade.desktop

/**
 * TSF の DLL とエンジンのあいだの、1行1メッセージの形（UTF-8、改行で区切る）。
 * 欄はタブで区切り、欄の中の `\`・タブ・改行・CR は `\\`・`\t`・`\n`・`\r` にする。
 * C++ の側でも短く読み書きできるよう、JSON にはしない。中身は docs/WINDOWS-PROTOCOL.md
 */
object Protocol {
    fun encode(fields: List<String>): String = fields.joinToString("\t") { escape(it) }

    fun encode(vararg fields: Any): String = encode(fields.map { it.toString() })

    fun decode(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\t' -> {
                    fields += sb.toString()
                    sb.clear()
                }
                c == '\\' && i + 1 < line.length -> {
                    i++
                    sb.append(
                        when (line[i]) {
                            't' -> '\t'
                            'n' -> '\n'
                            'r' -> '\r'
                            else -> line[i]
                        },
                    )
                }
                else -> sb.append(c)
            }
            i++
        }
        fields += sb.toString()
        return fields
    }

    fun escape(s: String): String {
        if (s.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return s
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\t' -> sb.append("\\t")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}

/** 修飾キーの状態（DLL が GetKeyState で取って送る） */
object Mods {
    const val SHIFT = 1
    const val CTRL = 2
    const val ALT = 4
    const val WIN = 8
}
