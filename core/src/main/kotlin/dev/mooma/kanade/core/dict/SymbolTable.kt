package dev.mooma.kanade.core.dict

/**
 * 読み→記号・絵文字（「やじるし」→「→←↑↓⇒」、「ぷらす」→「＋」、「えがお」→「😀」など）。
 * Mozc の symbol.tsv と emoji_data.tsv から作る。文節の候補の後ろに足す（Mozc の SymbolRewriter と同じく、1位にはしない）。
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

        /**
         * Mozc の symbol.tsv（品詞 文字 読み… の TSV、読みは空白区切り）と emoji_data.tsv（符号 文字 読み… ）から作る。
         * 読みはひらがなと長音だけのものを使う（「->」のような英数の読みは使わない）
         */
        fun fromMozc(symbolTsv: List<String>, emojiTsv: List<String>): SymbolTable {
            val map = LinkedHashMap<String, MutableList<String>>()
            fun add(char: String, readings: String) {
                if (char.isBlank()) return
                for (r in readings.split(' ')) {
                    if (r.isEmpty() || !r.all { it in 'ぁ'..'ゖ' || it == 'ー' }) continue
                    val list = map.getOrPut(r) { ArrayList() }
                    if (char !in list && list.size < MAX_PER_READING) list += char
                }
            }
            for (line in symbolTsv.drop(1)) {
                val f = line.split('\t')
                if (f.size >= 3) add(f[1], f[2])
            }
            for (line in emojiTsv) {
                if (line.startsWith("#")) continue
                val f = line.split('\t')
                if (f.size >= 3) add(f[1], f[2])
            }
            return SymbolTable(map)
        }
    }
}
