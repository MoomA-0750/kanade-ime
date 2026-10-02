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
        for (a in targets) for (b in targets) for (c in targets) {
            val e = engine()
            "kanji".forEach { e.handle(KeyInput.Text(it)) }
            assertEquals(expected[a], e.conv(a), "$a")
            assertEquals(expected[b], e.conv(b), "$a→$b")
            assertEquals(expected[c], e.conv(c), "$a→$b→$c")
        }
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
