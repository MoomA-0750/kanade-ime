package dev.mooma.kanade.core.voice

/**
 * 話して入れる句読点・記号（Pixel や iPhone の音声入力と同じ考え方）。「くてん」→「。」、「かいぎょう」→ 改行 など。
 * 音声認識の結果は漢字・ひらがな・カタカナのどれで出るか決まらないので、それぞれの書き方を持つ。
 * 認識が自分で付けた句読点と重なったら（「句点。」→「。。」）一つにまとめる。
 */
object SpokenPunctuation {
    /** 長いものを先に見る（「かっことじ」を「かっこ」より先に） */
    private val WORDS: List<Pair<String, String>> = listOf(
        listOf("かぎかっことじ", "カギカッコトジ", "鍵括弧閉じ", "かぎかっこ閉じ", "カギカッコ閉じ", "かぎ括弧閉じ", "閉じかぎかっこ", "とじかぎかっこ") to "」",
        listOf("かぎかっこ", "カギカッコ", "鍵括弧", "かぎ括弧") to "「",
        listOf("かっことじ", "カッコトジ", "括弧閉じ", "かっこ閉じ", "カッコ閉じ", "閉じかっこ", "とじかっこ") to "）",
        listOf("かっこ", "カッコ", "括弧") to "（",
        listOf("くてん", "クテン", "句点") to "。",
        listOf("とうてん", "トウテン", "読点") to "、",
        listOf("かいだんらく", "カイダンラク", "改段落") to "\n\n",
        listOf("かいぎょう", "カイギョウ", "改行") to "\n",
        listOf("ぎもんふ", "ギモンフ", "疑問符", "はてなまーく", "はてなマーク", "ハテナマーク", "クエスチョンマーク") to "？",
        listOf("かんたんふ", "カンタンフ", "感嘆符", "びっくりまーく", "びっくりマーク", "ビックリマーク", "エクスクラメーションマーク") to "！",
        listOf("なかぐろ", "ナカグロ", "中黒") to "・",
    ).flatMap { (forms, out) -> forms.map { it to out } }.sortedByDescending { it.first.length }

    private val AUTO_PUNCT = setOf('、', '。', '，', '．', ',', '.', '?', '？', '!', '！')

    /** 「かっこいい」「かっこよく」の「かっこ」は記号にしない */
    private fun isOtherWord(text: String, at: Int, word: String): Boolean {
        if (word != "かっこ" && word != "カッコ") return false
        val next = text.getOrNull(at + word.length) ?: return false
        return next in "いイよヨ良"
    }

    fun apply(text: String): String {
        if (WORDS.none { text.contains(it.first) }) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val hit = WORDS.firstOrNull { text.startsWith(it.first, i) && !isOtherWord(text, i, it.first) }
            if (hit == null) {
                out.append(text[i])
                i++
                continue
            }
            val symbol = hit.second
            // 認識が言葉の前に付けた句読点や空白（「今日は、句点」）は、話した記号に置き換える
            if (symbol.first() in "。、？！\n") {
                while (out.isNotEmpty() && (out.last() in AUTO_PUNCT || out.last() == ' ' || out.last() == '　')) out.setLength(out.length - 1)
            }
            out.append(symbol)
            i += hit.first.length
            // 言葉の後ろに認識が付けた句読点・空白（「句点。」）は捨てる
            while (i < text.length && (text[i] in AUTO_PUNCT || text[i] == ' ' || text[i] == '　')) i++
        }
        return out.toString()
    }
}
