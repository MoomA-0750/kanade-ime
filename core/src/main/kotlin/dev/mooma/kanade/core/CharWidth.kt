package dev.mooma.kanade.core

/** ひらがな・カタカナ・全角・半角の変換（F6〜F10 と記号入力で使う） */
object CharWidth {
    fun toKatakana(s: String): String = buildString {
        for (c in s) append(if (c in 'ぁ'..'ゖ') c + 0x60 else c)
    }

    fun toHiragana(s: String): String = buildString {
        for (c in s) append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
    }

    fun toFullWidthAscii(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                ' ' -> '　'
                in '!'..'~' -> c + 0xFEE0
                else -> c
            }
        )
    }

    /** 全角英数・記号（！〜～と全角スペース）を半角に。それ以外はそのまま */
    fun toHalfWidthAscii(s: String): String = buildString {
        for (c in s) append(
            when (c) {
                '\u3000' -> ' '
                in '\uFF01'..'\uFF5E' -> c - 0xFEE0
                else -> c
            }
        )
    }

    fun hasFullWidthAscii(s: String) = s.any { it in '\uFF01'..'\uFF5E' }

    fun hasHalfWidthAscii(s: String) = s.any { it in '!'..'~' }

    fun toHalfWidthKatakana(s: String): String = buildString {
        for (c in toKatakana(s)) {
            val h = HALF_KATAKANA[c]
            if (h != null) append(h) else append(c)
        }
    }

    private val HALF_KATAKANA: Map<Char, String> = run {
        val full = "ァアィイゥウェエォオカガキギクグケゲコゴサザシジスズセゼソゾタダチヂッツヅテデトドナニヌネノハバパヒビピフブプヘベペホボポマミムメモャヤュユョヨラリルレロワヲンヴー。「」、・"
        val half = listOf(
            "ｧ", "ｱ", "ｨ", "ｲ", "ｩ", "ｳ", "ｪ", "ｴ", "ｫ", "ｵ", "ｶ", "ｶﾞ", "ｷ", "ｷﾞ", "ｸ", "ｸﾞ", "ｹ", "ｹﾞ", "ｺ", "ｺﾞ",
            "ｻ", "ｻﾞ", "ｼ", "ｼﾞ", "ｽ", "ｽﾞ", "ｾ", "ｾﾞ", "ｿ", "ｿﾞ", "ﾀ", "ﾀﾞ", "ﾁ", "ﾁﾞ", "ｯ", "ﾂ", "ﾂﾞ", "ﾃ", "ﾃﾞ", "ﾄ", "ﾄﾞ",
            "ﾅ", "ﾆ", "ﾇ", "ﾈ", "ﾉ", "ﾊ", "ﾊﾞ", "ﾊﾟ", "ﾋ", "ﾋﾞ", "ﾋﾟ", "ﾌ", "ﾌﾞ", "ﾌﾟ", "ﾍ", "ﾍﾞ", "ﾍﾟ", "ﾎ", "ﾎﾞ", "ﾎﾟ",
            "ﾏ", "ﾐ", "ﾑ", "ﾒ", "ﾓ", "ｬ", "ﾔ", "ｭ", "ﾕ", "ｮ", "ﾖ", "ﾗ", "ﾘ", "ﾙ", "ﾚ", "ﾛ", "ﾜ", "ｦ", "ﾝ", "ｳﾞ", "ｰ",
            "｡", "｢", "｣", "､", "･",
        )
        require(full.length == half.size)
        full.indices.associate { full[it] to half[it] }
    }
}
