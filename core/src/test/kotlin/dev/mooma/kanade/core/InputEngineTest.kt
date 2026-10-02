package dev.mooma.kanade.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputEngineTest {
    private fun engine() = InputEngine().apply { handle(KeyInput.SetJapanese(true)) }

    private fun InputEngine.type(s: String) = s.forEach { handle(KeyInput.Text(it)) }

    private fun kana(s: String): String = engine().run {
        type(s)
        (commitAll().single() as ImeCommand.Commit).text
    }

    @Test
    fun basicRomaji() {
        assertEquals("にほんご", kana("nihongo"))
        assertEquals("しんぶん", kana("shinbun"))
        assertEquals("きゃっと", kana("kyatto"))
        assertEquals("ちょっと", kana("chotto"))
        assertEquals("てぃーむ", kana("thi-mu"))
        assertEquals("ふぁいる", kana("fairu"))
        assertEquals("つづく", kana("tuduku"))
    }

    @Test
    fun nRules() {
        assertEquals("こんにちは", kana("konnnichiha"))
        assertEquals("きんえん", kana("kin'en"))
        assertEquals("ほん", kana("hon"))
        assertEquals("さんぽ", kana("sanpo"))
        assertEquals("こんや", kana("konnya"))
    }

    @Test
    fun smallKana() {
        assertEquals("ぁっゃ", kana("xaltuxya"))
    }

    @Test
    fun punctuationAndSymbols() {
        assertEquals("はい、そう。", kana("hai,sou."))
        assertEquals("「ー」", kana("[-]"))
        assertEquals("１２３", kana("123"))
        val half = InputEngine(EngineSettings(fullWidthSymbols = false)).apply { handle(KeyInput.SetJapanese(true)) }
        half.type("123")
        assertEquals(ImeCommand.Commit("123"), half.commitAll().single())
    }

    @Test
    fun pendingIsShownWhileComposing() {
        val e = engine()
        e.type("ky")
        assertEquals("ky", e.composingText)
        e.type("a")
        assertEquals("きゃ", e.composingText)
    }

    @Test
    fun backspace() {
        val e = engine()
        e.type("kyak")
        e.handle(KeyInput.Backspace)
        assertEquals("きゃ", e.composingText)
        e.handle(KeyInput.Backspace)
        assertEquals("き", e.composingText)
        e.type("tta")
        assertEquals("きった", e.composingText)
        e.handle(KeyInput.Backspace)
        e.handle(KeyInput.Backspace)
        e.handle(KeyInput.Backspace)
        assertFalse(e.isComposing)
    }

    @Test
    fun keysPassThroughWhenNotComposing() {
        val e = engine()
        assertFalse(e.handle(KeyInput.Backspace).consumed)
        assertFalse(e.handle(KeyInput.Enter).consumed)
        assertFalse(e.handle(KeyInput.Space).consumed)
        assertFalse(e.handle(KeyInput.Other).consumed)
    }

    @Test
    fun otherKeyCommitsThenPasses() {
        val e = engine()
        e.type("kan")
        val r = e.handle(KeyInput.Other)
        assertFalse(r.consumed)
        assertEquals(listOf<ImeCommand>(ImeCommand.Commit("かん")), r.commands)
    }

    @Test
    fun directModePassesEverything() {
        val e = InputEngine()
        assertFalse(e.handle(KeyInput.Text('a')).consumed)
        assertFalse(e.isComposing)
    }

    @Test
    fun turningOffCommits() {
        val e = engine()
        e.type("aiu")
        val r = e.handle(KeyInput.ToggleJapanese)
        assertTrue(r.consumed)
        assertEquals(listOf(ImeCommand.Commit("あいう"), ImeCommand.ModeChanged(false)), r.commands)
        assertFalse(e.japanese)
    }

    @Test
    fun escapeCancels() {
        val e = engine()
        e.type("aiu")
        assertEquals(listOf<ImeCommand>(ImeCommand.SetComposing("")), e.handle(KeyInput.Escape).commands)
        assertFalse(e.isComposing)
    }

    @Test
    fun widthConversion() {
        fun conv(s: String, t: WidthTarget) = engine().run {
            type(s)
            handle(KeyInput.ConvertWidth(t))
            composingText
        }
        assertEquals("カタカナ", conv("katakana", WidthTarget.KATAKANA))
        assertEquals("ｶﾀｶﾅ", conv("katakana", WidthTarget.HALF_KATAKANA))
        assertEquals("katakana", conv("katakana", WidthTarget.HALF_ALNUM))
        assertEquals("ｋａｎ", conv("kan", WidthTarget.FULL_ALNUM))
        assertEquals("がっこう", conv("gakkou", WidthTarget.HIRAGANA))
    }
}
