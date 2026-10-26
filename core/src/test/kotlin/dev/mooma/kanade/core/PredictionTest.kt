package dev.mooma.kanade.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 予測の候補と再変換 */
class PredictionTest {
    /** 「きーぼ」で始まる読みに「キーボード」「キーボード配列」を返し、変換は読みのカタカナ・「書記」だけの小さな候補 */
    private class Source : CandidateSource {
        override fun convert(reading: String, n: Int) = listOf(
            Conversion(listOf(Segment(reading, if (reading == "しょき") "書記" else CharWidth.toKatakana(reading)))),
        )
        override fun segmentCandidates(reading: String, n: Int) =
            if (reading == "しょき") listOf("書記", "初期", "暑気") else listOf(CharWidth.toKatakana(reading), reading)
        override fun predict(reading: String, n: Int) =
            if ("きーぼーど".startsWith(reading) && reading.length < 5) listOf("キーボード", "キーボード配列").take(n) else emptyList()
    }

    private fun engine(prediction: Boolean = true) = InputEngine(EngineSettings(prediction = prediction), Source()).apply {
        handle(KeyInput.SetJapanese(true))
    }

    private fun InputEngine.type(s: String) = s.map { handle(KeyInput.Text(it)) }.last()

    @Test
    fun predictionsAppearWhileTyping() {
        val e = engine()
        val shown = e.type("ki-bo").commands.filterIsInstance<ImeCommand.ShowCandidates>().single()
        assertEquals(listOf("キーボード", "キーボード配列"), shown.candidates)
        assertTrue(shown.prediction)
        assertEquals(-1, shown.selected)
    }

    @Test
    fun noPredictionsWhenTurnedOff() {
        val e = engine(prediction = false)
        assertTrue(e.type("ki-bo").commands.none { it is ImeCommand.ShowCandidates })
    }

    @Test
    fun choosingAPredictionCommitsIt() {
        val e = engine()
        e.type("ki-bo")
        val r = e.handle(KeyInput.SelectCandidate(1))
        assertEquals(ImeCommand.Commit("キーボード配列"), r.commands.first())
        assertTrue(ImeCommand.HideCandidates in r.commands)
        assertTrue(!e.isComposing)
    }

    @Test
    fun tabSelectsAndEnterCommitsAPrediction() {
        val e = engine()
        e.type("ki-bo")
        val shown = e.handle(KeyInput.Tab).commands.filterIsInstance<ImeCommand.ShowCandidates>().single()
        assertEquals(0, shown.selected)
        assertEquals(ImeCommand.Commit("キーボード"), e.handle(KeyInput.Enter).commands.first())
    }

    @Test
    fun predictionsHideWhenTheReadingNoLongerMatches() {
        val e = engine()
        e.type("ki-bo")
        assertTrue(ImeCommand.HideCandidates in e.type("ra").commands)
    }

    @Test
    fun reconvertsWhatWasCommitted() {
        val e = engine(prediction = false)
        e.type("shoki")
        e.handle(KeyInput.Space)
        e.handle(KeyInput.Space) // 初期
        assertEquals(ImeCommand.Commit("初期"), e.handle(KeyInput.Enter).commands.first())
        assertEquals("しょき", e.readingOf("初期"))
        // 選んだ「初期」を変換し直す。元の表記が最初の候補で、Space で次へ
        val view = e.reconvert("初期")!!.filterIsInstance<ImeCommand.ShowCandidates>().last()
        assertEquals("初期", view.candidates.first())
        assertTrue("書記" in view.candidates)
        e.handle(KeyInput.Space)
        assertEquals(ImeCommand.Commit("書記"), e.handle(KeyInput.Enter).commands.first())
    }

    @Test
    fun escapeRestoresTheOriginalOnReconversion() {
        val e = engine(prediction = false)
        e.reconvert("しょき")!!
        e.handle(KeyInput.Space)
        assertEquals(ImeCommand.Commit("しょき"), e.handle(KeyInput.Escape).commands.first())
    }

    @Test
    fun readingsOfKanaAndKnownWordsCombine() {
        val e = engine(prediction = false)
        e.type("shoki")
        e.handle(KeyInput.Space)
        e.handle(KeyInput.Enter)
        assertEquals("しょきのせってい", e.readingOf("書記のせってい"))
        assertEquals("かたかな", e.readingOf("カタカナ"))
        // 覚えていない漢字があれば読めない
        assertNull(e.readingOf("設定"))
        assertNull(e.reconvert("設定"))
    }

    @Test
    fun reconversionKeepsTheCommittedSegments() {
        val e = engine(prediction = false)
        e.type("shoki")
        e.handle(KeyInput.Space)
        e.handle(KeyInput.Space) // 初期
        e.handle(KeyInput.Enter)
        // 「初期」は確定したときの表記のまま1つの文節になり、続くかなは変換し直す
        val view = e.reconvert("初期は")!!
        assertEquals(listOf(2, 1), view.filterIsInstance<ImeCommand.SetComposing>().last().segmentLengths)
        assertTrue(view.filterIsInstance<ImeCommand.SetComposing>().last().text.startsWith("初期"))
    }
}
