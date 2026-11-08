package dev.mooma.kanade.core.convert

/**
 * フリック入力で起きやすい打ち間違い（同じキーの上で、濁点・半濁点・小書きを付け忘れる・付けすぎる）。
 * azooKey の TypoCorrection のフリック用の表（タ→ダ、ツ→ッ、ハ→バ など）にならう
 */
object FlickTypos {
    private val groups = listOf(
        "かが", "きぎ", "くぐ", "けげ", "こご", "さざ", "しじ", "すず", "せぜ", "そぞ",
        "ただ", "ちぢ", "つっづ", "てで", "とど", "はばぱ", "ひびぴ", "ふぶぷ", "へべぺ", "ほぼぽ",
        "あぁ", "いぃ", "うぅゔ", "えぇ", "おぉ", "やゃ", "ゆゅ", "よょ", "わゎ",
    )
    private val table: Map<Char, String> = buildMap {
        for (g in groups) for (c in g) put(c, g.filter { it != c })
    }

    /** [c] を打つつもりで間違えやすい字（[c] 自身は含まない）。なければ空 */
    fun alternatives(c: Char): String = table[c].orEmpty()
}
