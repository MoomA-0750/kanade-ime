package dev.mooma.kanade.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CaretTest {
    private val source = object : CandidateSource {
        override fun convert(reading: String, n: Int) = listOf(Conversion(listOf(Segment(reading, "[$reading]"))))
        override fun segmentCandidates(reading: String, n: Int) = listOf("[$reading]")
    }

    private fun engine() = InputEngine(EngineSettings(), source).apply { handle(KeyInput.SetJapanese(true)) }
    private fun InputEngine.type(s: String) = s.forEach { handle(KeyInput.Text(it)) }
    private fun InputEngine.last(input: KeyInput) = handle(input).commands.filterIsInstance<ImeCommand.SetComposing>().last()

    @Test
    fun movingCaretShowsReadingWithCursor() {
        val e = engine()
        e.type("kyouha")
        assertEquals("[きょうは]", e.composingText)
        // 「きょ」は1文字ずつに分かれ、カーソルは文字単位で動く
        assertEquals(ImeCommand.SetComposing("きょうは", cursor = 3), e.last(KeyInput.Left))
        assertEquals(ImeCommand.SetComposing("きょうは", cursor = 0), e.last(KeyInput.Home))
        assertEquals(ImeCommand.SetComposing("きょうは", cursor = 1), e.last(KeyInput.Right))
        // 末尾に戻ればライブ変換に戻る
        assertEquals(ImeCommand.SetComposing("[きょうは]"), e.last(KeyInput.End))
    }

    @Test
    fun typingAndDeletingAtCaret() {
        val e = engine()
        e.type("kyouha")
        e.handle(KeyInput.Left)
        e.handle(KeyInput.Left)
        // 「きょ|うは」に「t」と打つと、綴りの途中もカーソルの位置に出る
        assertEquals(ImeCommand.SetComposing("きょtうは", cursor = 3), e.last(KeyInput.Text('t')))
        assertEquals(ImeCommand.SetComposing("きょとうは", cursor = 3), e.last(KeyInput.Text('o')))
        assertEquals(ImeCommand.SetComposing("きょうは", cursor = 2), e.last(KeyInput.Backspace))
        assertEquals(ImeCommand.SetComposing("きょは", cursor = 2), e.last(KeyInput.DeleteForward))
    }

    @Test
    fun spaceConvertsWholeAndEnterCommitsWhatIsShown() {
        val e = engine()
        e.type("abc") // あ・b・c
        e.handle(KeyInput.Left)
        e.type("n")
        // カーソルの位置にある綴りの途中の「n」は、変換の前に「ん」になってその位置に入る
        e.handle(KeyInput.Space)
        assertEquals("[あbんc]", e.composingText)

        val f = engine()
        f.type("abc")
        f.handle(KeyInput.Left)
        // カーソルが途中にあるあいだはかなで見えているので、Enter ではそのまま確定する
        assertEquals(listOf<ImeCommand>(ImeCommand.Commit("あbc")), f.handle(KeyInput.Enter).commands)
    }

    @Test
    fun leftArrowAtStartStays() {
        val e = engine()
        e.type("a")
        e.handle(KeyInput.Home)
        assertEquals(ImeCommand.SetComposing("あ", cursor = 0), e.last(KeyInput.Left))
    }
}
