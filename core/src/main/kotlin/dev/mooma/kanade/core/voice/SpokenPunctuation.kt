package dev.mooma.kanade.core.voice

import dev.mooma.kanade.core.dict.UserDictionary

/**
 * 話して入れる句読点・記号（Pixel や iPhone の音声入力と同じ考え方）。「くてん」→「。」、「かいぎょう」→ 改行 など。
 * 音声認識の結果は漢字・ひらがな・カタカナのどれで出るか決まらないので、それぞれの書き方を持つ。
 * 認識が自分で付けた句読点と重なったら（「句点。」→「。。」）一つにまとめる。
 *
 * 表は「言い方 出力」の行（[parse]）。言い方の先頭に「|」を付けたものは、文の切れ目で言ったときだけ記号にする
 * （後ろが文の終わり・句読点・空白のとき。「まる」「てん」や、「くてん」の聞き違いの「九点」など、ふつうの言葉にもなるもの）。
 */
class SpokenPunctuation(entries: List<Pair<String, String>>) {
    private class Word(val text: String, val output: String, val boundaryOnly: Boolean)

    /** 長いものを先に見る（「かっことじ」を「かっこ」より先に） */
    private val words: List<Word> = entries.mapNotNull { (key, out) ->
        val boundary = key.startsWith("|")
        val text = key.removePrefix("|")
        if (text.isEmpty()) null else Word(text, out, boundary)
    }.sortedByDescending { it.text.length }

    /** 「かっこいい」「かっこよく」の「かっこ」は記号にしない */
    private fun isOtherWord(text: String, at: Int, word: String): Boolean {
        if (word != "かっこ" && word != "カッコ") return false
        val next = text.getOrNull(at + word.length) ?: return false
        return next in "いイよヨ良"
    }

    /** [at] から始まる [w] が文の切れ目にあるか（後ろが文の終わり・句読点・空白。1文字の漢字なら、前が漢字・数字でない） */
    private fun atBoundary(text: String, at: Int, w: Word): Boolean {
        val next = text.getOrNull(at + w.text.length)
        if (next != null && next !in AUTO_PUNCT && !next.isWhitespace()) return false
        if (w.text.length == 1) {
            val prev = text.getOrNull(at - 1)
            // 「100点」「日本丸」の「点」「丸」は言葉
            if (prev != null && (prev.isDigit() || prev in '０'..'９' || isKanji(prev))) return false
        }
        return true
    }

    private fun matchAt(text: String, i: Int): Word? = words.firstOrNull { w ->
        text.startsWith(w.text, i) && !isOtherWord(text, i, w.text) && (!w.boundaryOnly || atBoundary(text, i, w))
    }

    /**
     * 話した記号を置き換える。[autoPunctuation] が false なら、認識が自分で付けた句読点（、。など）は捨てる
     * （話して入れた記号は残す）
     */
    fun apply(text: String, autoPunctuation: Boolean = true): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val hit = matchAt(text, i)
            if (hit == null) {
                val c = text[i]
                if (autoPunctuation || c !in STRIPPABLE) out.append(c)
                i++
                continue
            }
            // 文の終わりの形（です・ます・た）の直後の「てん」は、「くてん」の「く」が落ちたものとみる
            // （認識は「くてん」をよく「点」と書く。です・ますの直後に読点を言うことはまずない）
            val symbol = if (hit.boundaryOnly && hit.output == "、" && endsSentence(out)) "。" else hit.output
            // 認識が言葉の前に付けた句読点や空白（「今日は、句点」）は、話した記号に置き換える
            if (symbol.first() in "。、？！\n") {
                while (out.isNotEmpty() && (out.last() in AUTO_PUNCT || out.last() == ' ' || out.last() == '　')) out.setLength(out.length - 1)
            }
            out.append(symbol)
            i += hit.text.length
            // 言葉の後ろに認識が付けた句読点・空白（「句点。」）は捨てる
            while (i < text.length && (text[i] in AUTO_PUNCT || text[i] == ' ' || text[i] == '　')) i++
        }
        return out.toString()
    }

    /** [text] の終わり（句読点・空白を除く）が文の終わりの形か */
    private fun endsSentence(text: CharSequence): Boolean {
        var end = text.length
        while (end > 0 && (text[end - 1] in AUTO_PUNCT || text[end - 1].isWhitespace())) end--
        val t = text.subSequence(0, end).toString()
        return SENTENCE_ENDINGS.any { t.endsWith(it) }
    }

    companion object {
        private val SENTENCE_ENDINGS = listOf("です", "ます", "でした", "ました", "ません", "でしょう", "ましょう", "だ", "った", "した", "ね", "よ")

        private val AUTO_PUNCT = setOf('、', '。', '，', '．', ',', '.', '?', '？', '!', '！')

        /** 自動の句読点を切ったときに捨てるもの（疑問符・感嘆符は文の意味が変わるので残す） */
        private val STRIPPABLE = setOf('、', '。', '，', '．', ',', '.')

        private fun isKanji(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿' || c == '々'

        /**
         * 既定の表（設定の「話して入れる記号」の初めの中身）。各社の音声入力の言い方（Windows・Pixel・iPhone）にならう。
         * 「|」付きは文の切れ目だけ。「九点」「苦点」などは「くてん」の、「当店」「東天」などは「とうてん」の聞き違い
         */
        val DEFAULT_TEXT: String = buildString {
            fun add(out: String, vararg keys: String) = keys.forEach { appendLine("$it $out") }
            appendLine("# 言い方 出力（\\n は改行）。言い方の先頭の | は、文の切れ目で言ったときだけ記号にする印")
            add("」", "かぎかっことじ", "カギカッコトジ", "鍵括弧閉じ", "かぎかっこ閉じ", "カギカッコ閉じ", "かぎ括弧閉じ", "閉じかぎかっこ", "とじかぎかっこ", "かぎかっこ閉じる")
            add("「", "かぎかっこ", "カギカッコ", "鍵括弧", "かぎ括弧", "かぎかっこ開く", "かぎかっこひらく")
            add("』", "にじゅうかぎかっことじ", "二重かぎかっこ閉じ", "二重鍵括弧閉じ", "二重かぎ括弧閉じ")
            add("『", "にじゅうかぎかっこ", "二重かぎかっこ", "二重鍵括弧", "二重かぎ括弧")
            add("）", "かっことじ", "カッコトジ", "括弧閉じ", "かっこ閉じ", "カッコ閉じ", "閉じかっこ", "とじかっこ", "かっこ閉じる")
            add("（", "かっこひらく", "かっこ開く", "括弧開く", "カッコ開く", "かっこ", "カッコ", "括弧")
            add("。", "くてん", "クテン", "句点")
            add("。", "|まる", "|マル", "|丸", "|九点", "|苦点", "|区点", "|句てん", "|9点", "|９点")
            add("、", "とうてん", "トウテン", "読点")
            add("、", "|てん", "|テン", "|点", "|当店", "|東天", "|等点", "|読てん")
            add("\\n\\n", "かいだんらく", "カイダンラク", "改段落", "あたらしいだんらく", "新しい段落", "次の段落")
            add("\\n", "かいぎょう", "カイギョウ", "改行", "あたらしいぎょう", "新しい行", "次の行")
            add("？", "ぎもんふ", "ギモンフ", "疑問符", "はてなまーく", "はてなマーク", "ハテナマーク", "クエスチョンマーク")
            add("！", "かんたんふ", "カンタンフ", "感嘆符", "びっくりまーく", "びっくりマーク", "ビックリマーク", "エクスクラメーションマーク")
            add("・", "なかぐろ", "ナカグロ", "中黒")
            add("…", "さんてんりーだー", "三点リーダー", "三点リーダ")
            add("／", "スラッシュ")
            add("＠", "アットマーク")
            add("ー", "ちょうおん", "長音")
            add("〜", "なみせん", "波線")
        }

        val DEFAULT = SpokenPunctuation(parse(DEFAULT_TEXT))

        /** 「言い方 出力」の行を読む（ユーザー辞書と同じ形。# の行は飛ばす） */
        fun parse(text: String): List<Pair<String, String>> = UserDictionary.parsePairs(text)

        fun apply(text: String): String = DEFAULT.apply(text)
    }
}
