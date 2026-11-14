package dev.mooma.kanade.core

import dev.mooma.kanade.core.dict.EmojiShortcodes
import dev.mooma.kanade.core.dict.Snippets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `:rocket` → 🚀（SPEC の「絵文字を名前で入れる」） */
class EmojiShortcodeTest {
    private fun engine(japanese: Boolean = true, settings: EngineSettings = EngineSettings(liveConversion = false)) =
        InputEngine(settings).apply { if (japanese) handle(KeyInput.SetJapanese(true)) }

    private fun InputEngine.type(s: String): List<ImeCommand> = s.flatMap { handle(KeyInput.Text(it)).commands }

    private fun List<ImeCommand>.commits() = filterIsInstance<ImeCommand.Commit>().joinToString("") { it.text }
    private fun List<ImeCommand>.lastCandidates() = filterIsInstance<ImeCommand.ShowCandidates>().lastOrNull()

    @Test
    fun tableIsBundledInCore() {
        val t = EmojiShortcodes.default
        assertTrue(t.size > 1500, "表の件数 ${t.size}")
        assertEquals("🚀", t.exact("rocket"))
        assertEquals("👍", t.exact("+1"))
        assertEquals("🦖", t.exact("t-rex"))
    }

    @Test
    fun typingNameShowsCandidatesAndEnterInsertsFirst() {
        val e = engine()
        val commands = e.type(":rocket")
        assertEquals(":rocket", e.composingText)
        val c = commands.lastCandidates()!!
        assertEquals("🚀", c.candidates.first())
        assertEquals(":rocket:", c.labels.first())
        assertFalse(c.prediction, "数字で選べるので番号を付ける")
        val r = e.handle(KeyInput.Enter)
        assertEquals("🚀", r.commands.commits())
        assertTrue(ImeCommand.HideCandidates in r.commands)
        assertFalse(e.isComposing)
    }

    @Test
    fun closingColonInsertsExactName() {
        val e = engine()
        assertEquals("👍", e.type(":+1:").commits())
        assertEquals("🚀", e.type(":rocket:").commits())
    }

    @Test
    fun prefixMatchesComeFirstAndSpaceMovesSelection() {
        val e = engine()
        val c = e.type(":rock").lastCandidates()!!
        assertTrue("🚀" in c.candidates)
        assertTrue(c.labels.all { it!!.startsWith(":") })
        val moved = e.handle(KeyInput.Space).commands.lastCandidates()!!
        assertEquals(1, moved.selected)
        assertEquals(c.candidates[1], e.handle(KeyInput.Enter).commands.commits())
    }

    @Test
    fun digitSelectsWhenNoNameContinuesWithIt() {
        val e = engine()
        val c = e.type(":smile").lastCandidates()!!
        assertEquals(c.candidates[1], e.handle(KeyInput.Text('2')).commands.commits())
        // `:10` は `:100:`（💯）の途中なので、数字は名前の続き
        val f = engine()
        f.type(":10")
        assertEquals(":10", f.composingText)
        assertEquals("💯", f.handle(KeyInput.Enter).commands.commits())
    }

    @Test
    fun noMatchFallsBackToOrdinaryJapaneseInput() {
        val e = engine()
        e.type(":arigatou")
        // 名前に合わなくなったところで、ふつうに打ったのと同じになる
        assertEquals("：ありがとう", e.composingText)
        assertEquals("：ありがとう", e.handle(KeyInput.Enter).commands.commits())
    }

    @Test
    fun escapeTypesTheLettersAsUsual() {
        val e = engine()
        e.type(":sushi")
        assertEquals(":sushi", e.composingText)
        e.handle(KeyInput.Escape)
        assertEquals("：すし", e.composingText)
    }

    @Test
    fun backspaceEditsNameAndLeavesOnBareColon() {
        val e = engine()
        e.type(":rocketx")
        // rocketx に合うものは無いので、ふつうの入力に戻っている
        assertEquals("：ろcけtx", e.composingText)
        val f = engine()
        f.type(":ro")
        f.handle(KeyInput.Backspace)
        assertEquals(":r", f.composingText)
        f.handle(KeyInput.Backspace)
        assertEquals(":", f.composingText)
        val hide = f.handle(KeyInput.Backspace)
        assertFalse(f.isComposing)
        assertTrue(hide.commands.contains(ImeCommand.SetComposing("")))
    }

    @Test
    fun colonAfterLettersOrDigitsIsOrdinary() {
        val e = engine(japanese = false)
        val r = e.type("10")
        assertTrue(r.isEmpty())
        assertFalse(e.handle(KeyInput.Text(':')).consumed, "10:30 の : は素通し")
        val j = engine()
        j.leftContext = "http"
        j.type(":")
        assertEquals("：", j.composingText)
    }

    @Test
    fun colonWhileComposingIsOrdinary() {
        val e = engine()
        e.type("rei:")
        assertEquals("れい：", e.composingText)
    }

    @Test
    fun directInputSpaceKeepsTheText() {
        val e = engine(japanese = false)
        assertTrue(e.handle(KeyInput.Text(':')).consumed)
        e.handle(KeyInput.Text('p'))
        val r = e.handle(KeyInput.Space)
        assertEquals(":p", r.commands.commits())
        assertFalse(r.consumed, "空白はアプリへ")
        assertFalse(e.isComposing)
        val f = engine(japanese = false)
        assertEquals("🚀", f.type(":rocket:").commits())
    }

    @Test
    fun recentEmojiComesFirst() {
        val e = engine()
        val first = e.type(":heart").lastCandidates()!!
        val pick = first.candidates[3]
        e.handle(KeyInput.SelectCandidate(3)).commands.commits().let { assertEquals(pick, it) }
        val again = e.type(":heart").lastCandidates()!!
        // ちょうど合う名前（:heart: ❤️）の次に、最近選んだもの
        assertEquals(pick, again.candidates[1])
    }

    @Test
    fun snippetWithColonKeyWins() {
        val e = engine()
        e.snippets = Snippets.parse(":) 😊\n")
        assertEquals("😊", e.type(":)").commits())
        assertFalse(e.isComposing)
    }

    @Test
    fun canBeTurnedOff() {
        val e = engine(settings = EngineSettings(liveConversion = false, emojiShortcodes = false))
        e.type(":ro")
        assertEquals("：ろ", e.composingText)
    }

    @Test
    fun commitAllCommitsWhatIsShown() {
        val e = engine()
        e.type(":ro")
        assertEquals(":ro", e.commitAll().commits())
        assertNull(e.type("").lastCandidates())
    }
}
