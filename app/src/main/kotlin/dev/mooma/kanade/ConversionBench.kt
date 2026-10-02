package dev.mooma.kanade

import android.content.Context
import dev.mooma.kanade.core.convert.Reranker

/**
 * 端末上での変換の試験と計測。段階2で zenz の実速度を Fold 7 で測るためのもの。
 * 結果は設定画面にそのまま文字で出す（UI スレッドから呼ばないこと）。
 */
object ConversionBench {
    fun tryConvert(context: Context, reading: String): String = buildString {
        val converter = ConversionResources.converter(context)
        var t = System.nanoTime()
        val dict = converter.convert(reading, 10).map { it.text }
        appendLine("辞書（${ms(t)}）: ${dict.take(5).joinToString(" / ")}")
        val zenz = ConversionResources.zenz(context)
        if (zenz == null) {
            appendLine("zenz を読み込めなかった")
            return@buildString
        }
        t = System.nanoTime()
        val gen = zenz.generate("", reading)
        appendLine("zenz 生成（${ms(t)}）: $gen")
        t = System.nanoTime()
        val ranked = Reranker(zenz).rerank("", reading, dict)?.candidates
        appendLine("zenz 並べ替え 10件（${ms(t)}）: ${ranked?.take(5)?.joinToString(" / ")}")
    }

    /** 同梱の評価セット（eval/daily.tsv）で、1位の正解率と1回あたりの時間を測る */
    fun runEval(context: Context, progress: (String) -> Unit): String {
        val cases = context.assets.open("eval-daily.tsv").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t').let { f -> f[0] to f[1].split('|') } }
        val converter = ConversionResources.converter(context)
        val zenz = ConversionResources.zenz(context) ?: return "zenz を読み込めなかった"
        val reranker = Reranker(zenz)
        // 最初の数回は読み込みや JIT の分遅いので外す
        repeat(3) { converter.convert(cases[it].first, 10); zenz.generate("", cases[it].first) }

        val hits = IntArray(3)
        val times = List(4) { ArrayList<Double>() }
        for ((i, case) in cases.withIndex()) {
            val (reading, answers) = case
            var t = System.nanoTime()
            val dict = converter.convert(reading, 10).map { it.text }
            times[0] += elapsed(t)
            t = System.nanoTime()
            val gen = zenz.generate("", reading)
            times[1] += elapsed(t)
            t = System.nanoTime()
            val ranked = reranker.rerank("", reading, dict)?.candidates.orEmpty()
            times[2] += elapsed(t)
            t = System.nanoTime()
            val both = reranker.rerank("", reading, dict, useGeneration = true)?.candidates.orEmpty()
            times[3] += elapsed(t)
            if (dict.firstOrNull() in answers) hits[0]++
            if (ranked.firstOrNull() in answers) hits[1]++
            if (both.firstOrNull() in answers) hits[2]++
            if (i % 10 == 0) progress("計測中 $i/${cases.size}")
        }
        fun pct(n: Int) = "$n/${cases.size}（${"%.1f".format(100.0 * n / cases.size)}%）"
        fun stat(l: List<Double>) = l.sorted().let { "中央値 ${"%.1f".format(it[it.size / 2])}ms・95% ${"%.1f".format(it[it.size * 95 / 100])}ms" }
        return buildString {
            appendLine("評価セット ${cases.size}件、zenz ${if (ConversionResources.loadedSmall) "small" else "xsmall"}、スレッド ${Runtime.getRuntime().availableProcessors().coerceIn(1, 4)}")
            appendLine("辞書だけ: ${pct(hits[0])}、${stat(times[0])}")
            appendLine("zenz 生成: ${stat(times[1])}")
            appendLine("zenz 並べ替え: ${pct(hits[1])}、${stat(times[2])}")
            appendLine("並べ替え＋生成: ${pct(hits[2])}、${stat(times[3])}")
        }
    }

    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1e6
    private fun ms(start: Long) = "%.1fms".format(elapsed(start))
}
