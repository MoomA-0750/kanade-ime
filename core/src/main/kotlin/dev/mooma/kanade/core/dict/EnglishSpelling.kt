package dev.mooma.kanade.core.dict

/**
 * カタカナの読み（ひらがなにしたもの）→英語の表記。[table] は JMdict・JMnedict から作った表（english.ksym）。
 * 表に無い読みは、長音の有る無し（こんぴゅーた／こんぴゅーたー）を試し、それでも無ければ表にある語2〜3個に分けてつなぐ
 * （わーるど＋かっぷ→World Cup）
 */
class EnglishSpelling(private val table: SymbolTable) {
    fun lookup(reading: String): List<String> {
        if (reading.isEmpty() || !reading.all { it in 'ぁ'..'ゖ' || it == 'ー' }) return emptyList()
        direct(reading).let { if (it.isNotEmpty()) return it }
        return compound(reading)
    }

    /** 表を引く（末尾の長音の有る無しも試す） */
    private fun direct(reading: String): List<String> {
        table.lookup(reading).let { if (it.isNotEmpty()) return it }
        val variant = if (reading.endsWith("ー")) reading.dropLast(1) else reading + "ー"
        return if (variant.length >= 2) table.lookup(variant) else emptyList()
    }

    /** 表にある語2〜3個に分けてつなぐ。前の語が長い分け方を先に試す */
    private fun compound(reading: String): List<String> {
        if (reading.length < 4) return emptyList()
        val parts = split(reading, 3) ?: return emptyList()
        val words = parts.map { direct(it).first() }
        val joined = words.joinToString(" ")
        // 固有名（大文字で始まる語）が混じれば、全部の頭を大文字にした形を先に出す（World Cup、Apple Watch）
        val title = words.joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }
        return if (words.any { it.first().isUpperCase() }) listOf(title, joined).distinct() else listOf(joined, title).distinct()
    }

    private fun split(reading: String, maxParts: Int): List<String>? {
        if (direct(reading).isNotEmpty()) return listOf(reading)
        if (maxParts <= 1) return null
        for (cut in reading.length - MIN_PART downTo MIN_PART) {
            val head = reading.substring(0, cut)
            if (direct(head).isEmpty()) continue
            val rest = split(reading.substring(cut), maxParts - 1) ?: continue
            return listOf(head) + rest
        }
        return null
    }

    private companion object {
        /** 分けた語の最低の長さ（「あ」「ー」のような1文字の語でつながないように） */
        const val MIN_PART = 2
    }
}
