package dev.mooma.kanade.core

import dev.mooma.kanade.core.dict.Snippets
import dev.mooma.kanade.core.dict.UserDictionary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserDictionaryTest {
    private val dict = UserDictionary.parse(
        """
        # コメント
        なかの 中野
        なかの 仲野
        キーだけ
        """.trimIndent(),
    )
    private val snippets = Snippets.parse(
        """
        @@ me@example.com
        -> →
        --> ⟶
        sig お世話になっております。\nよろしくお願いいたします。
        """.trimIndent(),
    )

    @Test
    fun parsesLines() {
        assertEquals(2, dict.size)
        assertEquals(listOf("中野", "仲野"), dict.lookup("なかの"))
        assertEquals(4, snippets.size)
    }

    @Test
    fun snippetMatchesLongestSuffix() {
        assertEquals("⟶", snippets.matchSuffix("a-->")?.second)
        assertEquals("→", snippets.matchSuffix("x->")?.second)
        assertNull(snippets.matchSuffix("->x"))
        assertEquals("お世話になっております。\nよろしくお願いいたします。", snippets.matchSuffix("sig")?.second)
    }

    /** 偽の辞書（何でも読みのまま）にユーザー辞書を付けたもの */
    private val source = object : CandidateSource {
        override fun convert(reading: String, n: Int) = listOf(Conversion(listOf(Segment(reading, reading))), Conversion(listOf(Segment(reading, reading + "?"))))
        override fun segmentCandidates(reading: String, n: Int) = listOf(reading)
        override fun pinned(reading: String) = dict.lookup(reading)
    }

    private fun engine() = InputEngine(EngineSettings(), source).apply {
        snippets = this@UserDictionaryTest.snippets
        handle(KeyInput.SetJapanese(true))
    }

    @Test
    fun userWordIsFirstAndStaysFirstAfterRerank() {
        val e = engine()
        val req = "nakano".map { e.handle(KeyInput.Text(it)) }.flatMap { it.commands }.filterIsInstance<ImeCommand.RequestRerank>().last()
        assertEquals("中野", e.composingText)
        assertTrue("中野" !in req.candidates)
        e.onReranked(req.id, listOf("なかの?", "なかの"))
        assertEquals("中野", e.composingText)
        e.handle(KeyInput.Space)
        e.handle(KeyInput.Space)
        assertEquals("仲野", e.composingText)
    }

    @Test
    fun snippetReplacesImmediatelyInJapaneseMode() {
        val e = engine()
        "kyouha".forEach { e.handle(KeyInput.Text(it)) }
        e.handle(KeyInput.Text('-'))
        val r = e.handle(KeyInput.Text('>'))
        // 前の未確定（いま見えている形）を確定してから、キーを出力に置き換える
        assertEquals(listOf<ImeCommand>(ImeCommand.Commit("きょうは"), ImeCommand.Commit("→")), r.commands)
        assertTrue(!e.isComposing)
    }

    @Test
    fun snippetWithLetterKeyInJapaneseMode() {
        val e = engine()
        val r = "sig".map { e.handle(KeyInput.Text(it)) }.last()
        assertEquals(listOf<ImeCommand>(ImeCommand.Commit("お世話になっております。\nよろしくお願いいたします。")), r.commands)
    }

    @Test
    fun snippetInDirectMode() {
        val e = InputEngine().apply { snippets = this@UserDictionaryTest.snippets }
        assertTrue(!e.handle(KeyInput.Text('a')).consumed)
        assertTrue(!e.handle(KeyInput.Text('@')).consumed)
        val r = e.handle(KeyInput.Text('@'))
        assertTrue(r.consumed)
        assertEquals(listOf<ImeCommand>(ImeCommand.ReplaceBeforeCursor("@", '@', "me@example.com")), r.commands)
        // 文字以外のキーで打鍵の並びは途切れる
        e.handle(KeyInput.Text('-'))
        e.handle(KeyInput.Backspace)
        assertTrue(!e.handle(KeyInput.Text('>')).consumed)
    }
}
