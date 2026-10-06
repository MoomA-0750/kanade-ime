package dev.mooma.kanade.core

import dev.mooma.kanade.core.glide.KanaSpelling
import dev.mooma.kanade.core.touch.NextCharModel
import dev.mooma.kanade.core.touch.TapCorrection
import dev.mooma.kanade.core.touch.TapCorrection.Option
import dev.mooma.kanade.core.touch.TapModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TapCorrectionTest {
    private val english = TapModels.english(
        """
        the 1000000
        there 300000
        then 200000
        they 400000
        what 500000
        when 300000
        """.trimIndent().lineSequence(),
    )

    @Test
    fun nextLetterFollowsWords() {
        // 「th」のあとは e が w よりずっと来やすい
        assertTrue(english.prob("^th", 'e') > english.prob("^th", 'w') * 5)
    }

    @Test
    fun ambiguousTapGoesToLikelyKey() {
        // th のあと、e と w の境目あたり（w に少し近い）を触った → e にする
        val i = TapCorrection.choose(listOf(Option('w', 0.48f), Option('e', 0.52f)), "^th", english)
        assertEquals(1, i)
    }

    @Test
    fun centerTapIsKept() {
        // キーの真ん中を触ったら、来にくい文字でもそのまま
        val i = TapCorrection.choose(listOf(Option('w', 0.2f), Option('e', 0.8f)), "^th", english)
        assertEquals(0, i)
    }

    @Test
    fun clearlyOffTapIsKept() {
        // はっきり w 寄りなら w のまま（来やすさだけで大きく動かさない）
        val i = TapCorrection.choose(listOf(Option('w', 0.35f), Option('e', 0.9f)), "^th", english)
        assertEquals(0, i)
    }

    @Test
    fun typingSpellings() {
        assertEquals(setOf("kitte"), KanaSpelling.typingSpellings("きって").toSet())
        assertTrue("konnnitiha" in KanaSpelling.typingSpellings("こんにちは"))
        assertTrue("ko-hi-" in KanaSpelling.typingSpellings("こーひー"))
        assertTrue("sinbun" in KanaSpelling.typingSpellings("しんぶん") || "shinbun" in KanaSpelling.typingSpellings("しんぶん"))
    }

    @Test
    fun japaneseRomajiModel() {
        val m = TapModels.japaneseRomaji("ありがとう\t3000\nあした\t3500\n".lineSequence())
        // 「arig」のあとは a が来やすい（ありがとう）、s は来にくい
        assertTrue(m.prob("arig", 'a') > m.prob("arig", 's') * 5)
    }

    @Test
    fun unseenContextStillHasProbability() {
        val m = NextCharModel()
        m.add("abc", 1f)
        assertTrue(m.prob("zzz", 'q') > 0f)
    }
}
