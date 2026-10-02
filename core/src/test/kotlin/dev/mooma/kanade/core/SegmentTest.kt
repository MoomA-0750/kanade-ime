package dev.mooma.kanade.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SegmentTest {
    /** 「はしを|わたる」の2文節に分ける偽の辞書。文節の候補は表で返す */
    private val segmentTable = mapOf(
        "はしを" to listOf("橋を", "箸を", "端を"),
        "わたる" to listOf("渡る", "亘る"),
        "はし" to listOf("橋", "箸"),
        "をわたる" to listOf("を渡る"),
        "はしをわ" to listOf("はしをわ"),
        "たる" to listOf("樽", "たる"),
    )
    private val source = object : CandidateSource {
        override fun convert(reading: String, n: Int): List<Conversion> = when (reading) {
            "はしをわたる" -> listOf(Conversion(listOf(Segment("はしを", "橋を"), Segment("わたる", "渡る"))))
            "をわたる" -> listOf(Conversion(listOf(Segment("を", "を"), Segment("わたる", "渡る"))))
            "たる" -> listOf(Conversion(listOf(Segment("たる", "樽"))))
            else -> listOf(Conversion(listOf(Segment(reading, reading))))
        }

        override fun segmentCandidates(reading: String, n: Int) = segmentTable[reading] ?: listOf(reading)
    }

    private fun converting(): InputEngine = InputEngine(EngineSettings(), source).apply {
        handle(KeyInput.SetJapanese(true))
        "hashiwowataru".forEach { handle(KeyInput.Text(it)) }
        handle(KeyInput.Space)
    }

    private fun InputEngine.lastComposing(r: EngineResult) = r.commands.filterIsInstance<ImeCommand.SetComposing>().last()

    @Test
    fun spaceSplitsIntoSegmentsWithFocusOnFirst() {
        val e = InputEngine(EngineSettings(), source).apply { handle(KeyInput.SetJapanese(true)) }
        "hashiwowataru".forEach { e.handle(KeyInput.Text(it)) }
        val r = e.handle(KeyInput.Space)
        assertEquals(ImeCommand.SetComposing("橋を渡る", listOf(2, 2), 0), e.lastComposing(r))
        assertTrue(ImeCommand.ShowCandidates(listOf("橋を", "箸を", "端を"), 0, 0) in r.commands)
    }

    @Test
    fun cyclingChangesOnlyFocusedSegment() {
        val e = converting()
        e.handle(KeyInput.Space)
        assertEquals("箸を渡る", e.composingText)
        val r = e.handle(KeyInput.Right)
        assertTrue(ImeCommand.ShowCandidates(listOf("渡る", "亘る"), 0, 2) in r.commands)
        e.handle(KeyInput.Down)
        assertEquals("箸を亘る", e.composingText)
        assertEquals(listOf<ImeCommand>(ImeCommand.Commit("箸を亘る"), ImeCommand.HideCandidates), e.handle(KeyInput.Enter).commands)
    }

    @Test
    fun shrinkingFocusedSegmentResegmentsTheRest() {
        val e = converting()
        val r = e.handle(KeyInput.ShiftLeft)
        // 「はし」と「を」「わたる」に分け直す（表記は 橋|を|渡る）
        assertEquals(ImeCommand.SetComposing("橋を渡る", listOf(1, 1, 2), 0), e.lastComposing(r))
        e.handle(KeyInput.Down)
        assertEquals("箸を渡る", e.composingText)
    }

    @Test
    fun growingFocusedSegment() {
        val e = converting()
        e.handle(KeyInput.ShiftRight)
        val r = e.handle(KeyInput.Space)
        // 「はしをわ」と「たる」
        assertEquals(listOf(4, 1), e.lastComposing(r).segmentLengths)
        assertEquals("はしをわ樽", e.composingText)
    }

    @Test
    fun digitSelectsAndMovesToNextSegment() {
        val e = converting()
        e.handle(KeyInput.Text('2'))
        assertEquals("箸を渡る", e.composingText)
        assertTrue(e.isConverting)
        val r = e.handle(KeyInput.Text('2'))
        // 最後の文節では確定しない（Enter で確定）
        assertEquals("箸を亘る", e.composingText)
        assertTrue(r.consumed)
    }

    @Test
    fun segmentRerankUsesWholeSentence() {
        val e = InputEngine(EngineSettings(), source).apply { handle(KeyInput.SetJapanese(true)) }
        "hashiwowataru".forEach { e.handle(KeyInput.Text(it)) }
        val req = e.handle(KeyInput.Space).commands.filterIsInstance<ImeCommand.RequestRerank>().last()
        assertEquals(listOf("橋を渡る", "箸を渡る", "端を渡る"), req.candidates)
        e.onReranked(req.id, listOf("箸を渡る", "橋を渡る", "端を渡る"))
        assertEquals("箸を渡る", e.composingText)
    }

    @Test
    fun shiftArrowWhileComposingStartsConversion() {
        val e = InputEngine(EngineSettings(), source).apply { handle(KeyInput.SetJapanese(true)) }
        "hashiwowataru".forEach { e.handle(KeyInput.Text(it)) }
        e.handle(KeyInput.ShiftLeft)
        assertTrue(e.isConverting)
        assertEquals("橋を渡る", e.composingText)
    }

    @Test
    fun tappingCandidateSelectsLikeDigit() {
        val e = converting()
        e.handle(KeyInput.SelectCandidate(2))
        assertEquals("端を渡る", e.composingText)
        e.handle(KeyInput.SelectCandidate(1))
        assertEquals("端を亘る", e.composingText)
    }
}
