package dev.mooma.kanade.core.flick

/**
 * フリック配列（12キー、Gboard と同じ考え方）の文字の表。キーごとに
 * 「押す・左・上・右・下」の5方向の文字を持つ（ない方向は空）。同じキーを続けて押すと、この順に入れ替わる（トグル入力）。
 */
object FlickTables {
    /** 方向（押す・左・上・右・下） */
    enum class Dir { TAP, LEFT, UP, RIGHT, DOWN }

    class Key(val label: String, val chars: List<String>) {
        fun char(dir: Dir): String? = chars.getOrNull(dir.ordinal)?.takeIf { it.isNotEmpty() }
        /** トグル入力で回す文字（空でないもの） */
        val cycle: List<String> get() = chars.filter { it.isNotEmpty() }
    }

    private fun k(label: String, vararg c: String) = Key(label, c.toList())

    /** かな（3列×4段の真ん中。4段目の左は「小゛゜」で、[DAKUTEN] として別に扱う） */
    val KANA: List<Key> = listOf(
        k("あ", "あ", "い", "う", "え", "お"), k("か", "か", "き", "く", "け", "こ"), k("さ", "さ", "し", "す", "せ", "そ"),
        k("た", "た", "ち", "つ", "て", "と"), k("な", "な", "に", "ぬ", "ね", "の"), k("は", "は", "ひ", "ふ", "へ", "ほ"),
        k("ま", "ま", "み", "む", "め", "も"), k("や", "や", "（", "ゆ", "）", "よ"), k("ら", "ら", "り", "る", "れ", "ろ"),
        k(DAKUTEN, ""), k("わ", "わ", "を", "ん", "ー", ""), k("、。?!", "、", "。", "？", "！", "…"),
    )

    /** 英字（4段目の左は大文字・小文字の切り替え） */
    val ALPHABET: List<Key> = listOf(
        k("@#/&_", "@", "#", "/", "&", "_"), k("ABC", "a", "b", "c", "", ""), k("DEF", "d", "e", "f", "", ""),
        k("GHI", "g", "h", "i", "", ""), k("JKL", "j", "k", "l", "", ""), k("MNO", "m", "n", "o", "", ""),
        k("PQRS", "p", "q", "r", "s", ""), k("TUV", "t", "u", "v", "", ""), k("WXYZ", "w", "x", "y", "z", ""),
        k(CASE, ""), k("'\"()", "'", "\"", "(", ")", ""), k(".,?!", ".", ",", "?", "!", "-"),
    )

    /** 数字と記号 */
    val NUMBER: List<Key> = listOf(
        k("1", "1", "☆", "♪", "→", ""), k("2", "2", "¥", "$", "€", ""), k("3", "3", "%", "°", "#", ""),
        k("4", "4", "○", "*", "・", ""), k("5", "5", "+", "×", "÷", ""), k("6", "6", "<", "=", ">", ""),
        k("7", "7", "「", "」", ":", ""), k("8", "8", "〒", "々", "〆", ""), k("9", "9", "^", "|", "\\", ""),
        k("()[]", "(", ")", "[", "]", ""), k("0", "0", "~", "…", "'", ""), k(".,-/", ".", ",", "-", "/", ""),
    )

    /** 「小゛゜」のキーの名前（押すと直前の文字に濁点・半濁点・小書きを付け外しする） */
    const val DAKUTEN = "小゛゜"
    /** 英字の大文字・小文字の切り替えのキーの名前 */
    const val CASE = "a/A"

    /** 濁点・半濁点・小書きの順に回す組（Gboard と同じ: か→が→か、は→ば→ぱ→は、つ→っ→づ→つ、あ→ぁ→あ） */
    private val CYCLES: List<String> = listOf(
        "かが", "きぎ", "くぐ", "けげ", "こご", "さざ", "しじ", "すず", "せぜ", "そぞ", "ただ", "ちぢ", "つっづ", "てで", "とど",
        "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ", "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ", "やゃ", "ゆゅ", "よょ", "わゎ",
        "カガ", "キギ", "クグ", "ケゲ", "コゴ", "サザ", "シジ", "スズ", "セゼ", "ソゾ", "タダ", "チヂ", "ツッヅ", "テデ", "トド",
        "ハバパ", "ヒビピ", "フブプ", "ヘベペ", "ホボポ", "アァ", "イィ", "ウゥヴ", "エェ", "オォ", "ヤャ", "ユュ", "ヨョ", "ワヮ",
    )

    /** 「小゛゜」を押したときの、直前の文字の次の形（変えられなければ null） */
    fun nextDakuten(c: Char): Char? {
        val cycle = CYCLES.firstOrNull { c in it } ?: return null
        return cycle[(cycle.indexOf(c) + 1) % cycle.length]
    }

    /** トグル入力で、[current] の次（[forward] が false なら前）の文字 */
    fun toggle(key: Key, current: String, forward: Boolean = true): String? {
        val cycle = key.cycle
        val i = cycle.indexOf(current)
        if (i < 0) return null
        return cycle[(i + (if (forward) 1 else cycle.size - 1)) % cycle.size]
    }
}
