package dev.mooma.kanade.core

import dev.mooma.kanade.core.glide.GlideDecoder
import dev.mooma.kanade.core.glide.GlideLexicon
import dev.mooma.kanade.core.glide.KanaSpelling
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlideTest {
    private val kw = 100f
    private val kh = 150f

    /** QWERTY の文字のキーの中心（2段目は半キー、3段目は1キー半ずれる） */
    private val keys: Map<Char, Pair<Float, Float>> = buildMap {
        listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { row, (letters, offset) ->
            letters.forEachIndexed { i, c -> put(c, (offset + i + 0.5f) * kw to (row + 0.5f) * kh) }
        }
    }

    /** キーの中心を結んで、途中に点を足し、少し揺らした軌跡 */
    private fun gesture(word: String, noise: Float = 15f, seed: Int = 1): Pair<FloatArray, FloatArray> {
        val r = Random(seed)
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val path = GlideLexicon.collapse(word).map { keys.getValue(it) }
        for (i in path.indices) {
            if (i > 0) {
                val (x0, y0) = path[i - 1]
                val (x1, y1) = path[i]
                for (k in 1..9) {
                    val t = k / 10f
                    xs += x0 + (x1 - x0) * t + (r.nextFloat() - 0.5f) * 2 * noise
                    ys += y0 + (y1 - y0) * t + (r.nextFloat() - 0.5f) * 2 * noise
                }
            }
            xs += path[i].first + (r.nextFloat() - 0.5f) * 2 * noise
            ys += path[i].second + (r.nextFloat() - 0.5f) * 2 * noise
        }
        return xs.toFloatArray() to ys.toFloatArray()
    }

    private val english = GlideLexicon.english(
        """
        you 28787591
        i'm 4000000
        the 22761659
        hello 500000
        hell 300000
        help 600000
        world 400000
        word 350000
        don't 3000000
        dont 1000
        great 450000
        greet 30000
        """.trimIndent().lineSequence(),
    )

    private fun top(lexicon: GlideLexicon, word: String, seed: Int = 1): String {
        val (xs, ys) = gesture(word, seed = seed)
        return GlideDecoder(lexicon).decode(xs, ys, keys, kw).first().output
    }

    @Test
    fun englishWords() {
        for (seed in 1..5) {
            assertEquals("hello", top(english, "helo", seed))
            assertEquals("world", top(english, "world", seed))
            assertEquals("great", top(english, "great", seed))
            assertEquals("help", top(english, "help", seed))
        }
    }

    @Test
    fun englishOutputKeepsApostropheAndCapitalI() {
        assertEquals("don't", top(english, "dont"))
        assertEquals("I'm", top(english, "im"))
    }

    @Test
    fun spellings() {
        assertEquals(setOf("kanzi", "kanji"), KanaSpelling.spellings("かんじ").toSet())
        assertEquals(setOf("syasin", "shashin"), KanaSpelling.spellings("しゃしん").toSet())
        assertEquals(listOf("arigatou"), KanaSpelling.spellings("ありがとう"))
        // っ と ー はなぞれないので綴りに入れない
        assertEquals(listOf("kite"), KanaSpelling.spellings("きって"))
        assertEquals(listOf("kohi"), KanaSpelling.spellings("こーひー"))
    }

    private val japanese = GlideLexicon.japanese(
        """
        ありがとう	3000
        かんじ	4000
        かんじる	4500
        しゃしん	4200
        きょう	3500
        きよう	6000
        """.trimIndent().lineSequence(),
    )

    @Test
    fun japaneseWords() {
        assertEquals("ありがとう", top(japanese, "arigatou"))
        // ヘボン式でも訓令式でも同じ語になる
        assertEquals("かんじ", top(japanese, "kanji"))
        assertEquals("かんじ", top(japanese, "kanzi"))
        assertEquals("しゃしん", top(japanese, "shashin"))
        assertEquals("しゃしん", top(japanese, "syasin"))
        // 同じ形（kyou と kiyou は形が近い）なら、よく使う方が先
        val (xs, ys) = gesture("kyou")
        val result = GlideDecoder(japanese).decode(xs, ys, keys, kw)
        assertEquals("きょう", result.first().output)
        assertTrue(result.any { it.output == "きよう" })
    }

    @Test
    fun resampleKeepsEnds() {
        val ox = FloatArray(GlideDecoder.POINTS)
        val oy = FloatArray(GlideDecoder.POINTS)
        GlideDecoder.resample(floatArrayOf(0f, 100f, 100f), floatArrayOf(0f, 0f, 50f), 3, ox, oy)
        assertEquals(0f, ox.first())
        assertEquals(100f, ox.last())
        assertEquals(50f, oy.last(), 0.01f)
    }
}
