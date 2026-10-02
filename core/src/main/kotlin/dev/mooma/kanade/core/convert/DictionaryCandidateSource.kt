package dev.mooma.kanade.core.convert

import dev.mooma.kanade.core.CandidateSource
import dev.mooma.kanade.core.CharWidth
import dev.mooma.kanade.core.Conversion
import dev.mooma.kanade.core.dict.SymbolTable

/** 辞書とラティスによる [CandidateSource]。文全体は N-best を文節に分けて返す */
class DictionaryCandidateSource(
    private val converter: KanaKanjiConverter,
    private val segmenter: Segmenter,
    private val symbols: SymbolTable = SymbolTable(emptyMap()),
) : CandidateSource {
    override fun convert(reading: String, n: Int): List<Conversion> =
        converter.convert(reading, n).map { Conversion(segmenter.segments(it.words)) }

    override fun segmentCandidates(reading: String, n: Int): List<String> =
        (converter.convert(reading, n).map { it.text } + reading + CharWidth.toKatakana(reading) + symbols.lookup(reading)).distinct()
}
