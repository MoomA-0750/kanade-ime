package dev.mooma.kanade.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BracketTest {
    private fun engine() = InputEngine().apply { handle(KeyInput.SetJapanese(true)) }

    private fun InputEngine.type(s: String) = s.map { handle(KeyInput.Text(it)) }.last()

    private fun lastComposing(r: EngineResult) = r.commands.filterIsInstance<ImeCommand.SetComposing>().last()

    @Test
    fun openingBracketAddsCloserAndKeepsCursorInside() {
        val e = engine()
        val r = e.type("[")
        val c = lastComposing(r)
        assertEquals("「」", c.text)
        assertEquals(1, c.cursor)
        val r2 = e.type("a")
        assertEquals("「あ」", lastComposing(r2).text)
        assertEquals(2, lastComposing(r2).cursor)
        assertEquals(listOf<ImeCommand>(ImeCommand.Commit("「あ」")), e.commitAll())
    }

    @Test
    fun typingTheCloserStepsOverIt() {
        val e = engine()
        e.type("[a]")
        // 閉じ括弧を打っても重ならず、後ろへ進む
        assertEquals(listOf<ImeCommand>(ImeCommand.Commit("「あ」")), e.commitAll())
        val e2 = engine()
        e2.type("[a]i")
        assertEquals(listOf<ImeCommand>(ImeCommand.Commit("「あ」い")), e2.commitAll())
    }

    @Test
    fun backspaceOnEmptyPairRemovesBoth() {
        val e = engine()
        e.type("[")
        val r = e.handle(KeyInput.Backspace)
        assertTrue(r.commands.any { it is ImeCommand.SetComposing && it.text.isEmpty() })
        assertEquals(emptyList(), e.commitAll())
    }

    @Test
    fun spaceOnEmptyPairCyclesBrackets() {
        val e = engine()
        e.type("(")
        assertEquals("（）", e.composingText)
        val r = e.handle(KeyInput.Space)
        assertEquals("「」", lastComposing(r).text)
        assertTrue(r.commands.any { it is ImeCommand.ShowCandidates && it.candidates == Brackets.PAIRS })
        e.handle(KeyInput.Space)
        assertEquals("『』", e.composingText)
        // 候補の窓から選べる
        e.handle(KeyInput.SelectCandidate(Brackets.PAIRS.indexOf("【】")))
        assertEquals("【】", e.composingText)
        // そのまま中に打てる
        e.type("a")
        assertEquals(listOf<ImeCommand>(ImeCommand.Commit("【あ】")), e.commitAll())
    }

    @Test
    fun escapeWhileChoosingClosesCandidates() {
        val e = engine()
        e.type("(")
        e.handle(KeyInput.Space)
        val r = e.handle(KeyInput.Escape)
        assertTrue(r.commands.any { it == ImeCommand.HideCandidates })
        assertTrue(r.commands.any { it is ImeCommand.SetComposing && it.text.isEmpty() })
    }

    @Test
    fun bracketAlternatives() {
        assertTrue("「" in Brackets.alternatives("（"))
        assertTrue("」" in Brackets.alternatives("）"))
        assertEquals(emptyList(), Brackets.alternatives("あ"))
    }

    @Test
    fun canBeTurnedOff() {
        val e = InputEngine(EngineSettings(pairBrackets = false)).apply { handle(KeyInput.SetJapanese(true)) }
        e.type("[")
        assertEquals("「", e.composingText)
    }
}
