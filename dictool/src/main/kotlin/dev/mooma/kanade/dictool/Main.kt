package dev.mooma.kanade.dictool

import dev.mooma.kanade.core.convert.KanaKanjiConverter
import dev.mooma.kanade.core.convert.Reranker
import dev.mooma.kanade.zenz.ZenzModel
import dev.mooma.kanade.core.dict.ConnectionMatrix
import dev.mooma.kanade.core.dict.DictionaryBuilder
import dev.mooma.kanade.core.dict.PosTable
import dev.mooma.kanade.core.dict.SymbolTable
import dev.mooma.kanade.core.convert.Segmenter
import dev.mooma.kanade.core.dict.SystemDictionary
import java.io.File
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.system.exitProcess

/** Mozc OSS 辞書の取得元。版を固定する（上げるときはここを変え、評価をやり直す） */
const val MOZC_COMMIT = "a069a88d4cb5c011de0f9aebb6c149a1c808d904"
/** 辞書の生成物の版（2: pos.kpos、3: symbol.ksym） */
const val BUILD_REVISION = 3

/** 辞書以外に取る Mozc のデータ（記号・絵文字）。src/data からのパス */
val MOZC_EXTRA_FILES = listOf("symbol/symbol.tsv", "emoji/emoji_data.tsv")

/** zenz のモデル。リビジョンとハッシュを固定する */
const val ZENZ_URL = "https://huggingface.co/Miwa-Keita/zenz-v3.2-xsmall-gguf/resolve/4f5423f0fad41a73b1242eb96fe5c12ae4fdca83/ggml-model-Q5_K_M.gguf"
const val ZENZ_FILE = "zenz-v3.2-xsmall-Q5_K_M.gguf"
const val ZENZ_SHA256 = "00c64b3d318045a708d0cad5434faccab10f5481a49e6362864551fd0995fa58"
val MOZC_FILES = (0..9).map { "dictionary0$it.txt" } + listOf("connection_single_column.txt", "id.def", "evaluation.tsv", "README.txt")

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "fetch" -> fetch(File(args[1]))
        "fetch-model" -> fetchModel(File(args[1]))
        "assets" -> assets(File(args[1]), File(args[2]))
        "build" -> build(File(args[1]), File(args[2]))
        "eval" -> evaluate(File(args[1]), File(args[2]))
        "convert" -> convert(File(args[1]), args.drop(2))
        "zenz" -> zenzTry(File(args[1]), File(args[2]), File(args[3]), args.drop(4))
        "eval-zenz" -> evaluateZenz(File(args[1]), File(args[2]), File(args[3]), File(args[4]), args.getOrNull(5)?.toInt() ?: 10)
        else -> {
            System.err.println(
                """
                使い方:
                  fetch <cacheDir>              Mozc の辞書を取得する（既にあれば何もしない）
                  fetch-model <cacheDir>        zenz のモデルを取得する（既にあれば何もしない）
                  assets <cacheDir> <outDir>    APK に入れる辞書・モデル・ライセンスを outDir にそろえる（Gradle から呼ぶ）
                  build <mozcDir> <outDir>      system.kdic と connection.kcon を書く
                  eval <outDir> <evaluation.tsv> Mozc の評価セットで1位一致率などを測る
                  convert <outDir> <かな...>     変換を試す
                  zenz <outDir> <gguf> <libkanade_zenz.so> <かな...>        zenz の並べ替えと生成を試す
                  eval-zenz <outDir> <tsv> <gguf> <libkanade_zenz.so> [topK] zenz ありで評価する
                """.trimIndent()
            )
            exitProcess(2)
        }
    }
}

fun fetch(cacheDir: File) {
    val dir = File(cacheDir, "mozc-$MOZC_COMMIT")
    dir.mkdirs()
    for (name in MOZC_FILES) {
        val f = File(dir, name)
        if (f.exists() && f.length() > 0) continue
        val url = "https://raw.githubusercontent.com/google/mozc/$MOZC_COMMIT/src/data/dictionary_oss/$name"
        println("取得: $url")
        val tmp = File(dir, "$name.part")
        URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(f)
    }
    for (path in MOZC_EXTRA_FILES) {
        val f = File(dir, path.substringAfter('/'))
        if (f.exists() && f.length() > 0) continue
        val url = "https://raw.githubusercontent.com/google/mozc/$MOZC_COMMIT/src/data/$path"
        println("取得: $url")
        val tmp = File(dir, "${f.name}.part")
        URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        tmp.renameTo(f)
    }
    println(dir.path)
}

fun fetchModel(cacheDir: File): File {
    val dir = File(cacheDir, "models").apply { mkdirs() }
    val f = File(dir, ZENZ_FILE)
    if (!f.exists() || sha256(f) != ZENZ_SHA256) {
        println("取得: $ZENZ_URL")
        val tmp = File(dir, "$ZENZ_FILE.part")
        URI(ZENZ_URL).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(sha256(tmp) == ZENZ_SHA256) { "zenz のハッシュが合わない" }
        tmp.renameTo(f)
    }
    return f
}

fun sha256(f: File): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** APK の assets に入れるものをそろえる。取得と辞書の変換は、無いときだけ行う */
fun assets(cacheDir: File, outDir: File) {
    fetch(cacheDir)
    val mozcDir = File(cacheDir, "mozc-$MOZC_COMMIT")
    // 生成物の中身を変えたら BUILD_REVISION を上げる（古い生成物を使わないように）
    val built = File(cacheDir, "built-$MOZC_COMMIT-v${SystemDictionary.VERSION}-r$BUILD_REVISION")
    // ライセンス文は build の最後に書くので、これがあれば変換は済んでいる
    if (!File(built, "mozc-dictionary-license.txt").exists()) build(mozcDir, built)
    val model = fetchModel(cacheDir)
    outDir.mkdirs()
    for (name in listOf("system.kdic", "connection.kcon", "pos.kpos", "symbol.ksym", "mozc-dictionary-license.txt")) {
        File(built, name).copyTo(File(outDir, name), overwrite = true)
    }
    model.copyTo(File(outDir, "zenz.gguf"), overwrite = true)
}

fun build(mozcDir: File, outDir: File) {
    outDir.mkdirs()
    val words = (0..9).flatMap { i ->
        File(mozcDir, "dictionary0$i.txt").readLines().mapNotNull(DictionaryBuilder::parseMozcLine)
    }
    File(outDir, "system.kdic").outputStream().use { DictionaryBuilder.write(words, it) }
    File(mozcDir, "connection_single_column.txt").useLines { lines ->
        File(outDir, "connection.kcon").outputStream().use { ConnectionMatrix.writeFromMozc(lines, it) }
    }
    // 辞書の出典とライセンス（IPAdic・ICOT の表示義務）をアプリに同梱する
    val symbols = SymbolTable.fromMozc(File(mozcDir, "symbol.tsv").readLines(), File(mozcDir, "emoji_data.tsv").readLines())
    File(outDir, "symbol.ksym").writeText(symbols.write())
    File(outDir, "pos.kpos").outputStream().use { PosTable.fromMozcIdDef(File(mozcDir, "id.def").readLines()).write(it) }
    File(mozcDir, "README.txt").copyTo(File(outDir, "mozc-dictionary-license.txt"), overwrite = true)
    println("語数 ${words.size}、${File(outDir, "system.kdic").length() / 1024 / 1024}MB")
}

/**
 * Mozc の evaluation.tsv の1行。意味は Mozc の converter/quality_regression_util.cc と同じにする:
 * - Conversion Expected [N]: 期待値が N 位以内（N 省略は1位）に出る
 * - Conversion Not Expected: 上の否定
 * - Conversion Match: 1位が5列目の語を含む / Conversion Not Match: 1位が5列目の語を含まない（誤変換の例）
 * Mozc は文節ごとの順位で数えるが、ここでは文全体の候補の順位で近似する。
 */
data class EvalCase(val label: String, val input: String, val value: String, val command: String, val rank: Int) {
    fun passes(candidates: List<String>): Boolean {
        val top = candidates.firstOrNull() ?: ""
        return when (command) {
            "Conversion Match" -> value in top
            "Conversion Not Match" -> value !in top
            "Conversion Expected" -> candidates.indexOf(value).let { it in 0..rank }
            "Conversion Not Expected" -> candidates.indexOf(value).let { it !in 0..rank }
            // eval/daily.tsv の形式。| で区切った許容解のどれかが1位
            "Any" -> top in value.split('|')
            else -> error(command)
        }
    }
}

fun loadCases(tsv: File): List<EvalCase> = tsv.readLines().filter { !it.startsWith("#") }.mapNotNull { line ->
    val f = line.split('\t').filter { it.isNotEmpty() }
    if (f.size == 2) return@mapNotNull EvalCase("OK", f[0], f[1], "Any", 0)
    if (f.size < 4 || !f[3].startsWith("Conversion")) return@mapNotNull null
    var command = f[3]
    var rank = 0
    // 「Conversion Expected 2」のように命令の後ろに順位が付く形と、5列目に順位がある形がある
    Regex("^(Conversion Expected) (\\d+)$").find(command)?.let {
        command = it.groupValues[1]
        rank = it.groupValues[2].toInt()
    }
    if (f.size > 4) f[4].toIntOrNull()?.let { rank = it }
    // Match / Not Match は5列目が「含む／含まない」部分文字列（例: 誤算、田中）。3列目は文全体の例
    val value = if (command.endsWith("Match") && f.size > 4) f[4] else f[2]
    EvalCase(f[0].removeSuffix(":"), f[1], value, command, rank)
}

fun loadConverter(outDir: File): KanaKanjiConverter {
    fun map(name: String) = FileChannel.open(File(outDir, name).toPath(), StandardOpenOption.READ)
        .use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
    return KanaKanjiConverter(SystemDictionary(map("system.kdic")), ConnectionMatrix(map("connection.kcon")))
}

fun convert(outDir: File, inputs: List<String>) {
    val converter = loadConverter(outDir)
    for (input in inputs) {
        val t = System.nanoTime()
        val result = converter.convert(input, 10)
        val ms = (System.nanoTime() - t) / 1e6
        println("$input (${"%.1f".format(ms)}ms)")
        val segmenter = File(outDir, "pos.kpos").takeIf { it.exists() }?.let { Segmenter(PosTable.read(java.nio.ByteBuffer.wrap(it.readBytes()))) }
        for (c in result) {
            val segs = segmenter?.segments(c.words)?.joinToString(" / ") { it.surface } ?: ""
            println("  ${c.cost}\t${c.text}\t${c.words.joinToString("|") { it.surface }}\t文節: $segs")
        }
    }
}

/** Mozc の evaluation.tsv で辞書だけの変換を測る */
fun evaluate(outDir: File, tsv: File) {
    val converter = loadConverter(outDir)
    val cases = loadCases(tsv)
    repeat(3) { for (c in cases.take(50)) converter.convert(c.input, 10) }
    var pass = 0
    var passOk = 0
    val times = ArrayList<Double>()
    val misses = ArrayList<String>()
    for (c in cases) {
        val t = System.nanoTime()
        val texts = converter.convert(c.input, 100).map { it.text }
        times += (System.nanoTime() - t) / 1e6
        if (c.passes(texts)) {
            pass++
            if (c.label == "OK") passOk++
        } else {
            misses += "${c.input}\t${c.command} ${c.rank}\t${c.value}\t実際 ${texts.firstOrNull()}"
        }
    }
    times.sort()
    val ok = cases.count { it.label == "OK" }
    println("件数 ${cases.size}（うち Mozc で通る OK ${ok}）")
    println("合格 $pass（${"%.1f".format(100.0 * pass / cases.size)}%）、OK のうち $passOk（${"%.1f".format(100.0 * passOk / ok)}%）")
    println("時間 中央値 ${"%.2f".format(times[times.size / 2])}ms、95% ${"%.2f".format(times[times.size * 95 / 100])}ms、最大 ${"%.2f".format(times.last())}ms")
    println("不合格の例:")
    misses.take(15).forEach { println("  $it") }
}

fun openZenz(model: File, lib: File): ZenzModel {
    System.load(lib.absolutePath)
    return ZenzModel.open(model.absolutePath, threads = 4) ?: error("zenz を読めない: $model")
}

fun zenzTry(outDir: File, model: File, lib: File, inputs: List<String>) {
    val converter = loadConverter(outDir)
    openZenz(model, lib).use { zenz ->
        val reranker = Reranker(zenz)
        for (input in inputs) {
            val dict = converter.convert(input, 10).map { it.text }
            var t = System.nanoTime()
            val gen = zenz.generate("", input)
            val genMs = (System.nanoTime() - t) / 1e6
            t = System.nanoTime()
            val ranked = reranker.rerank("", input, dict)!!.candidates
            val rankMs = (System.nanoTime() - t) / 1e6
            println("$input")
            println("  辞書: ${dict.take(5)}")
            println("  生成: $gen（${"%.1f".format(genMs)}ms）")
            println("  並べ替え: ${ranked.take(5)}（${"%.1f".format(rankMs)}ms）")
        }
    }
}

/** 辞書だけ・zenz で並べ替え・並べ替え＋生成 の合格数と時間を比べる */
fun evaluateZenz(outDir: File, tsv: File, model: File, lib: File, topK: Int) {
    val converter = loadConverter(outDir)
    val cases = loadCases(tsv)
    openZenz(model, lib).use { zenz ->
        val reranker = Reranker(zenz, topK = topK)
        val pass = IntArray(3)
        val times = List(2) { ArrayList<Double>() }
        val changed = ArrayList<String>()
        for (c in cases) {
            val dict = converter.convert(c.input, 100).map { it.text }
            var t = System.nanoTime()
            val ranked = reranker.rerank("", c.input, dict)!!.candidates
            times[0] += (System.nanoTime() - t) / 1e6
            t = System.nanoTime()
            val withGen = reranker.rerank("", c.input, dict, useGeneration = true)!!.candidates
            times[1] += (System.nanoTime() - t) / 1e6
            val p = listOf(c.passes(dict), c.passes(ranked), c.passes(withGen))
            p.forEachIndexed { i, b -> if (b) pass[i]++ }
            if (p[0] != p[2]) changed += "${if (p[2]) "改善" else "悪化"}\t${c.input}\t${c.command} ${c.rank} ${c.value}\t辞書 ${dict.firstOrNull()}\tzenz ${withGen.firstOrNull()}"
        }
        fun pct(n: Int) = "$n（${"%.1f".format(100.0 * n / cases.size)}%）"
        fun med(l: ArrayList<Double>) = l.sorted().let { "中央値 ${"%.1f".format(it[it.size / 2])}ms、95% ${"%.1f".format(it[it.size * 95 / 100])}ms" }
        println("件数 ${cases.size}、topK $topK、モデル ${model.name}")
        println("辞書だけ          ${pct(pass[0])}")
        println("zenz で並べ替え   ${pct(pass[1])}  ${med(times[0])}")
        println("並べ替え＋生成    ${pct(pass[2])}  ${med(times[1])}")
        println("辞書だけとの違い（並べ替え＋生成）: 改善 ${changed.count { it.startsWith("改善") }}、悪化 ${changed.count { it.startsWith("悪化") }}")
        changed.take(30).forEach { println("  $it") }
    }
}
