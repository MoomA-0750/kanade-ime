package dev.mooma.kanade.core.convert

import dev.mooma.kanade.core.dict.ConnectionMatrix
import dev.mooma.kanade.core.dict.DictionaryBuilder
import dev.mooma.kanade.core.dict.SystemDictionary
import dev.mooma.kanade.core.dict.WordEntry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConverterTest {
    // 品詞: 0=文頭文末、1=名詞、2=助詞
    private val words = listOf(
        WordEntry("はし", 1, 1, 3000, "橋"),
        WordEntry("はし", 1, 1, 3200, "箸"),
        WordEntry("はし", 1, 1, 3500, "端"),
        WordEntry("は", 2, 2, 1000, "は"),
        WordEntry("し", 1, 1, 4000, "市"),
        WordEntry("を", 2, 2, 500, "を"),
        WordEntry("わたる", 1, 1, 3000, "渡る"),
        WordEntry("てすと", 1, 1, 3000, "てすと"),
        WordEntry("てすと", 1, 1, 2000, "テスト"),
    )

    private fun dictionary(): SystemDictionary {
        val out = ByteArrayOutputStream()
        DictionaryBuilder.write(words, out)
        return SystemDictionary(ByteBuffer.wrap(out.toByteArray()))
    }

    private fun connection(): ConnectionMatrix {
        // 名詞→助詞・助詞→名詞は安く、名詞→名詞は高い
        val costs = listOf(
            0, 0, 0,
            0, 3000, 100,
            0, 100, 3000,
        )
        val out = ByteArrayOutputStream()
        ConnectionMatrix.writeFromMozc((listOf("3") + costs.map { it.toString() }).asSequence(), out)
        return ConnectionMatrix(ByteBuffer.wrap(out.toByteArray()))
    }

    @Test
    fun prefixSearchFindsAllLengths() {
        val d = dictionary()
        val hits = d.prefixSearch("はしを", 0).map { (len, idx) -> len to d.reading(idx) }
        assertEquals(listOf(1 to "は", 2 to "はし"), hits)
        assertEquals(emptyList(), d.prefixSearch("ぬ", 0))
    }

    @Test
    fun surfacesAreStoredCompactly() {
        val d = dictionary()
        val (_, idx) = d.prefixSearch("てすと", 0).single()
        // 同じ読みの中はコスト順
        assertEquals(listOf("テスト", "てすと"), d.entriesOf(idx).map { d.surface(it, "てすと") })
    }

    @Test
    fun nBestInCostOrder() {
        val c = KanaKanjiConverter(dictionary(), connection(), unknownPosId = 1)
        val result = c.convert("はしをわたる", 3)
        assertEquals(listOf("橋を渡る", "箸を渡る", "端を渡る"), result.map { it.text })
        assertEquals(listOf("橋", "を", "渡る"), result[0].words.map { it.surface })
        assertEquals(result.sortedBy { it.cost }, result)
    }

    @Test
    fun unknownCharactersStillConnect() {
        val c = KanaKanjiConverter(dictionary(), connection(), unknownPosId = 1)
        // 辞書にない「ぬ」は1文字の未知語として置かれ、後ろとつながる
        val best = c.convert("ぬはし", 1).single()
        assertEquals("ぬ", best.words.first().surface)
        assertEquals("ぬはし".length, best.words.sumOf { it.reading.length })
    }

    private class FakeModel(private val preferred: String) : LanguageModel {
        override fun score(leftContext: String, reading: String, candidates: List<String>, rightContext: String) =
            FloatArray(candidates.size) { if (candidates[it] == preferred) -1f else -20f }

        override fun generate(leftContext: String, reading: String, rightContext: String) = preferred
        override fun cancel() {}
    }

    @Test
    fun rerankerPromotesModelChoice() {
        val r = Reranker(FakeModel("箸を渡る"), topK = 3)
        assertEquals(listOf("箸を渡る", "橋を渡る", "端を渡る"), r.rerank("", "はしをわたる", listOf("橋を渡る", "箸を渡る", "端を渡る"))!!.candidates)
    }

    @Test
    fun rerankerAddsGeneratedCandidate() {
        val r = Reranker(FakeModel("ハシを渡る"), topK = 3)
        val result = r.rerank("", "はしをわたる", listOf("橋を渡る", "箸を渡る"), useGeneration = true)!!
        assertEquals("ハシを渡る", result.candidates.first())
        assertEquals("ハシを渡る", result.generated)
    }

    @Test
    fun rerankerReturnsNullWhenCancelled() {
        val cancelled = object : LanguageModel {
            override fun score(leftContext: String, reading: String, candidates: List<String>, rightContext: String): FloatArray? = null
            override fun generate(leftContext: String, reading: String, rightContext: String): String? = null
            override fun cancel() {}
        }
        assertNull(Reranker(cancelled).rerank("", "はし", listOf("橋")))
    }

    @Test
    fun symbolsAreAppendedToSegmentCandidates() {
        val symbols = dev.mooma.kanade.core.dict.SymbolTable.fromMozcSymbols(
            listOf("POS\tCHAR\tReading", "記号\t→\tやじるし みぎや ->", "記号\t←\tやじるし ひだりや <-"),
        )
        val emoji = dev.mooma.kanade.core.dict.SymbolTable.fromMozcEmoji(listOf("# comment", "1F600\t😀\tえがお かお"))
        assertEquals(listOf("→", "←"), symbols.lookup("やじるし"))
        assertEquals(emptyList(), symbols.lookup("->"))
        assertEquals(listOf("😀"), emoji.lookup("えがお"))
        assertEquals(symbols.lookup("やじるし"), dev.mooma.kanade.core.dict.SymbolTable.parse(symbols.write()).lookup("やじるし"))
        val source = DictionaryCandidateSource(
            KanaKanjiConverter(dictionary(), connection(), unknownPosId = 1),
            Segmenter(dev.mooma.kanade.core.dict.PosTable(ByteArray(3))),
            symbols,
        )
        assertEquals(listOf("→", "←"), source.segmentCandidates("やじるし", 5).filter { it in listOf("→", "←") })
        assertEquals("記号", source.labelFor("やじるし", "→"))
        assertEquals("ひらがな", source.labelFor("やじるし", "やじるし"))
        assertEquals("カタカナ", source.labelFor("やじるし", "ヤジルシ"))
    }

    @Test
    fun allWordsOfTheReadingAreCandidates() {
        val c = KanaKanjiConverter(dictionary(), connection(), unknownPosId = 1)
        assertEquals(listOf("橋", "箸", "端"), c.exactWords("はし"))
        val source = DictionaryCandidateSource(c, Segmenter(dev.mooma.kanade.core.dict.PosTable(ByteArray(3))))
        assertTrue(source.segmentCandidates("はし", 1).containsAll(listOf("橋", "箸", "端")))
    }

    @Test
    fun widthLabels() {
        val source = DictionaryCandidateSource(
            KanaKanjiConverter(dictionary(), connection(), unknownPosId = 1),
            Segmenter(dev.mooma.kanade.core.dict.PosTable(ByteArray(3))),
        )
        assertEquals("全角", source.labelFor("！", "！"))
        assertEquals("半角", source.labelFor("！", "!"))
        assertEquals(listOf("！", "!"), source.segmentCandidates("！", 3).filter { it == "！" || it == "!" })
    }

    @Test
    fun userWordsEnterLatticeWithPriority() {
        val c = KanaKanjiConverter(dictionary(), connection(), unknownPosId = 1)
        assertEquals("橋を渡る", c.convert("はしをわたる", 1).single().text)
        c.userDictionary = dev.mooma.kanade.core.dict.UserDictionary.parse("わたる 亘る")
        assertEquals("橋を亘る", c.convert("はしをわたる", 1).single().text)
    }
}
