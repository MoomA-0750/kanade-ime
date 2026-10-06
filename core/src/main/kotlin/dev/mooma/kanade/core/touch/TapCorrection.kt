package dev.mooma.kanade.core.touch

import kotlin.math.ln

/**
 * 次に来る文字の出やすさ（文字の n-gram）。語の一覧（読みのローマ字・英単語・かなの読み）から、よく使う語ほど重く数える。
 * [START] は語の始まりの印（英語の語の頭、かなの読みの頭）
 */
class NextCharModel(private val order: Int = 4) {
    /** 前の文字列（長さ 0〜order-1）→ 次の文字ごとの重み */
    private val counts = HashMap<String, HashMap<Char, Float>>()
    private val totals = HashMap<String, Float>()
    private val alphabet = HashSet<Char>()

    fun add(word: String, weight: Float) {
        if (word.isEmpty() || weight <= 0f) return
        val s = START + word
        for (i in 1 until s.length) {
            val c = s[i]
            alphabet += c
            for (k in 0 until order) {
                if (i - k < 0) break
                val ctx = s.substring(i - k, i)
                counts.getOrPut(ctx) { HashMap() }.merge(c, weight, Float::plus)
                totals.merge(ctx, weight, Float::plus)
            }
        }
    }

    /**
     * [context] のあとに [c] が来る確率。長い前の文字列ほど重く見て、短いものと混ぜる（見たことのない並びでも 0 にしない）
     */
    fun prob(context: String, c: Char): Float {
        var p = 1f / maxOf(1, alphabet.size) * FLOOR
        var weightSum = FLOOR
        for (k in 0 until order) {
            if (k > context.length) break
            val ctx = context.substring(context.length - k)
            val total = totals[ctx] ?: continue
            val w = LAMBDA[k]
            p += w * ((counts[ctx]?.get(c) ?: 0f) / total)
            weightSum += w
        }
        return p / weightSum
    }

    val size: Int get() = counts.size

    companion object {
        const val START = '^'
        /** 前の文字列の長さごとの重み（0: なし、1: 1文字前…） */
        private val LAMBDA = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.4f, 0.4f)
        /** どの文字にも少しは残す */
        private const val FLOOR = 0.02f
    }
}

/**
 * タップの位置がずれたときの補い（Apple のキーボードが見えないところでキーの当たりを広げ縮めするのと同じ考え方）。
 * 触ったキーと近くのキーを、キーの中心からの近さと「入力中の語のあとに来やすいか」で比べる。
 * キーの真ん中あたりを触ったときは、必ず触ったキーにする
 */
object TapCorrection {
    /** 候補のキー。[distance] は触った点とキーの中心の距離（キーの大きさを 1 とした大きさ） */
    data class Option(val char: Char, val distance: Float)

    /** [options] の最初は触ったキー。選んだものの位置を返す */
    fun choose(
        options: List<Option>,
        context: String,
        model: NextCharModel,
        sigma: Float = SIGMA,
        weight: Float = WEIGHT,
        keepRadius: Float = KEEP_RADIUS,
    ): Int {
        if (options.size < 2 || options[0].distance <= keepRadius) return 0
        var best = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for ((i, o) in options.withIndex()) {
            // 来にくい文字の点の下がり方には下限を付け、来やすさだけでキーを大きく動かさないようにする
            val prior = ln(maxOf(model.prob(context, o.char), MIN_PROB))
            val score = -(o.distance * o.distance) / (2 * sigma * sigma) + weight * prior
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    /** キーの中心からのずれの許し具合 */
    const val SIGMA = 0.35f
    /** 「来やすさ」の効き具合 */
    const val WEIGHT = 0.7f
    /** 来やすさの下限（これより来にくい文字も、これだけはあるとみなす） */
    const val MIN_PROB = 0.02f
    /** これより中心に近ければ、触ったキーのまま */
    const val KEEP_RADIUS = 0.3f
}

/** グライドの語の一覧（assets の glide-ja.tsv・glide-en.txt）から、次の文字の出やすさを作る */
object TapModels {
    /** 日本語（ローマ字で打つとき）: 読みを打つときのローマ字にして、よく使う読みほど重く数える */
    fun japaneseRomaji(lines: Sequence<String>): NextCharModel = NextCharModel().apply {
        for ((kana, weight) in japaneseEntries(lines)) {
            for (s in dev.mooma.kanade.core.glide.KanaSpelling.typingSpellings(kana)) add(s, weight)
        }
    }

    /** 日本語（テンキーでかなを打つとき）: 読みのかなそのもの */
    fun japaneseKana(lines: Sequence<String>): NextCharModel = NextCharModel(order = 3).apply {
        for ((kana, weight) in japaneseEntries(lines)) add(kana, weight)
    }

    /** 英語: 語の回数の平方根で数える（よく使う語に寄りすぎないように） */
    fun english(lines: Sequence<String>): NextCharModel = NextCharModel().apply {
        val words = lines.mapNotNull { line ->
            val parts = line.trim().split(' ', '\t')
            val count = parts.getOrNull(1)?.toDoubleOrNull() ?: return@mapNotNull null
            parts[0].lowercase().filter { it in 'a'..'z' }.takeIf { it.isNotEmpty() }?.let { it to count }
        }.toList()
        val max = words.maxOfOrNull { it.second } ?: return@apply
        for ((w, c) in words) add(w, kotlin.math.sqrt(c / max).toFloat())
    }

    /** glide-ja.tsv の「読み<TAB>コスト」を、読みと重み（コストが小さいほど重い）にする */
    private fun japaneseEntries(lines: Sequence<String>): Sequence<Pair<String, Float>> = lines.mapNotNull { line ->
        val tab = line.indexOf('\t')
        if (tab <= 0) return@mapNotNull null
        val cost = line.substring(tab + 1).trim().toIntOrNull() ?: return@mapNotNull null
        line.substring(0, tab) to kotlin.math.exp(-cost / 1000.0).toFloat()
    }
}
