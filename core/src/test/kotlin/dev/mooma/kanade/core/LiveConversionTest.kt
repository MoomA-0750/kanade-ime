package dev.mooma.kanade.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveConversionTest {
    /** 読みごとの候補を表で返す偽の辞書 */
    private val table = mapOf(
        "はし" to listOf("橋", "箸", "端"),
        "はしで" to listOf("橋で", "箸で"),
        "かん" to listOf("缶", "感"),
        "か" to listOf("課", "蚊"),
    )

    private fun engine(live: Boolean = true) = InputEngine(
        EngineSettings(liveConversion = live),
        CandidateSource { r, _ -> table[r] ?: listOf(r) },
    ).apply { handle(KeyInput.SetJapanese(true)) }

    private fun InputEngine.type(s: String) = s.map { handle(KeyInput.Text(it)) }

    @Test
    fun showsDictionaryBestWhileTyping() {
        val e = engine()
        e.type("hash")
        // 「は」は表にないので読みのまま。綴りの途中は後ろに付く
        assertEquals("はsh", e.composingText)
        e.type("i")
        assertEquals("橋", e.composingText)
    }

    @Test
    fun requestsRerankWhenReadingChanges() {
        val e = engine()
        val results = e.type("hashi")
        val requests = results.flatMap { it.commands }.filterIsInstance<ImeCommand.RequestRerank>()
        // 「は」は候補が1つ（読みそのまま）なので頼まない
        assertEquals(listOf("はし"), requests.map { it.reading })
        assertEquals(3, requests.single().candidates.size)
    }

    @Test
    fun rerankReplacesBestUnlessStale() {
        val e = engine()
        val req = e.type("hashi").flatMap { it.commands }.filterIsInstance<ImeCommand.RequestRerank>().last()
        assertEquals(listOf<ImeCommand>(ImeCommand.SetComposing("箸")), e.onReranked(req.id, listOf("箸", "橋", "端")))
        assertEquals("箸", e.composingText)
        // 読みが変わったあとに古い結果が来ても無視する
        e.type("de")
        assertEquals(emptyList(), e.onReranked(req.id, listOf("端")))
        assertEquals("橋で", e.composingText)
    }

    @Test
    fun enterCommitsConvertedText() {
        val e = engine()
        e.type("hashi")
        assertEquals(listOf<ImeCommand>(ImeCommand.Commit("橋")), e.handle(KeyInput.Enter).commands)
        assertEquals("橋", e.leftContext)
    }

    @Test
    fun trailingNIsFlushedBeforeCommit() {
        val e = engine()
        e.type("kan")
        assertEquals("課n", e.composingText)
        assertEquals(listOf<ImeCommand>(ImeCommand.Commit("缶")), e.handle(KeyInput.Enter).commands)
    }

    @Test
    fun spaceCyclesCandidatesAndDigitsSelect() {
        val e = engine()
        e.type("hashi")
        val first = e.handle(KeyInput.Space).commands
        assertTrue(ImeCommand.ShowCandidates(listOf("橋", "箸", "端"), 0) in first)
        assertTrue(e.isConverting)
        e.handle(KeyInput.Space)
        assertEquals("箸", e.composingText)
        e.handle(KeyInput.ShiftSpace)
        e.handle(KeyInput.ShiftSpace)
        assertEquals("端", e.composingText)
        assertEquals(
            listOf(ImeCommand.Commit("箸"), ImeCommand.HideCandidates),
            e.handle(KeyInput.Text('2')).commands,
        )
        assertFalse(e.isComposing)
    }

    @Test
    fun typingWhileConvertingCommitsAndContinues() {
        val e = engine()
        e.type("hashi")
        e.handle(KeyInput.Space)
        e.handle(KeyInput.Space)
        val r = e.handle(KeyInput.Text('k'))
        assertTrue(ImeCommand.Commit("箸") in r.commands)
        assertEquals("k", e.composingText)
    }

    @Test
    fun escapeAndBackspaceLeaveConversionShowingReading() {
        val e = engine()
        e.type("hashi")
        e.handle(KeyInput.Space)
        assertTrue(ImeCommand.HideCandidates in e.handle(KeyInput.Escape).commands)
        assertEquals("はし", e.composingText)
        assertEquals(listOf<ImeCommand>(ImeCommand.Commit("はし")), e.handle(KeyInput.Enter).commands)

        e.type("hashi")
        e.handle(KeyInput.Space)
        e.handle(KeyInput.Backspace)
        assertFalse(e.isConverting)
        assertEquals("はし", e.composingText)
        // 次に打てばまたライブ変換に戻る
        e.type("de")
        assertEquals("橋で", e.composingText)
    }

    @Test
    fun rerankDuringConversionKeepsUserChoice() {
        val e = engine()
        val req = e.type("hashi").flatMap { it.commands }.filterIsInstance<ImeCommand.RequestRerank>().last()
        e.handle(KeyInput.Space)
        e.handle(KeyInput.Space) // 箸を選ぶ
        e.onReranked(req.id, listOf("端", "箸", "橋"))
        assertEquals("箸", e.composingText)
    }

    @Test
    fun liveConversionOffShowsKanaUntilSpace() {
        val e = engine(live = false)
        e.type("hashi")
        assertEquals("はし", e.composingText)
        e.handle(KeyInput.Space)
        assertEquals("橋", e.composingText)
    }

    @Test
    fun widthConversionIsNotLiveConverted() {
        val e = engine()
        e.type("hashi")
        e.handle(KeyInput.ConvertWidth(WidthTarget.KATAKANA))
        assertEquals("ハシ", e.composingText)
        assertEquals(listOf<ImeCommand>(ImeCommand.Commit("ハシ")), e.handle(KeyInput.Enter).commands)
    }
}
