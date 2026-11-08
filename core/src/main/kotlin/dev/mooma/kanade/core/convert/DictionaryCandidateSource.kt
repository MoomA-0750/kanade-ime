package dev.mooma.kanade.core.convert

import dev.mooma.kanade.core.CandidateSource
import dev.mooma.kanade.core.CharWidth
import dev.mooma.kanade.core.Conversion
import dev.mooma.kanade.core.dict.LearningHistory
import dev.mooma.kanade.core.dict.SymbolTable
import dev.mooma.kanade.core.dict.UserDictionary

/** 辞書とラティスによる [CandidateSource]。文全体は N-best を文節に分けて返す */
class DictionaryCandidateSource(
    private val converter: KanaKanjiConverter,
    private val segmenter: Segmenter,
    private val symbols: SymbolTable = SymbolTable(emptyMap()),
    private val emoji: SymbolTable = SymbolTable(emptyMap()),
    private val dates: DateCandidates = DateCandidates(),
    /** カタカナの読み→英語の表記（アップル→Apple。JMdict・JMnedict から作る） */
    english: SymbolTable = SymbolTable(emptyMap()),
) : CandidateSource {
    /** カタカナ→英語の表記。表は大きいので、IME は起動のあとに裏で読んでから差し替える（[setEnglishTable]） */
    @Volatile private var english = dev.mooma.kanade.core.dict.EnglishSpelling(english)

    fun setEnglishTable(table: SymbolTable) {
        english = dev.mooma.kanade.core.dict.EnglishSpelling(table)
    }

    /** 設定画面で編集されたら差し替える。ラティスにも入れる */
    var userDictionary: UserDictionary
        get() = converter.userDictionary
        set(value) {
            converter.userDictionary = value
        }

    /** 学習（前に選んだ候補）。null なら学習しない */
    @Volatile var learning: LearningHistory? = null
        set(value) {
            field = value
            converter.learnedDictionary = value?.asDictionary() ?: UserDictionary.EMPTY
        }

    /**
     * フリック入力の打ち間違い（濁点・小書きの付け忘れなど）を直した候補も出す。IME がテンキーのときだけ true にする
     * （ローマ字やグライドでは、この表の間違いは起きない）
     */
    @Volatile var typoCorrection = false

    /** 学習が変わった（保存したほうがよい） */
    @Volatile var learningChanged = false

    /** 学習しないよう求める入力欄（パスワード欄・シークレットモードなど）にいるあいだ true */
    @Volatile var learningPaused = false

    override fun learn(reading: String, surface: String) {
        if (learningPaused) return
        val h = learning ?: return
        if (h.learn(reading, surface)) {
            converter.learnedDictionary = h.asDictionary()
            learningChanged = true
        }
    }

    override fun convert(reading: String, n: Int): List<Conversion> {
        val nBest = converter.convert(reading, n)
        val list = withTypos(reading, nBest).map { c ->
            Conversion(segmenter.segments(c.words).let { s -> onReading(reading, s) }, corrected = c !in nBest)
        }
        // カタカナの語（英語の表記がある）が文節に分かれていたら、1つの文節にまとめる（Space で英語の表記も選べるように）
        val katakana = CharWidth.toKatakana(reading)
        val top = list.firstOrNull() ?: return list
        if (top.segments.size > 1 && top.text == katakana && english.lookup(reading).isNotEmpty()) {
            return listOf(Conversion(listOf(dev.mooma.kanade.core.Segment(reading, katakana)))) + list.drop(1)
        }
        return list
    }

    /**
     * 打ち間違いを直した候補を、コスト（直したぶんを足したもの）の順に混ぜる。ただし上位3件には、打ったままの読みの
     * 変換を少なくとも1つ残す（azooKey と同じ保険。直しが外れても、打ったとおりの候補がすぐ見つかるように）
     */
    private fun withTypos(reading: String, nBest: List<Candidate>): List<Candidate> {
        if (!typoCorrection || nBest.isEmpty()) return nBest
        val typos = converter.typoCorrections(reading).filter { t -> nBest.none { it.text == t.text } }
        if (typos.isEmpty()) return nBest
        val merged = (nBest + typos).sortedBy { it.cost }.toMutableList()
        if (merged.take(3).none { it in nBest }) {
            merged.remove(nBest[0])
            merged.add(2, nBest[0])
        }
        return merged
    }

    /**
     * 文節の読みを、打った読み [reading] から切り出し直す（打ち間違いを直した候補は、直した読みの文節になっているため。
     * 直しは1字を1字に替えるだけなので、長さは変わらない）
     */
    private fun onReading(reading: String, segments: List<dev.mooma.kanade.core.Segment>): List<dev.mooma.kanade.core.Segment> {
        var pos = 0
        return segments.map { s ->
            val r = reading.substring(pos, (pos + s.reading.length).coerceAtMost(reading.length))
            pos += s.reading.length
            if (r == s.reading) s else s.copy(reading = r)
        }
    }

    /**
     * ユーザー辞書、ラティスの上位3件、ひらがな・カタカナ、記号・絵文字、ラティスの残り、
     * その読みの辞書の語すべて、の順（記号を下位の当て字より前に出す。Mozc の SymbolRewriter も上位の少し後ろに入れる）。
     * 全角英数・記号を含む候補には半角のもの（逆も）をすぐ後ろに足す（「＋」と「+」、「！」と「!」）
     */
    override fun segmentCandidates(reading: String, n: Int): List<String> {
        val nBest = converter.convert(reading, maxOf(n, LATTICE_CANDIDATES))
        val lattice = nBest.map { it.text }
        // 括弧1文字の文節には、ほかの括弧も出す（フリックの「や」で打った（ から「 『 【 などへ）
        // 打ち間違いを直した候補は、打ったままの読みの上位3件とひらがな・カタカナの後ろに置く
        val typos = if (typoCorrection) converter.typoCorrections(reading).map { it.text } else emptyList()
        val base = userDictionary.lookup(reading) + lattice.take(SYMBOL_POSITION) + reading + CharWidth.toKatakana(reading) + typos +
            dev.mooma.kanade.core.Brackets.alternatives(reading) + english.lookup(reading) +
            dates.lookup(reading) + symbols.lookup(reading) + emoji.lookup(reading) + lattice.drop(SYMBOL_POSITION) + converter.exactWords(reading) +
            headVariants(nBest.firstOrNull())
        // 日付・時刻には全角/半角の別の形を足さない（数字が多く、候補が倍に膨らむため）
        val dateSet = dates.lookup(reading).toSet()
        return base.flatMap { listOf(it) + if (it in dateSet) emptyList() else widthVariant(it) }.distinct()
    }

    /** 候補の窓で右に出す説明 */
    override fun labelFor(reading: String, candidate: String): String? = when {
        candidate in userDictionary.lookup(reading) -> "ユーザー辞書"
        candidate == reading && reading.any { it in '\u3041'..'\u3096' } -> "ひらがな"
        candidate == CharWidth.toKatakana(reading) && candidate != reading -> "カタカナ"
        candidate in dates.lookup(reading) -> if (reading in TIME_READINGS) "時刻" else "日付"
        candidate in emoji.lookup(reading) -> "絵文字"
        candidate in english.lookup(reading) -> "英語"
        candidate in symbols.lookup(reading) || CharWidth.toHalfWidthAscii(candidate) in symbols.lookup(reading) ||
            CharWidth.toFullWidthAscii(candidate) in symbols.lookup(reading) -> when {
            CharWidth.hasHalfWidthAscii(candidate) -> "記号・半角"
            CharWidth.hasFullWidthAscii(candidate) -> "記号・全角"
            else -> "記号"
        }
        CharWidth.hasFullWidthAscii(candidate) -> "全角"
        CharWidth.hasHalfWidthAscii(candidate) -> "半角"
        else -> null
    }

    /**
     * 1位が「語＋後ろ（助詞など）」に分かれるとき、先頭の語の読みの辞書の語すべてに後ろを付けたもの
     * （「かんじが」→ 漢字が・感じが・幹事が…。文節の中の欲しい漢字が N-best に入らないことがあるため）
     */
    private fun headVariants(top: Candidate?): List<String> {
        val words = top?.words ?: return emptyList()
        if (words.size < 2) return emptyList()
        val tail = words.drop(1).joinToString("") { it.surface }
        return converter.exactWords(words[0].reading).map { it + tail }
    }

    /**
     * 入力中の読みの先を予測した候補（「きーぼ」→ キーボード）。読み全体で見つからなければ、1位の変換の最後の文節の読みで探し、
     * 前の文節はそのままの表記を付ける（「きょうはきーぼ」→ 今日はキーボード）
     */
    override fun predict(reading: String, n: Int): List<String> = predictWithReadings(reading, n).map { it.second }

    override fun predictWithReadings(reading: String, n: Int): List<Pair<String, String>> {
        fun words(prefix: String): List<Pair<String, String>> {
            val user = (userDictionary.withPrefix(prefix) + converter.learnedDictionary.withPrefix(prefix))
                .sortedBy { it.first.length }
            return (user + converter.predictWords(prefix, n, segmenter::predictable)).distinctBy { it.second }.take(n)
        }
        words(reading).takeIf { it.isNotEmpty() }?.let { return it }
        val top = convert(reading, 1).firstOrNull() ?: return emptyList()
        if (top.segments.size < 2) return emptyList()
        val last = top.segments.last()
        val headReading = top.segments.dropLast(1).joinToString("") { it.reading }
        val head = top.segments.dropLast(1).joinToString("") { it.surface }
        return words(last.reading).map { (r, w) -> headReading + r to head + w }
    }

    override fun convertWithPrefix(reading: String, prefix: String): Conversion? =
        converter.convertWithPrefix(reading, prefix)?.let { Conversion(segmenter.segments(it.words)) }

    override fun isLearned(reading: String, surface: String): Boolean = learning?.lookup(reading) == surface

    override fun pinned(reading: String): List<String> =
        (userDictionary.lookup(reading) + listOfNotNull(learning?.lookup(reading))).distinct()

    private companion object {
        const val SYMBOL_POSITION = 3

        val TIME_READINGS = setOf("いま", "じこく", "げんざいじこく")

        /** 文節の候補でラティスから取る数（同じ読みの辞書の語は、これとは別に全部足す） */
        const val LATTICE_CANDIDATES = 60
    }

    private fun widthVariant(s: String): List<String> = when {
        CharWidth.hasFullWidthAscii(s) -> listOf(CharWidth.toHalfWidthAscii(s))
        CharWidth.hasHalfWidthAscii(s) -> listOf(CharWidth.toFullWidthAscii(s))
        else -> emptyList()
    }
}
