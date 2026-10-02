package dev.mooma.kanade.core.convert

import dev.mooma.kanade.core.CandidateSource
import dev.mooma.kanade.core.CharWidth
import dev.mooma.kanade.core.Conversion
import dev.mooma.kanade.core.dict.SymbolTable
import dev.mooma.kanade.core.dict.UserDictionary

/** 辞書とラティスによる [CandidateSource]。文全体は N-best を文節に分けて返す */
class DictionaryCandidateSource(
    private val converter: KanaKanjiConverter,
    private val segmenter: Segmenter,
    private val symbols: SymbolTable = SymbolTable(emptyMap()),
) : CandidateSource {
    /** 設定画面で編集されたら差し替える。ラティスにも入れる */
    var userDictionary: UserDictionary
        get() = converter.userDictionary
        set(value) {
            converter.userDictionary = value
        }

    override fun convert(reading: String, n: Int): List<Conversion> =
        converter.convert(reading, n).map { Conversion(segmenter.segments(it.words)) }

    /**
     * ユーザー辞書、辞書の上位3件、ひらがな・カタカナ、記号・絵文字、辞書の残りの順
     * （記号を下位の当て字より前に出す。Mozc の SymbolRewriter も上位の少し後ろに入れる）。
     * 全角英数・記号を含む候補には半角のもの（逆も）をすぐ後ろに足す（「＋」と「+」、「！」と「!」）
     */
    override fun segmentCandidates(reading: String, n: Int): List<String> {
        val dict = converter.convert(reading, n).map { it.text }
        val base = userDictionary.lookup(reading) + dict.take(SYMBOL_POSITION) + reading + CharWidth.toKatakana(reading) +
            symbols.lookup(reading) + dict.drop(SYMBOL_POSITION)
        return base.flatMap { listOf(it) + widthVariant(it) }.distinct()
    }

    override fun pinned(reading: String): List<String> = userDictionary.lookup(reading)

    private companion object {
        const val SYMBOL_POSITION = 3
    }

    private fun widthVariant(s: String): List<String> = when {
        CharWidth.hasFullWidthAscii(s) -> listOf(CharWidth.toHalfWidthAscii(s))
        CharWidth.hasHalfWidthAscii(s) -> listOf(CharWidth.toFullWidthAscii(s))
        else -> emptyList()
    }
}
