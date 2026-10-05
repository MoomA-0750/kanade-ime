package dev.mooma.kanade.core

/**
 * 括弧の対。日本語入力で開き括弧を打つと閉じ括弧も入れてカーソルを中に置き（[InputEngine]）、
 * 空の対で Space を押すと別の括弧の対に替える。変換の文節が括弧1文字なら、ほかの括弧も候補に出す
 */
object Brackets {
    /** 替えていく順（全角を先に。半角は最後） */
    val PAIRS = listOf("（）", "「」", "『』", "【】", "［］", "｛｝", "〔〕", "〈〉", "《》", "“”", "‘’", "()", "[]", "{}")

    /** 打ったときに閉じ括弧も入れる開き括弧（半角の ( [ { はプログラムなどで邪魔になるので入れない） */
    private val AUTO_CLOSE = PAIRS.take(9).associate { it[0] to it[1] }

    fun closerOf(open: String): String? = open.singleOrNull()?.let { AUTO_CLOSE[it] }?.toString()

    /** [text] が括弧1つの対（「（）」など）なら、その位置 */
    fun pairIndex(text: String): Int = PAIRS.indexOf(text)

    /** 括弧1文字の、ほかの括弧（開きなら開き、閉じなら閉じ）。括弧でなければ空 */
    fun alternatives(text: String): List<String> {
        val c = text.singleOrNull() ?: return emptyList()
        val opens = PAIRS.map { it[0].toString() }
        val closes = PAIRS.map { it[1].toString() }
        return when (c.toString()) {
            in opens -> opens
            in closes -> closes
            else -> emptyList()
        }
    }
}
