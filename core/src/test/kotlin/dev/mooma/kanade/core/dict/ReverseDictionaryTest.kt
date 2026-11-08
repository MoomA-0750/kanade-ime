package dev.mooma.kanade.core.dict

import dev.mooma.kanade.core.EngineSettings
import dev.mooma.kanade.core.ImeCommand
import dev.mooma.kanade.core.InputEngine
import dev.mooma.kanade.core.KeyInput
import dev.mooma.kanade.core.convert.DictionaryCandidateSource
import dev.mooma.kanade.core.convert.KanaKanjiConverter
import dev.mooma.kanade.core.convert.Segmenter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 表記→読みの逆引き（ほかのキーボード・音声で入れた文の再変換、音声の文にユーザー辞書の語を当てる） */
class ReverseDictionaryTest {
    private fun kdic(words: List<WordEntry>): SystemDictionary {
        val out = ByteArrayOutputStream()
        DictionaryBuilder.write(words, out)
        return SystemDictionary(ByteBuffer.wrap(out.toByteArray()))
    }

    /** 逆引き: 読みの欄に表記、表記の欄に読み。コストは Mozc の値（「書け」1349、接尾の「書」13）に近づける */
    private val reverse = ReverseDictionary(kdic(listOf(
        WordEntry("漢字", 1, 1, 3000, "かんじ"),
        WordEntry("感じ", 1, 1, 3000, "かんじ"),
        WordEntry("書け", 1, 1, 1349, "かけ"),
        WordEntry("書", 1, 1, 10, "しょ"),
        WordEntry("奏で", 1, 1, 3000, "かなで"),
        WordEntry("音", 1, 1, 3000, "おと"),
    )))

    @Test
    fun readsKanjiTextWithTheDictionary() {
        assertEquals("かんじがかけない", reverse.readingOf("漢字が書けない"))
        assertEquals(listOf("漢字", "が", "書け", "ない"), reverse.pieces("漢字が書けない")!!.map { it.surface })
        assertNull(reverse.readingOf("未知"))
    }

    private fun source(): DictionaryCandidateSource {
        val conn = ConnectionMatrix(ByteBuffer.wrap(ByteArrayOutputStream().also { out ->
            ConnectionMatrix.writeFromMozc(sequenceOf("2", "0", "0", "0", "0"), out)
        }.toByteArray()))
        val dict = kdic(listOf(WordEntry("かんじ", 1, 1, 3000, "感じ"), WordEntry("かんじ", 1, 1, 3500, "漢字"), WordEntry("が", 1, 1, 500, "が")))
        return DictionaryCandidateSource(KanaKanjiConverter(dict, conn, unknownPosId = 1), Segmenter(PosTable(ByteArray(3))))
            .also { it.reverse = reverse }
    }

    @Test
    fun userWordsReplaceMatchingReadings() {
        val s = source()
        s.userDictionary = UserDictionary.parse("かなで Kanade")
        assertEquals("Kanadeの音", s.applyUserWords("奏での音"))
        // 読みが合わなければそのまま
        assertEquals("漢字が", s.applyUserWords("漢字が"))
    }

    @Test
    fun textTypedElsewhereIsReconvertedKeepingItsKanji() {
        val e = InputEngine(EngineSettings(), source()).apply { handle(KeyInput.SetJapanese(true)) }
        // 辞書の1位は「感じが」だが、元の表記「漢字が」のまま変換中にする
        val view = e.reconvert("漢字が")!!.filterIsInstance<ImeCommand.ShowCandidates>().last()
        assertEquals("漢字", view.candidates.first())
        assertEquals("漢字が", e.composingText)
        assertEquals("かんじが", e.composingReading)
    }
}
