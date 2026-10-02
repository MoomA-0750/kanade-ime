package dev.mooma.kanade.core

import dev.mooma.kanade.core.dict.LearningHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 2026-10-03 のレビューで見つかった点の再発防止 */
class ReviewFixesTest {
    private class Source : CandidateSource {
        val learned = LearningHistory()
        override fun convert(reading: String, n: Int) =
            listOf(Conversion(listOf(Segment(reading, if (reading == "はし") "橋" else reading))), Conversion(listOf(Segment(reading, "$reading?"))))
        override fun segmentCandidates(reading: String, n: Int) = if (reading == "はし") listOf("橋", "箸", "端") else listOf(reading)
        override fun pinned(reading: String) = listOfNotNull(learned.lookup(reading))
        override fun learn(reading: String, surface: String) {
            learned.learn(reading, surface)
        }
    }

    private fun engine(source: CandidateSource = Source(), live: Boolean = true) =
        InputEngine(EngineSettings(liveConversion = live), source).apply { handle(KeyInput.SetJapanese(true)) }

    private fun InputEngine.type(s: String) = s.map { handle(KeyInput.Text(it)) }

    @Test
    fun rerankAloneIsNotLearned() {
        val source = Source()
        val e = engine(source)
        e.type("hashi")
        val req = e.handle(KeyInput.Space).commands.filterIsInstance<ImeCommand.RequestRerank>().last()
        e.onReranked(req.id, listOf("箸", "橋", "端"))
        assertEquals("箸", e.composingText)
        e.handle(KeyInput.Enter)
        assertNull(source.learned.lookup("はし"))
    }

    @Test
    fun choosingAgainstRerankIsLearned() {
        val source = Source()
        val e = engine(source)
        e.type("hashi")
        val req = e.handle(KeyInput.Space).commands.filterIsInstance<ImeCommand.RequestRerank>().last()
        e.onReranked(req.id, listOf("箸", "橋", "端"))
        e.handle(KeyInput.Space) // 橋
        e.handle(KeyInput.Enter)
        assertEquals("橋", source.learned.lookup("はし"))
    }

    @Test
    fun rerankWhileCaretInsideKeepsCursor() {
        val e = engine()
        val req = e.type("hashi").flatMap { it.commands }.filterIsInstance<ImeCommand.RequestRerank>().last()
        e.handle(KeyInput.Left)
        assertEquals(emptyList(), e.onReranked(req.id, listOf("はし?", "はし")))
        assertEquals("はし", e.composingText)
    }

    @Test
    fun f10AfterF9AndBackspaceGivesHalfWidth() {
        val e = engine()
        e.type("kana")
        e.handle(KeyInput.ConvertWidth(WidthTarget.FULL_ALNUM))
        e.handle(KeyInput.Backspace)
        e.handle(KeyInput.ConvertWidth(WidthTarget.HALF_ALNUM))
        assertEquals("kan", e.composingText)
        val f = engine()
        f.type("kana")
        f.handle(KeyInput.ConvertWidth(WidthTarget.KATAKANA))
        f.handle(KeyInput.Backspace)
        f.handle(KeyInput.ConvertWidth(WidthTarget.HALF_ALNUM))
        assertEquals("ka", f.composingText)
    }

    @Test
    fun noRerankWhileTypingWhenLiveConversionOff() {
        val e = engine(live = false)
        val requests = e.type("hashi").flatMap { it.commands }.filterIsInstance<ImeCommand.RequestRerank>()
        assertTrue(requests.isEmpty())
    }

    @Test
    fun digitWithoutCandidateIsIgnored() {
        val e = engine()
        e.type("hashi")
        e.handle(KeyInput.Space)
        val r = e.handle(KeyInput.Text('9'))
        assertTrue(r.consumed)
        assertEquals(emptyList(), r.commands)
        assertTrue(e.isConverting)
    }

    @Test
    fun tchBecomesSmallTsu() {
        val e = engine(live = false)
        e.type("matcha")
        assertEquals(ImeCommand.Commit("まっちゃ"), e.commitAll().first())
    }

    @Test
    fun singleCharLearnedReadingsStayOutOfLattice() {
        val h = LearningHistory()
        h.learn("は", "葉")
        h.learn("はし", "箸")
        assertEquals(emptyList(), h.asDictionary().lookup("は"))
        assertEquals(listOf("箸"), h.asDictionary().lookup("はし"))
    }
}
