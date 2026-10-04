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

/** [score] は形・よく使う度合い・学習を合わせた点（大きいほど合う）。[prior] はそのうちのよく使う度合い */
data class GlideCandidate(val output: String, val score: Float, val prior: Float = 0f)

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

        /**
         * 日本語の語の、文脈の中でのよく使う度合い: 入力中の読み [context] のあとに [word] をつなげて変換したとき、
         * いちばん良い変換のコストがどれだけ増えるか（[cost] は読みを変換したときのコスト。分からなければ null）
         */
        fun japanesePrior(context: String, word: String, cost: (String) -> Int?): Float? {
            val base = if (context.isEmpty()) 0 else cost(context) ?: return null
            val total = cost(context + word) ?: return null
            return -(total - base) / MOZC_COST_PER_NAT
        }

        /** 「i」「i'm」などは大文字にする */
        private fun englishOutput(word: String): String =
            if (word == "i" || word.startsWith("i'")) "I" + word.substring(1) else word

        /** 変換のコストを自然対数の確率くらいに直すときの割る数（dictool の eval-glide で選んだ） */
        const val MOZC_COST_PER_NAT = 1000f
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

/** 読み取りの重み（どれもキーの幅を 1 とした大きさ。dictool の eval-glide で選ぶ） */
data class GlideParams(
    /** 形のずれの許し具合 */
    val sigma: Float = 0.35f,
    /** 始めと終わりの点のずれの許し具合 */
    val endSigma: Float = 0.5f,
    /** 途中のキーと軌跡のずれの許し具合 */
    val keySigma: Float = 0.45f,
    /** よく使われる度合いの効き具合 */
    val priorWeight: Float = 0.6f,
    /**
     * 上位の語を、軌跡と語の線を伸び縮みさせて合わせる比べ方（DTW）で比べ直すときの、ずれの許し具合。
     * 0 以下なら比べ直さない。角を丸めてなぞったり、速さが途中で変わったりしても形が合う
     */
    val dtwSigma: Float = 0f,
    /** DTW で比べ直す上位の語の数 */
    val dtwTop: Int = 60,
) {
    companion object {
        /**
         * dictool の tune-glide で、人の指に近い軌跡（狙いのずれ 0.2・角の丸め）に合わせて選んだ重み（2026-10-04）。
         * 途中のキーを通るかは、角を丸めてなぞると外れやすいので、ほとんど見ない
         */
        val JAPANESE = GlideParams(sigma = 0.13f, endSigma = 0.35f, keySigma = 3f, priorWeight = 1.0f, dtwSigma = 0.2f)
        val ENGLISH = GlideParams(sigma = 0.22f, endSigma = 0.3f, keySigma = 6f, priorWeight = 0.36f, dtwSigma = 0.15f)
    }
}

/** 指の軌跡から語を選ぶ */
class GlideDecoder(private val lexicon: GlideLexicon, val params: GlideParams = GlideParams()) {
    /**
     * [xs]・[ys] は指の軌跡（画面の座標）、[keys] は文字のキーの中心、[keyWidth] はキーの幅（同じ座標）。
     * 合う順に最大 [limit] 個
     */
    fun decode(
        xs: FloatArray,
        ys: FloatArray,
        keys: Map<Char, Pair<Float, Float>>,
        keyWidth: Float,
        limit: Int = 6,
        /** 語ごとに足す点（使う人が選んできた語を先に出す。[GlideLearning.bonus]） */
        bonus: (String) -> Float = { 0f },
        /** キーの高さ。縦のずれはキーの高さを単位に測る（横長のキーでも、段の違いを横の1キー分と同じに見る） */
        keyHeight: Float = keyWidth,
    ): List<GlideCandidate> {
        if (xs.size < 2 || keys.isEmpty()) return emptyList()
        if (keyHeight != keyWidth) {
            // 縦を伸ばして、キーが正方形に見える座標にしてから比べる
            val k = keyWidth / keyHeight
            return decode(xs, FloatArray(ys.size) { ys[it] * k }, keys.mapValues { (_, p) -> p.first to p.second * k }, keyWidth, limit, bonus)
        }
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
        val best = HashMap<String, GlideCandidate>()
        /** 出力ごとの、いちばん合った語（DTW で比べ直すときに使う） */
        val bestWord = HashMap<String, GlideWord>()
        val p = params
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
                val score = -(d * d) / (2 * p.sigma * p.sigma) - (ds * ds + de * de) / (2 * p.endSigma * p.endSigma) -
                    pass / (2 * p.keySigma * p.keySigma) + p.priorWeight * w.prior + bonus(w.output)
                val old = best[w.output]
                if (old == null || old.score < score) {
                    best[w.output] = GlideCandidate(w.output, score, w.prior)
                    bestWord[w.output] = w
                }
            }
        }
        val ranked = best.values.sortedByDescending { it.score }
        if (p.dtwSigma <= 0f) return ranked.take(limit)
        // 上位の語を DTW で比べ直す
        return ranked.take(p.dtwTop).map { c ->
            val w = bestWord.getValue(c.output)
            val n = w.keys.length
            for (i in 0 until n) {
                val q = keys.getValue(w.keys[i])
                tx[i] = q.first
                ty[i] = q.second
            }
            resample(tx, ty, n, rx, ry)
            val d = dtw(gx, gy, rx, ry) / keyWidth
            val ds = hypot(tx[0] - xs[0], ty[0] - ys[0]) / keyWidth
            val de = hypot(tx[n - 1] - xs.last(), ty[n - 1] - ys.last()) / keyWidth
            val score = -(d * d) / (2 * p.dtwSigma * p.dtwSigma) - (ds * ds + de * de) / (2 * p.endSigma * p.endSigma) +
                p.priorWeight * c.prior + bonus(c.output)
            c.copy(score = score)
        }.sortedByDescending { it.score }.take(limit)
    }

    /** DTW の距離: 2つの点の並びを、順番を保ったまま伸び縮みさせて合わせたときの、対応する点どうしの距離の平均 */
    private fun dtw(ax: FloatArray, ay: FloatArray, bx: FloatArray, by: FloatArray): Float {
        val n = ax.size
        val inf = Float.MAX_VALUE / 4
        var prev = FloatArray(n) { inf }
        var cur = FloatArray(n)
        for (i in 0 until n) {
            cur.fill(inf)
            val lo = maxOf(0, i - DTW_BAND)
            val hi = minOf(n - 1, i + DTW_BAND)
            for (j in lo..hi) {
                val c = hypot(ax[i] - bx[j], ay[i] - by[j])
                val m = when {
                    i == 0 && j == 0 -> 0f
                    else -> minOf(if (i > 0) prev[j] else inf, if (j > 0) cur[j - 1] else inf, if (i > 0 && j > 0) prev[j - 1] else inf)
                }
                cur[j] = c + m
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[n - 1] / n
    }

    /** 点の近くのキー（中心がキーの幅以内。無ければいちばん近いもの） */
    private fun nearKeys(x: Float, y: Float, keys: Map<Char, Pair<Float, Float>>, keyWidth: Float): List<Char> {
        val near = keys.filter { (_, p) -> hypot(p.first - x, p.second - y) <= keyWidth * NEAR_KEY }.keys
        return near.toList().ifEmpty { listOf(keys.minBy { (_, p) -> hypot(p.first - x, p.second - y) }.key) }
    }

    /**
     * 前後のつながりで並べ直す。[prior] はその語の、文脈の中でのよく使う度合い（[GlideWord.prior] と同じ大きさ。分からなければ null でそのまま）。
     * 日本語では、入力中の読みのあとにつなげて変換したときのコストの増え方を使う（「〜が」のあとは「ある」が「あう」より自然、など）
     */
    fun rerank(candidates: List<GlideCandidate>, prior: (String) -> Float?): List<GlideCandidate> =
        candidates.map { c ->
            val p = prior(c.output) ?: return@map c
            c.copy(score = c.score + params.priorWeight * (p - c.prior), prior = p)
        }.sortedByDescending { it.score }

    companion object {
        /** 比べる点の数 */
        const val POINTS = 40
        /** DTW で合わせるときに、ずらしてよい点の数 */
        const val DTW_BAND = 8
        /** 語のキーの数の上限 */
        const val MAX_KEYS = 32
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

/**
 * 使う人がグライドで入れて残した語の回数。よく使う語ほど先に出す（[bonus]）。
 * 1行に「語<TAB>回数」で読み書きする
 */
class GlideLearning {
    private val counts = LinkedHashMap<String, Int>()

    @Synchronized
    fun record(word: String) {
        counts[word] = (counts.remove(word) ?: 0) + 1
        // 古いものから捨てる（最近使った語を後ろに置き直している）
        while (counts.size > MAX_WORDS) counts.remove(counts.keys.first())
    }

    /** 入れた直後に消した・選び直した語は、数えたのを取り消す */
    @Synchronized
    fun forget(word: String) {
        val c = counts[word] ?: return
        if (c <= 1) counts.remove(word) else counts[word] = c - 1
    }

    @Synchronized
    fun bonus(word: String): Float = counts[word]?.let { LEARN_WEIGHT * ln(1f + it) } ?: 0f

    @Synchronized
    fun write(): String = counts.entries.joinToString("") { "${it.key}\t${it.value}\n" }

    companion object {
        const val MAX_WORDS = 3000
        /** 1回選んだ語に足す点の大きさ（2回で約 1.6 倍、10回で約 3.4 倍） */
        const val LEARN_WEIGHT = 1.5f

        fun parse(text: String): GlideLearning = GlideLearning().apply {
            for (line in text.lineSequence()) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val n = line.substring(tab + 1).toIntOrNull() ?: continue
                counts[line.substring(0, tab)] = n
            }
        }
    }
}
