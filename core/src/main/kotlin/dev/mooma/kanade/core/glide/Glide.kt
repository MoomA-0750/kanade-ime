package dev.mooma.kanade.core.glide

import dev.mooma.kanade.core.RomajiTable
import kotlin.math.hypot
import kotlin.math.ln

/**
 * グライド入力（キーの上を指でなぞって語を入れる）の読み取り。
 *
 * 語ごとに「通るキーの並び」（同じ文字が続くところは1つにまとめる）を持ち、キーの中心を結んだ折れ線を
 * 指の軌跡と同じ点の数に並べ直して、対応する点どうしの平均の距離（キーの幅で割る）で形の合い方を測る。
 * これに語のよく使われる度合い（[GlideWord.prior]）を足して並べる（SHARK2 の位置の比べ方に近い）。
 * 画面に依らないよう、キーの位置は呼ぶ側（画面キーボード）が渡す。
 */
data class GlideWord(
    /** 通るキーの並び（a〜z、続く同じ文字は1つ） */
    val keys: String,
    /** 入れる文字（日本語ならかな、英語なら語） */
    val output: String,
    /** よく使われる度合い（自然対数の確率くらいの大きさ。0 に近いほどよく使う） */
    val prior: Float,
)

data class GlideCandidate(val output: String, val score: Float)

class GlideLexicon(words: List<GlideWord>) {
    private val words: List<GlideWord>
    /** 最初と最後のキーごとの語（[bucket]） */
    private val index: Map<Int, IntArray>

    init {
        // 同じ並び・同じ文字は、よく使う方だけ残す
        val best = HashMap<Pair<String, String>, GlideWord>()
        for (w in words) {
            if (w.keys.length < 2 || w.keys.any { it !in 'a'..'z' }) continue
            val k = w.keys to w.output
            val old = best[k]
            if (old == null || old.prior < w.prior) best[k] = w
        }
        this.words = best.values.toList()
        index = this.words.indices.groupBy { bucket(this.words[it].keys.first(), this.words[it].keys.last()) }
            .mapValues { it.value.toIntArray() }
    }

    val size: Int get() = words.size

    internal fun wordsBetween(first: Char, last: Char): Sequence<GlideWord> =
        index[bucket(first, last)]?.asSequence()?.map { words[it] } ?: emptySequence()

    private fun bucket(first: Char, last: Char) = (first - 'a') * 26 + (last - 'a')

    companion object {
        /** 綴りの続く同じ文字を1つにする（グライドでは同じキーを2回通れない） */
        fun collapse(s: String): String {
            val b = StringBuilder()
            for (c in s) if (b.isEmpty() || b.last() != c) b.append(c)
            return b.toString()
        }

        /**
         * 日本語: 1行に「読み（ひらがな）<TAB>コスト」（Mozc のコスト。小さいほどよく使う）。
         * 読みをローマ字の綴り（ヘボン式・訓令式の2通りまで）にして、どちらでなぞっても出るようにする
         */
        fun japanese(lines: Sequence<String>): GlideLexicon {
            val words = ArrayList<GlideWord>()
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val kana = line.substring(0, tab)
                val cost = line.substring(tab + 1).trim().toIntOrNull() ?: continue
                for (spelling in KanaSpelling.spellings(kana)) {
                    words += GlideWord(collapse(spelling), kana, -cost / MOZC_COST_PER_NAT)
                }
            }
            return GlideLexicon(words)
        }

        /** 英語: 1行に「語 回数」か「語<TAB>回数」。'（don't など）は入れる文字には残し、なぞるキーからは外す */
        fun english(lines: Sequence<String>): GlideLexicon {
            val parsed = lines.mapNotNull { line ->
                val parts = line.trim().split(' ', '\t')
                if (parts.size < 2) return@mapNotNull null
                val word = parts[0]
                val count = parts[1].toLongOrNull() ?: return@mapNotNull null
                if (word.isEmpty() || word.any { it !in 'a'..'z' && it != '\'' }) return@mapNotNull null
                word to count
            }.toList()
            val max = parsed.maxOfOrNull { it.second }?.toDouble() ?: return GlideLexicon(emptyList())
            return GlideLexicon(parsed.map { (word, count) ->
                GlideWord(collapse(word.filter { it != '\'' }), englishOutput(word), ln(count / max).toFloat())
            })
        }

        /** 「i」「i'm」などは大文字にする */
        private fun englishOutput(word: String): String =
            if (word == "i" || word.startsWith("i'")) "I" + word.substring(1) else word

        /** Mozc のコストを自然対数の確率くらいに直すときの割る数（dictool の eval-glide で、形がはっきりしているのに頻度で負けないよう選んだ） */
        private const val MOZC_COST_PER_NAT = 1000f
    }
}

/** かなの読みを、なぞるときのローマ字の綴りにする */
object KanaSpelling {
    /** ヘボン式に特有の綴り（sh・ch・ts・j・f）か。訓令式を1つ目、ヘボン式を2つ目にそろえるため */
    private fun hepburn(romaji: String) =
        "sh" in romaji || "ch" in romaji || "ts" in romaji || 'j' in romaji || romaji.startsWith("f")

    /** かな（1〜2文字）ごとの綴り（訓令式・ヘボン式の順に最大2通り。x・l・c・q で始まる綴りは、ほかが無いときだけ） */
    private val table: Map<String, List<String>> by lazy {
        val byKana = HashMap<String, MutableList<String>>()
        for ((romaji, kana) in RomajiTable.entries) byKana.getOrPut(kana) { ArrayList() } += romaji
        byKana.mapValues { (_, list) ->
            val plain = list.filter { it.first() !in "xlcq" && it.all { c -> c in 'a'..'z' } }
            val sorted = plain.ifEmpty { list.filter { it.all { c -> c in 'a'..'z' } } }.sortedWith(compareBy({ it.length }, { it }))
            // 訓令式（など）の綴りと、ヘボン式の綴りを1つずつ（「う」の wu のような別の綴りは入れない）
            val kunrei = sorted.firstOrNull { !hepburn(it) }
            val hep = sorted.firstOrNull { hepburn(it) }
            listOfNotNull(kunrei ?: hep, hep.takeIf { kunrei != null })
        }.filterValues { it.isNotEmpty() } + mapOf("ん" to listOf("n"), "っ" to listOf(""), "ー" to listOf(""))
    }

    /**
     * 綴り（最大2通り: どこも1つ目の綴り、どこも2つ目の綴り（無ければ1つ目））。表に無い文字があれば空。
     * 「っ」「ー」はなぞれないので綴りに入れない（同じ文字が続くのも1つにまとめるため、っ の重ねは表せない）
     */
    fun spellings(kana: String): List<String> {
        val first = StringBuilder()
        val second = StringBuilder()
        var i = 0
        while (i < kana.length) {
            // 拗音など2文字のかなを先に見る
            val two = if (i + 1 < kana.length) table[kana.substring(i, i + 2)] else null
            val options = two ?: table[kana.substring(i, i + 1)] ?: return emptyList()
            first.append(options[0])
            second.append(options.getOrElse(1) { options[0] })
            i += if (two != null) 2 else 1
        }
        return listOf(first.toString(), second.toString()).distinct().filter { it.isNotEmpty() }
    }
}

/** 指の軌跡から語を選ぶ */
class GlideDecoder(private val lexicon: GlideLexicon) {
    /**
     * [xs]・[ys] は指の軌跡（画面の座標）、[keys] は文字のキーの中心、[keyWidth] はキーの幅（同じ座標）。
     * 合う順に最大 [limit] 個
     */
    fun decode(xs: FloatArray, ys: FloatArray, keys: Map<Char, Pair<Float, Float>>, keyWidth: Float, limit: Int = 6): List<GlideCandidate> {
        if (xs.size < 2 || keys.isEmpty()) return emptyList()
        val gx = FloatArray(POINTS)
        val gy = FloatArray(POINTS)
        resample(xs, ys, xs.size, gx, gy)
        val gestureLength = length(xs, ys, xs.size)

        val starts = nearKeys(xs[0], ys[0], keys, keyWidth)
        val ends = nearKeys(xs.last(), ys.last(), keys, keyWidth)

        val tx = FloatArray(MAX_KEYS)
        val ty = FloatArray(MAX_KEYS)
        val rx = FloatArray(POINTS)
        val ry = FloatArray(POINTS)
        val best = HashMap<String, Float>()
        for (s in starts) for (e in ends) {
            for (w in lexicon.wordsBetween(s, e)) {
                val n = w.keys.length
                if (n > MAX_KEYS) continue
                var ok = true
                for (i in 0 until n) {
                    val p = keys[w.keys[i]]
                    if (p == null) {
                        ok = false
                        break
                    }
                    tx[i] = p.first
                    ty[i] = p.second
                }
                if (!ok) continue
                // 長さがかけ離れていれば比べない
                val templateLength = length(tx, ty, n)
                if (kotlin.math.abs(templateLength - gestureLength) > LENGTH_SLACK * maxOf(templateLength, keyWidth) + keyWidth) continue
                resample(tx, ty, n, rx, ry)
                var sum = 0f
                for (i in 0 until POINTS) sum += hypot(rx[i] - gx[i], ry[i] - gy[i])
                val d = sum / POINTS / keyWidth
                // 始めと終わりのキーは、なぞる人がいちばん狙うところなので重く見る
                val ds = hypot(tx[0] - xs[0], ty[0] - ys[0]) / keyWidth
                val de = hypot(tx[n - 1] - xs.last(), ty[n - 1] - ys.last()) / keyWidth
                // 語の途中のキーは、軌跡がその近くを通っているはず（通っていなければ別の語）
                var pass = 0f
                for (i in 1 until n - 1) {
                    var m = Float.MAX_VALUE
                    for (k in 0 until POINTS) m = minOf(m, hypot(tx[i] - gx[k], ty[i] - gy[k]))
                    val mk = m / keyWidth
                    pass += mk * mk
                }
                val score = -(d * d) / (2 * SIGMA * SIGMA) - (ds * ds + de * de) / (2 * END_SIGMA * END_SIGMA) -
                    pass / (2 * KEY_SIGMA * KEY_SIGMA) + PRIOR_WEIGHT * w.prior
                val old = best[w.output]
                if (old == null || old < score) best[w.output] = score
            }
        }
        return best.entries.sortedByDescending { it.value }.take(limit).map { GlideCandidate(it.key, it.value) }
    }

    /** 点の近くのキー（中心がキーの幅以内。無ければいちばん近いもの） */
    private fun nearKeys(x: Float, y: Float, keys: Map<Char, Pair<Float, Float>>, keyWidth: Float): List<Char> {
        val near = keys.filter { (_, p) -> hypot(p.first - x, p.second - y) <= keyWidth * NEAR_KEY }.keys
        return near.toList().ifEmpty { listOf(keys.minBy { (_, p) -> hypot(p.first - x, p.second - y) }.key) }
    }

    companion object {
        /** 比べる点の数 */
        const val POINTS = 40
        /** 語のキーの数の上限 */
        const val MAX_KEYS = 32
        /** 形のずれの許し具合（キーの幅を 1 として） */
        const val SIGMA = 0.35f
        /** 始めと終わりの点のずれの許し具合（キーの幅を 1 として） */
        const val END_SIGMA = 0.5f
        /** 途中のキーと軌跡のずれの許し具合（キーの幅を 1 として） */
        const val KEY_SIGMA = 0.45f
        /** よく使われる度合いの効き具合 */
        const val PRIOR_WEIGHT = 0.6f
        /** 始めと終わりのキーとみなす距離（キーの幅を 1 として） */
        const val NEAR_KEY = 1.0f
        /** 軌跡と語の長さの違いの許し具合（割合） */
        const val LENGTH_SLACK = 0.6f

        fun length(xs: FloatArray, ys: FloatArray, n: Int): Float {
            var l = 0f
            for (i in 1 until n) l += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            return l
        }

        /** 折れ線を、長さに沿って等間隔の [POINTS] 個の点に並べ直す（点が1つなら同じ点を並べる） */
        fun resample(xs: FloatArray, ys: FloatArray, n: Int, outX: FloatArray, outY: FloatArray) {
            val total = length(xs, ys, n)
            if (n == 1 || total == 0f) {
                outX.fill(xs[0])
                outY.fill(ys[0])
                return
            }
            val step = total / (outX.size - 1)
            var seg = 1
            var segStart = 0f
            for (k in outX.indices) {
                val target = minOf(k * step, total)
                while (seg < n - 1) {
                    val segLen = hypot(xs[seg] - xs[seg - 1], ys[seg] - ys[seg - 1])
                    if (segStart + segLen >= target) break
                    segStart += segLen
                    seg++
                }
                val segLen = hypot(xs[seg] - xs[seg - 1], ys[seg] - ys[seg - 1])
                val t = if (segLen == 0f) 0f else ((target - segStart) / segLen).coerceIn(0f, 1f)
                outX[k] = xs[seg - 1] + (xs[seg] - xs[seg - 1]) * t
                outY[k] = ys[seg - 1] + (ys[seg] - ys[seg - 1]) * t
            }
        }
    }
}
