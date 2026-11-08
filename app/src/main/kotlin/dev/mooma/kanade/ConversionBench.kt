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
        appendLine(context.getString(R.string.app_bench_dictionary, ms(t), dict.take(5).joinToString(" / ")))
        val zenz = ConversionResources.zenz(context)
        if (zenz == null) {
            appendLine(context.getString(R.string.app_bench_zenz_load_failed))
            return@buildString
        }
        t = System.nanoTime()
        val gen = zenz.generate("", reading)
        appendLine(context.getString(R.string.app_bench_zenz_generate, ms(t), gen.toString()))
        t = System.nanoTime()
        val ranked = Reranker(zenz).rerank("", reading, dict)?.candidates
        appendLine(context.getString(R.string.app_bench_zenz_rerank, ms(t), ranked?.take(5)?.joinToString(" / ").toString()))
    }

    /** 同梱の評価セット（eval/daily.tsv・daily2.tsv）で、1位の正解率と1回あたりの時間を測る */
    fun runEval(context: Context, progress: (String) -> Unit): String {
        // 同梱の評価セット2つ（日常文 102 件、同音異義語など 100 件）
        val cases = listOf("eval-daily.tsv", "eval-daily2.tsv").flatMap { name ->
            context.assets.open(name).bufferedReader().readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split('\t').let { f -> f[0] to f[1].split('|') } }
        }
        val converter = ConversionResources.converter(context)
        val zenz = ConversionResources.zenz(context) ?: return context.getString(R.string.app_bench_zenz_load_failed)
        val reranker = Reranker(zenz)
        // 最初の数回は読み込みや JIT の分遅いので外す
        repeat(3) { converter.convert(cases[it].first, 10); zenz.generate("", cases[it].first) }

        val hits = IntArray(4)
        val times = List(5) { ArrayList<Double>() }
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
            t = System.nanoTime()
            val corrected = dict.firstOrNull()?.let { first ->
                reranker.correct("", reading, first, Reranker.CORRECT_LIMIT) { prefix -> converter.convertWithPrefix(reading, prefix)?.text }
            }
            times[4] += elapsed(t)
            if (dict.firstOrNull() in answers) hits[0]++
            if (ranked.firstOrNull() in answers) hits[1]++
            if (both.firstOrNull() in answers) hits[2]++
            if (corrected in answers) hits[3]++
            if (i % 10 == 0) progress(context.getString(R.string.app_bench_progress, i, cases.size))
        }
        fun pct(n: Int) = context.getString(R.string.app_bench_percent, n, cases.size, "%.1f".format(100.0 * n / cases.size))
        fun stat(l: List<Double>) = l.sorted().let { context.getString(R.string.app_bench_stat, "%.1f".format(it[it.size / 2]), "%.1f".format(it[it.size * 95 / 100])) }
        // スレッド数ごとの並べ替えの時間（最初の 60 件）。端末が熱を持って遅くなる分が偏らないよう、文ごとに全部の数を順に回す
        val threadTimes = THREAD_COUNTS.associateWith { ArrayList<Double>() }
        for ((i, case) in cases.take(60).withIndex()) {
            val dict = converter.convert(case.first, 10).map { it.text }
            val order = THREAD_COUNTS.indices.map { THREAD_COUNTS[(it + i) % THREAD_COUNTS.size] }
            for (n in order) {
                zenz.setThreads(n)
                val t = System.nanoTime()
                reranker.rerank("", case.first, dict)
                threadTimes.getValue(n) += elapsed(t)
            }
        }
        val configured = KanadeSettings(context).zenzThreads
        zenz.setThreads(configured)
        return buildString {
            appendLine(context.getString(R.string.app_bench_eval_header, cases.size, configured))
            appendLine(context.getString(R.string.app_bench_eval_dictionary_only, pct(hits[0]), stat(times[0])))
            appendLine(context.getString(R.string.app_bench_eval_zenz_generate, stat(times[1])))
            appendLine(context.getString(R.string.app_bench_eval_zenz_rerank, pct(hits[1]), stat(times[2])))
            appendLine(context.getString(R.string.app_bench_eval_rerank_and_generate, pct(hits[2]), stat(times[3])))
            appendLine(context.getString(R.string.app_bench_eval_zenz_correct, pct(hits[3]), stat(times[4])))
            for ((n, l) in threadTimes.entries.map { it.key to it.value }) appendLine(context.getString(R.string.app_bench_eval_threads, n, stat(l)))
        }.also { android.util.Log.i("KanadeBench", it) }
    }

    /** 並べ替えの時間を測るスレッド数 */
    private val THREAD_COUNTS = listOf(2, 3, 4, 6, 8)

    private fun elapsed(start: Long) = (System.nanoTime() - start) / 1e6
    private fun ms(start: Long) = "%.1fms".format(elapsed(start))
}
