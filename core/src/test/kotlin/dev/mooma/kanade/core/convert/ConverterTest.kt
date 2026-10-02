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
        override fun score(leftContext: String, reading: String, candidates: List<String>) =
            FloatArray(candidates.size) { if (candidates[it] == preferred) -1f else -20f }

        override fun generate(leftContext: String, reading: String) = preferred
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
            override fun score(leftContext: String, reading: String, candidates: List<String>): FloatArray? = null
            override fun generate(leftContext: String, reading: String): String? = null
            override fun cancel() {}
        }
        assertNull(Reranker(cancelled).rerank("", "はし", listOf("橋")))
    }
}
