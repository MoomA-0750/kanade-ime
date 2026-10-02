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
) : CandidateSource {
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

    /** 学習が変わった（保存したほうがよい） */
    @Volatile var learningChanged = false

    override fun learn(reading: String, surface: String) {
        val h = learning ?: return
        if (h.learn(reading, surface)) {
            converter.learnedDictionary = h.asDictionary()
            learningChanged = true
        }
    }

    override fun convert(reading: String, n: Int): List<Conversion> =
        converter.convert(reading, n).map { Conversion(segmenter.segments(it.words)) }

    /**
     * ユーザー辞書、ラティスの上位3件、ひらがな・カタカナ、記号・絵文字、ラティスの残り、
     * その読みの辞書の語すべて、の順（記号を下位の当て字より前に出す。Mozc の SymbolRewriter も上位の少し後ろに入れる）。
     * 全角英数・記号を含む候補には半角のもの（逆も）をすぐ後ろに足す（「＋」と「+」、「！」と「!」）
     */
    override fun segmentCandidates(reading: String, n: Int): List<String> {
        val lattice = converter.convert(reading, maxOf(n, LATTICE_CANDIDATES)).map { it.text }
        val base = userDictionary.lookup(reading) + lattice.take(SYMBOL_POSITION) + reading + CharWidth.toKatakana(reading) +
            symbols.lookup(reading) + emoji.lookup(reading) + lattice.drop(SYMBOL_POSITION) + converter.exactWords(reading)
        return base.flatMap { listOf(it) + widthVariant(it) }.distinct()
    }

    /** 候補の窓で右に出す説明 */
    override fun labelFor(reading: String, candidate: String): String? = when {
        candidate in userDictionary.lookup(reading) -> "ユーザー辞書"
        candidate == reading && reading.any { it in '\u3041'..'\u3096' } -> "ひらがな"
        candidate == CharWidth.toKatakana(reading) && candidate != reading -> "カタカナ"
        candidate in emoji.lookup(reading) -> "絵文字"
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

    override fun pinned(reading: String): List<String> =
        (userDictionary.lookup(reading) + listOfNotNull(learning?.lookup(reading))).distinct()

    private companion object {
        const val SYMBOL_POSITION = 3

        /** 文節の候補でラティスから取る数（同じ読みの辞書の語は、これとは別に全部足す） */
        const val LATTICE_CANDIDATES = 30
    }

    private fun widthVariant(s: String): List<String> = when {
        CharWidth.hasFullWidthAscii(s) -> listOf(CharWidth.toHalfWidthAscii(s))
        CharWidth.hasHalfWidthAscii(s) -> listOf(CharWidth.toFullWidthAscii(s))
        else -> emptyList()
    }
}
