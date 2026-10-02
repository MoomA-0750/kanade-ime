package dev.mooma.kanade.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** F6〜F10（Ctrl+U/I/O/P/T）は、何度続けて押しても元の入力から作り直す */
class WidthChainTest {
    private fun engine() = InputEngine().apply { handle(KeyInput.SetJapanese(true)) }

    private fun InputEngine.conv(t: WidthTarget): String {
        handle(KeyInput.ConvertWidth(t))
        return composingText
    }

    @Test
    fun everyOrderWorks() {
        val targets = WidthTarget.entries
        val expected = mapOf(
            WidthTarget.HIRAGANA to "かんじ",
            WidthTarget.KATAKANA to "カンジ",
            WidthTarget.HALF_KATAKANA to "ｶﾝｼﾞ",
            WidthTarget.FULL_ALNUM to "ｋａｎｊｉ",
            WidthTarget.HALF_ALNUM to "kanji",
        )
        val alnum = setOf(WidthTarget.FULL_ALNUM, WidthTarget.HALF_ALNUM)
        // 英字のあとの半角カナ（F8）は半角英字になる
        fun want(prev: WidthTarget?, t: WidthTarget) =
            if (t == WidthTarget.HALF_KATAKANA && prev in alnum) expected[WidthTarget.HALF_ALNUM] else expected[t]
        for (a in targets) for (b in targets) for (c in targets) {
            val e = engine()
            "kanji".forEach { e.handle(KeyInput.Text(it)) }
            assertEquals(want(null, a), e.conv(a), "$a")
            val bActual = if (b == WidthTarget.HALF_KATAKANA && a in alnum) WidthTarget.HALF_ALNUM else b
            assertEquals(want(a, b), e.conv(b), "$a→$b")
            assertEquals(want(bActual, c), e.conv(c), "$a→$b→$c")
        }
    }

    @Test
    fun fullWidthAlnumThenHalfWidthGivesHalfWidthAlnum() {
        val e = engine()
        "kanji".forEach { e.handle(KeyInput.Text(it)) }
        assertEquals("ｋａｎｊｉ", e.conv(WidthTarget.FULL_ALNUM))
        assertEquals("kanji", e.conv(WidthTarget.HALF_KATAKANA))
        assertEquals("かんじ", e.conv(WidthTarget.HIRAGANA))
        assertEquals("ｶﾝｼﾞ", e.conv(WidthTarget.HALF_KATAKANA))
    }

    @Test
    fun typingAfterConversionContinuesFromConvertedText() {
        val e = engine()
        "kanji".forEach { e.handle(KeyInput.Text(it)) }
        e.conv(WidthTarget.HALF_ALNUM)
        "ha".forEach { e.handle(KeyInput.Text(it)) }
        assertEquals("kanjiは", e.composingText)
    }
}
