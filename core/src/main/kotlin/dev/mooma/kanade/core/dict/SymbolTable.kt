package dev.mooma.kanade.core.dict

/**
 * 読み→記号・絵文字（「やじるし」→「→←↑↓⇒」、「ぷらす」→「＋」、「えがお」→「😀」など）。
 * Mozc の symbol.tsv（記号）と emoji_data.tsv（絵文字）から、別々の表として作る。文節の候補の後ろに足す（Mozc の SymbolRewriter と同じく、1位にはしない）。
 * 形式（UTF-8 の行）: `読み<TAB>記号<TAB>記号…`
 */
class SymbolTable(private val map: Map<String, List<String>>) {
    fun lookup(reading: String): List<String> = map[reading].orEmpty()

    val size: Int get() = map.size

    fun write(): String = buildString {
        for ((reading, symbols) in map) append(reading).append('\t').append(symbols.joinToString("\t")).append('\n')
    }

    companion object {
        /** 1つの読みに付ける記号の上限（「きごう」のように数百あるものを切る） */
        const val MAX_PER_READING = 40

        fun parse(text: String): SymbolTable = SymbolTable(
            text.lineSequence().filter { it.isNotEmpty() }.associate { line ->
                val f = line.split('\t')
                f[0] to f.drop(1)
            },
        )

        /** Mozc の symbol.tsv（品詞 文字 読み… の TSV、読みは空白区切り。1行目は見出し）から作る */
        fun fromMozcSymbols(symbolTsv: List<String>): SymbolTable = build(symbolTsv.drop(1))

        /** Mozc の emoji_data.tsv（符号 文字 読み…）から作る */
        fun fromMozcEmoji(emojiTsv: List<String>): SymbolTable = build(emojiTsv.filter { !it.startsWith("#") })

        /** 2列目が文字、3列目が空白区切りの読み。読みはひらがなと長音だけのものを使う（「->」のような英数の読みは使わない） */
        private fun build(lines: List<String>): SymbolTable {
            val map = LinkedHashMap<String, MutableList<String>>()
            for (line in lines) {
                val f = line.split('\t')
                if (f.size < 3 || f[1].isBlank()) continue
                for (r in f[2].split(' ')) {
                    if (r.isEmpty() || !r.all { it in '\u3041'..'\u3096' || it == 'ー' }) continue
                    val list = map.getOrPut(r) { ArrayList() }
                    if (f[1] !in list && list.size < MAX_PER_READING) list += f[1]
                }
            }
            return SymbolTable(map)
        }
    }
}
