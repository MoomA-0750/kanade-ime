package dev.mooma.kanade.dictool

import dev.mooma.kanade.core.convert.KanaKanjiConverter
import dev.mooma.kanade.core.convert.Reranker
import dev.mooma.kanade.zenz.ZenzModel
import dev.mooma.kanade.core.dict.ConnectionMatrix
import dev.mooma.kanade.core.dict.DictionaryBuilder
import dev.mooma.kanade.core.dict.EmojiPanelData
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
/** 辞書の生成物の版（2: pos.kpos、3: symbol.ksym、4: 記号と絵文字を分けた emoji.ksym） */
const val BUILD_REVISION = 4

/** 逐次の音声認識に使う sherpa-onnx の Android 用ライブラリ（onnxruntime を静的に含む AAR）。版とハッシュを固定する */
const val SHERPA_AAR_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar"
const val SHERPA_AAR_FILE = "sherpa-onnx-static-link-onnxruntime-1.13.8.aar"
const val SHERPA_AAR_SHA256 = "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"

/** 辞書以外に取る Mozc のデータ（記号・絵文字）。src/data からのパス */
val MOZC_EXTRA_FILES = listOf("symbol/symbol.tsv", "emoji/emoji_data.tsv")

/** 絵文字パネルの並び（Unicode の emoji-test.txt）。版とハッシュを固定する（Android 16 が Emoji 16.0 まで描ける） */
const val EMOJI_TEST_URL = "https://unicode.org/Public/emoji/16.0/emoji-test.txt"
const val EMOJI_TEST_SHA256 = "24f0c534e86cf142e2496953e8f0e46a3e702392911eddcd29c6cced85139697"

/** zenz のモデル。リビジョンとハッシュを固定する */
/** グライド入力の英単語と頻度（hermitdave/FrequencyWords、OpenSubtitles 2018 の上位5万語。CC BY-SA 4.0） */
const val ENGLISH_WORDS_URL = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/525f9b560de45753a5ea01069454e72e9aa541c6/content/2018/en/en_50k.txt"
const val ENGLISH_WORDS_SHA256 = "5351ff405b1126ef555791dd4d9798a48e3e9a501a9fc481a9da957752cfb458"

/** 英単語のふるい（SCOWL 2020.12.07。英語として正しい語だけを残し、字幕の切れ端（ust など）や人名を外す） */
const val SCOWL_URL = "https://downloads.sourceforge.net/project/wordlist/SCOWL/2020.12.07/scowl-2020.12.07.tar.gz"
const val SCOWL_SHA256 = "5587667caa20c4891390c2d42dbb4d5c4c3f41bee77af1457ece3ba23fb859cc"
/** SCOWL の大きさ（よく使う語から順に 10〜95。70 は大きめの辞書にあたる） */
val SCOWL_SIZES = listOf(10, 20, 35, 40, 50, 55, 60, 70)

/**
 * カタカナ→英語の表記（アップル→Apple）。jmdict-simplified の JSON（JMdict の英語版・JMnedict。EDRDG、CC BY-SA 4.0）。
 * EDRDG の配布元は毎日更新されてハッシュで固定できないので、版の付いたものを使う
 */
const val JMDICT_VERSION = "3.6.2+20260928191014"
const val JMDICT_URL = "https://github.com/scriptin/jmdict-simplified/releases/download/3.6.2%2B20260928191014/jmdict-eng-3.6.2%2B20260928191014.json.zip"
const val JMDICT_SHA256 = "39d57329e4e17cba037072f3189ccd23b2a6bd5d98b563fbe3feef040f1dcdb3"
const val JMNEDICT_URL = "https://github.com/scriptin/jmdict-simplified/releases/download/3.6.2%2B20260928191014/jmnedict-all-3.6.2%2B20260928191014.json.zip"
const val JMNEDICT_SHA256 = "fc6b1ec1b48cd8cbe4f3908ae5cd3e38ae3f705f1afe581077a6809c92f53a35"

/** グライド入力の日本語に入れる読みの数（よく使うものから） */
const val GLIDE_JAPANESE_READINGS = 60000

const val ZENZ_URL = "https://huggingface.co/Miwa-Keita/zenz-v3.2-xsmall-gguf/resolve/4f5423f0fad41a73b1242eb96fe5c12ae4fdca83/ggml-model-Q5_K_M.gguf"
const val ZENZ_FILE = "zenz-v3.2-xsmall-Q5_K_M.gguf"
const val ZENZ_SHA256 = "00c64b3d318045a708d0cad5434faccab10f5481a49e6362864551fd0995fa58"
val MOZC_FILES = (0..9).map { "dictionary0$it.txt" } + listOf("connection_single_column.txt", "id.def", "evaluation.tsv", "README.txt")

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "fetch" -> fetch(File(args[1]))
        "fetch-model" -> fetchModel(File(args[1]))
        "fetch-sherpa" -> fetchSherpa(File(args[1]))
        "assets" -> assets(File(args[1]), File(args[2]))
        "build" -> build(File(args[1]), File(args[2]))
        "eval" -> evaluate(File(args[1]), File(args[2]))
        "convert" -> convert(File(args[1]), args.drop(2))
        "zenz" -> zenzTry(File(args[1]), File(args[2]), File(args[3]), args.drop(4))
        "eval-context" -> evaluateContext(File(args[1]), File(args[2]), File(args[3]), File(args[4]))
        "whisper" -> whisperTry(File(args[1]), File(args[2]), args.drop(3).map(::File))
        "eval-glide" -> evaluateGlide(File(args[1]), args.getOrNull(2)?.toFloat() ?: 0.25f)
        "tune-glide" -> tuneGlide(File(args[1]), args.getOrNull(2)?.toFloat() ?: 0.25f)
        "eval-glide-zenz" -> evaluateGlideZenz(File(args[1]), File(args[2]), File(args[3]), File(args[4]), args.getOrNull(5)?.toFloat() ?: 0.25f)
        "eval-zenz" -> evaluateZenz(File(args[1]), File(args[2]), File(args[3]), File(args[4]), args.getOrNull(5)?.toInt() ?: 10, args.getOrNull(6)?.toFloat() ?: 1.0f)
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
                  eval-glide <assetsDir> [ずれ]  グライドの読み取りを、人の指に近い軌跡で測る（ずれは狙いのずれ。キーの幅に対する割合）
                  tune-glide <assetsDir> [ずれ]  グライドの重みを探す
                  eval-glide-zenz <assetsDir> <builtDir> <gguf> <libkanade_zenz.so> [ずれ]  日本語のグライドを zenz で並べ直したときを測る
                  zenz <outDir> <gguf> <libkanade_zenz.so> <かな...>        zenz の並べ替えと生成を試す
                  eval-zenz <outDir> <tsv> <gguf> <libkanade_zenz.so> [topK] zenz ありで評価する
                  eval-context <outDir> <tsv> <gguf> <libkanade_zenz.so>   左の文脈ありとなしで並べ替えを比べる
                  whisper <ggml> <libkanade_whisper.so> <wav...>           音声認識を試す（16kHz・モノラル・16bit の wav）
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

/** Unicode の emoji-test.txt を取得する（無いときだけ） */
fun fetchEmojiTest(cacheDir: File): File {
    val f = File(cacheDir, "emoji-test-16.0.txt")
    if (!f.exists() || sha256(f) != EMOJI_TEST_SHA256) {
        println("取得: $EMOJI_TEST_URL")
        val tmp = File(cacheDir, "emoji-test-16.0.txt.part")
        URI(EMOJI_TEST_URL).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(sha256(tmp) == EMOJI_TEST_SHA256) { "emoji-test.txt のハッシュが合わない" }
        tmp.renameTo(f)
    }
    return f
}

/** sherpa-onnx の AAR を取得する（無いときだけ）。app の依存として .cache から読む */
fun fetchEnglishWords(cacheDir: File): File {
    val f = File(cacheDir, "en_50k.txt")
    if (!f.exists() || sha256(f) != ENGLISH_WORDS_SHA256) {
        println("取得: $ENGLISH_WORDS_URL")
        val tmp = File(cacheDir, "en_50k.txt.tmp")
        URI(ENGLISH_WORDS_URL).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(sha256(tmp) == ENGLISH_WORDS_SHA256) { "英単語のリストのハッシュが合わない" }
        tmp.renameTo(f)
    }
    return f
}

/**
 * グライド入力の日本語の読み。1行に「読み<TAB>コスト」で、コストは読みだけを変換したときのいちばん良い変換の道のりのコスト
 * （語のコストに、文の始め・終わりとのつながりのコストを足したもの）。Mozc の語のコストは 0 の語が多くて頻度の差が出ないため。
 * 語のコストの小さい読みから候補を取り、道のりのコストの小さい順に [GLIDE_JAPANESE_READINGS] 個。時間がかかるので [built] に残して使い回す
 */
fun glideJapanese(mozcDir: File, built: File): String {
    val cache = File(built, "glide-ja-r$GLIDE_JA_REVISION.tsv")
    if (cache.exists()) return cache.readText()
    val converter = loadConverter(built)
    val candidates = glideReadings(mozcDir).take(GLIDE_JAPANESE_READINGS * 3 / 2)
    val costs = candidates.mapNotNull { r -> converter.convert(r, n = 1).firstOrNull()?.let { r to it.cost } }
    val text = costs.sortedBy { it.second }.take(GLIDE_JAPANESE_READINGS).joinToString("") { "${it.first}\t${it.second}\n" }
    cache.writeText(text)
    return text
}

/** グライド入力の日本語の読みの数え直しの番号（作り方を変えたら上げる） */
const val GLIDE_JA_REVISION = 1

/** Mozc の辞書の読み（語のコストの小さい順） */
fun glideReadings(mozcDir: File): List<String> {
    val best = HashMap<String, Int>()
    for (i in 0..9) {
        File(mozcDir, "dictionary0$i.txt").forEachLine { line ->
            val w = DictionaryBuilder.parseMozcLine(line) ?: return@forEachLine
            val r = w.reading
            if (r.length > 12 || r.any { it !in 'ぁ'..'ゖ' && it != 'ー' }) return@forEachLine
            // 小書きのかな・っ・ー・ん で始まる読み（語の一部だけのもの）は入れない
            if (r.first() in "ぁぃぅぇぉっゃゅょゎゕゖーん") return@forEachLine
            val old = best[r]
            if (old == null || w.cost < old) best[r] = w.cost
        }
    }
    return best.entries.sortedBy { it.value }.map { it.key }
}

fun fetchScowl(cacheDir: File): File {
    val f = File(cacheDir, "scowl-2020.12.07.tar.gz")
    if (!f.exists() || sha256(f) != SCOWL_SHA256) {
        println("取得: $SCOWL_URL")
        val tmp = File(cacheDir, "scowl.tmp")
        URI(SCOWL_URL).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(sha256(tmp) == SCOWL_SHA256) { "SCOWL のハッシュが合わない" }
        tmp.renameTo(f)
    }
    return f
}

/** tar.gz の中のファイルを、名前と中身で順に渡す（tar の 512 バイトの見出しだけを読む簡単なもの） */
fun readTarGz(f: File, each: (name: String, bytes: ByteArray) -> Unit) {
    java.util.zip.GZIPInputStream(f.inputStream().buffered()).use { input ->
        val header = ByteArray(512)
        while (true) {
            if (input.readNBytes(header, 0, 512) < 512) break
            if (header.all { it == 0.toByte() }) break
            val name = String(header, 0, 100, Charsets.ISO_8859_1).substringBefore('\u0000')
            val size = String(header, 124, 12, Charsets.ISO_8859_1).trim('\u0000', ' ').toLong(8)
            val bytes = input.readNBytes(size.toInt())
            val pad = ((512 - size % 512) % 512).toInt()
            input.readNBytes(pad)
            each(name, bytes)
        }
    }
}

/** SCOWL の英単語（小文字。大きさ [SCOWL_SIZES] までの english-words と american-words） */
fun scowlWords(cacheDir: File): Set<String> {
    val names = SCOWL_SIZES.flatMap { listOf("final/english-words.$it", "final/american-words.$it") }.toSet()
    val words = HashSet<String>()
    readTarGz(fetchScowl(cacheDir)) { name, bytes ->
        if (names.any { name.endsWith(it) }) {
            // 大文字で始まる語（人名・地名）は入れない
            String(bytes, Charsets.ISO_8859_1).lineSequence().filter { it.isNotEmpty() && it[0].isLowerCase() }.forEach { words += it }
        }
    }
    return words
}

/**
 * グライド入力の英単語: FrequencyWords の頻度のうち SCOWL にある語と、短縮形（i'm・don't など）。
 * FrequencyWords は ' で語を切っている（don't が don と t になる）ので、短縮形は ' の後ろの切れ端の回数を、元の語の回数の割合で分けて作る
 */
fun glideEnglish(cacheDir: File): String {
    val counts = LinkedHashMap<String, Long>()
    fetchEnglishWords(cacheDir).forEachLine { line ->
        val parts = line.split(' ')
        if (parts.size == 2) parts[1].toLongOrNull()?.let { counts[parts[0]] = it }
    }
    val dictionary = scowlWords(cacheDir)
    val out = LinkedHashMap<String, Long>()
    for ((w, c) in counts) if (w.length >= 2 && w.all { it in 'a'..'z' } && w in dictionary) out[w] = c
    // n't: 切れ端（don・didn など）の回数がそのまま短縮形の回数。can't・won't は 't の残りを 3:1 で分ける
    val nt = mapOf(
        "don't" to "don", "didn't" to "didn", "doesn't" to "doesn", "isn't" to "isn", "wasn't" to "wasn", "aren't" to "aren",
        "couldn't" to "couldn", "wouldn't" to "wouldn", "shouldn't" to "shouldn", "haven't" to "haven", "hasn't" to "hasn",
        "hadn't" to "hadn", "weren't" to "weren", "ain't" to "ain",
    )
    var ntSum = 0L
    for ((w, fragment) in nt) counts[fragment]?.let { out[w] = it; ntSum += it }
    val rest = maxOf(0L, (counts["'t"] ?: 0L) - ntSum)
    out["can't"] = rest * 3 / 4
    out["won't"] = rest / 4
    // 'm は i'm だけ。're・'ll・'ve・'d・'s は、' の後ろの切れ端の回数を、元の語の回数の割合で分ける（'s は持ち主の 's もあるので半分）
    out["i'm"] = counts["'m"] ?: 0L
    val others = mapOf(
        "'re" to listOf("you're", "we're", "they're"),
        "'ll" to listOf("i'll", "you'll", "we'll", "he'll", "she'll", "it'll", "they'll", "that'll"),
        "'ve" to listOf("i've", "you've", "we've", "they've", "could've", "would've", "should've"),
        "'d" to listOf("i'd", "you'd", "he'd", "she'd", "we'd", "they'd"),
        "'s" to listOf("it's", "that's", "he's", "she's", "what's", "there's", "let's", "who's", "here's", "where's"),
    )
    for ((suffix, words) in others) {
        val total = (counts[suffix] ?: continue) * (if (suffix == "'s") 1 else 2) / 2
        val weights = words.associateWith { (counts[it.substringBefore('\'')] ?: 1L).toDouble() }
        val sum = weights.values.sum()
        for (w in words) out[w] = maxOf(1L, (total * weights.getValue(w) / sum).toLong())
    }
    // 切れ端は語ではないので外す。don・won は語でもあるが、回数のほとんどは don't・won't なので減らす
    for (fragment in nt.values) out.remove(fragment)
    if ("don" in dictionary) out["don"] = 5000L
    counts["won"]?.let { out["won"] = maxOf(1L, it - (out["won't"] ?: 0L)) }
    return out.entries.sortedByDescending { it.value }.joinToString("") { "${it.key} ${it.value}\n" }
}

fun fetchSherpa(cacheDir: File): File {
    val dir = File(cacheDir, "sherpa-aar").apply { mkdirs() }
    val f = File(dir, SHERPA_AAR_FILE)
    if (!f.exists() || sha256(f) != SHERPA_AAR_SHA256) {
        println("取得: $SHERPA_AAR_URL")
        val tmp = File(dir, "$SHERPA_AAR_FILE.part")
        URI(SHERPA_AAR_URL).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(sha256(tmp) == SHERPA_AAR_SHA256) { "sherpa-onnx の AAR のハッシュが合わない" }
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
    for (name in listOf("system.kdic", "connection.kcon", "pos.kpos", "symbol.ksym", "emoji.ksym", "mozc-dictionary-license.txt")) {
        File(built, name).copyTo(File(outDir, name), overwrite = true)
    }
    model.copyTo(File(outDir, "zenz.gguf"), overwrite = true)
    fetchSherpa(cacheDir)
    // 画面キーボードの絵文字パネル（グループごとの並び）
    // 検索に使う日本語の言葉は Mozc の emoji_data.tsv（読み・名前・説明）から
    val japanese = EmojiPanelData.japaneseFromMozc(File(mozcDir, "emoji_data.tsv").readLines())
    // グライド入力の語（日本語は Mozc の読み、英語は FrequencyWords）
    File(outDir, "glide-ja.tsv").writeText(glideJapanese(mozcDir, built))
    File(outDir, "glide-en.txt").writeText(glideEnglish(cacheDir))
    // カタカナ→英語の表記（アップル→Apple）
    val english = File(built, "english-$JMDICT_VERSION.ksym")
    if (!english.exists()) english.writeText(englishTable(cacheDir))
    english.copyTo(File(outDir, "english.ksym"), overwrite = true)
    readTarGz(fetchScowl(cacheDir)) { name, bytes -> if (name.endsWith("/Copyright")) File(outDir, "scowl-license.txt").writeBytes(bytes) }
    File(outDir, "emoji-panel.tsv").writeText(EmojiPanelData.fromUnicodeTest(fetchEmojiTest(cacheDir).readLines(), japanese).write())
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
    File(outDir, "symbol.ksym").writeText(SymbolTable.fromMozcSymbols(File(mozcDir, "symbol.tsv").readLines()).write())
    File(outDir, "emoji.ksym").writeText(SymbolTable.fromMozcEmoji(File(mozcDir, "emoji_data.tsv").readLines()).write())
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
fun evaluateZenz(outDir: File, tsv: File, model: File, lib: File, topK: Int, beta: Float = 1.0f) {
    val converter = loadConverter(outDir)
    val cases = loadCases(tsv)
    openZenz(model, lib).use { zenz ->
        val reranker = Reranker(zenz, topK = topK, beta = beta)
        val pass = IntArray(3)
        val times = List(2) { ArrayList<Double>() }
        val changed = ArrayList<String>()
        val remaining = ArrayList<String>()
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
            if (!p[1]) {
                // 並べ替えでも外れた文: 正解が辞書の候補の何位にいるか（いなければ、並べ替えでは届かない）
                val answers = c.value.split('|')
                val rankInDict = dict.indexOfFirst { it in answers }
                remaining += "残り\t${c.input}\t${c.value}\tzenz ${ranked.firstOrNull()}\t正解は辞書の${if (rankInDict < 0) "候補にない" else "${rankInDict + 1}位"}"
            }
        }
        fun pct(n: Int) = "$n（${"%.1f".format(100.0 * n / cases.size)}%）"
        fun med(l: ArrayList<Double>) = l.sorted().let { "中央値 ${"%.1f".format(it[it.size / 2])}ms、95% ${"%.1f".format(it[it.size * 95 / 100])}ms" }
        println("件数 ${cases.size}、topK $topK、β $beta、モデル ${model.name}")
        println("辞書だけ          ${pct(pass[0])}")
        println("zenz で並べ替え   ${pct(pass[1])}  ${med(times[0])}")
        println("並べ替え＋生成    ${pct(pass[2])}  ${med(times[1])}")
        println("辞書だけとの違い（並べ替え＋生成）: 改善 ${changed.count { it.startsWith("改善") }}、悪化 ${changed.count { it.startsWith("悪化") }}")
        changed.take(30).forEach { println("  $it") }
        println("並べ替えでも外れた文: ${remaining.size}")
        remaining.forEach { println("  $it") }
    }
}

fun whisperTry(model: File, lib: File, wavs: List<File>) {
    System.load(lib.absolutePath)
    var t = System.nanoTime()
    val whisper = dev.mooma.kanade.whisper.WhisperModel.open(model.absolutePath, threads = 4) ?: error("whisper を読めない: $model")
    println("読み込み ${"%.0f".format((System.nanoTime() - t) / 1e6)}ms")
    whisper.use { w ->
        for (wav in wavs) {
            val bytes = wav.readBytes()
            // 44 バイトの見出しのあとが 16bit PCM（作った wav は素直な形なのでそれで足りる）
            val n = (bytes.size - 44) / 2
            val pcm = FloatArray(n) { i ->
                val lo = bytes[44 + 2 * i].toInt() and 0xFF
                val hi = bytes[45 + 2 * i].toInt()
                ((hi shl 8) or lo).toShort() / 32768f
            }
            t = System.nanoTime()
            val text = w.transcribe(pcm)
            println("${wav.name}（${"%.1f".format(n / 16000.0)}秒の音声）: ${"%.0f".format((System.nanoTime() - t) / 1e6)}ms")
            println("  $text")
        }
    }
}

/** 左の文脈で正解が変わる文（eval/context.tsv）で、zenz に文脈を渡したときと渡さないときを比べる */
fun evaluateContext(outDir: File, tsv: File, model: File, lib: File) {
    val converter = loadConverter(outDir)
    val cases = tsv.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
    openZenz(model, lib).use { zenz ->
        val reranker = Reranker(zenz)
        var dictHit = 0
        var noCtx = 0
        var withCtx = 0
        for (f in cases) {
            val (context, reading, answer) = f
            // 4列目があれば右の文脈（カーソルの後ろの文）。比べるのは「文脈なし」と「左右の文脈あり」
            val right = f.getOrElse(3) { "" }
            val answers = answer.split('|')
            val dict = converter.convert(reading, 100).map { it.text }
            val a = reranker.rerank("", reading, dict)!!.candidates.firstOrNull()
            val b = reranker.rerank(context, reading, dict, rightContext = right)!!.candidates.firstOrNull()
            if (dict.firstOrNull() in answers) dictHit++
            if (a in answers) noCtx++
            if (b in answers) withCtx++
            val mark = when {
                a !in answers && b in answers -> "文脈で直った"
                a in answers && b !in answers -> "文脈で悪化"
                b in answers -> "正"
                else -> "誤"
            }
            println("$mark\t$context｜$reading｜$right\t文脈なし $a\t文脈あり $b")
        }
        println("件数 ${cases.size}: 辞書だけ $dictHit、zenz 文脈なし $noCtx、zenz 文脈あり $withCtx")
    }
}

/** グライドの試験に使うキーの並び（キーの幅 100、高さ [keyHeight]） */
fun glideKeys(keyHeight: Float): Map<Char, Pair<Float, Float>> = buildMap {
    listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { row, (letters, offset) ->
        letters.forEachIndexed { i, c -> put(c, (offset + i + 0.5f) * 100f to (row + 0.5f) * keyHeight) }
    }
}

/**
 * 人の指に近い軌跡: キーごとに狙いが中心からずれ（[aim] はずれの標準偏差、キーの幅に対する割合）、
 * 角を丸めて（Chaikin の角切りを1回）通り、少し震える
 */
fun humanGesture(spelling: String, keys: Map<Char, Pair<Float, Float>>, r: java.util.Random, aim: Float, keyHeight: Float = 100f): Pair<FloatArray, FloatArray> {
    // 縦の狙いのずれは、キーの高さに比例する（横長のキーなら縦のずれも小さい）
    val path = dev.mooma.kanade.core.glide.GlideLexicon.collapse(spelling).map { c ->
        val (x, y) = keys.getValue(c)
        (x + r.nextGaussian().toFloat() * aim * 100f) to (y + r.nextGaussian().toFloat() * aim * keyHeight)
    }
    var pts = path
    repeat(1) {
        if (pts.size < 3) return@repeat
        val next = ArrayList<Pair<Float, Float>>()
        next += pts.first()
        for (i in 0 until pts.size - 1) {
            val (x0, y0) = pts[i]
            val (x1, y1) = pts[i + 1]
            if (i > 0) next += (0.75f * x0 + 0.25f * x1) to (0.75f * y0 + 0.25f * y1)
            if (i < pts.size - 2) next += (0.25f * x0 + 0.75f * x1) to (0.25f * y0 + 0.75f * y1)
        }
        next += pts.last()
        pts = next
    }
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    for (i in pts.indices) {
        if (i > 0) for (k in 1..4) {
            val t = k / 5f
            xs += pts[i - 1].first + (pts[i].first - pts[i - 1].first) * t + r.nextGaussian().toFloat() * 4f
            ys += pts[i - 1].second + (pts[i].second - pts[i - 1].second) * t + r.nextGaussian().toFloat() * 4f
        }
        xs += pts[i].first
        ys += pts[i].second
    }
    return xs.toFloatArray() to ys.toFloatArray()
}

/** よく使う語ほど多く出る見本（使う頻度に近づける。上位 [top] 語から、順位の逆数の重みで [n] 語） */
fun <T> zipfSample(items: List<T>, n: Int, top: Int, r: java.util.Random): List<T> {
    val pool = items.take(top)
    val weights = pool.indices.map { 1.0 / (it + 10) }
    val total = weights.sum()
    return List(n) {
        var x = r.nextDouble() * total
        var i = 0
        while (i < pool.size - 1 && x > weights[i]) {
            x -= weights[i]
            i++
        }
        pool[i]
    }
}

class GlideEvalSet(val name: String, val lexicon: dev.mooma.kanade.core.glide.GlideLexicon, val cases: List<Pair<String, String>>)

/** 試験の見本（綴り → 正しい出力）。日本語は読みをヘボン式でなぞる */
fun glideEvalSets(assets: File, n: Int): List<GlideEvalSet> {
    val r = java.util.Random(11)
    val jaLines = File(assets, "glide-ja.tsv").readLines()
    val ja = dev.mooma.kanade.core.glide.GlideLexicon.japanese(jaLines.asSequence())
    val jaCases = zipfSample(jaLines.map { it.substringBefore('\t') }, n, 8000, r).mapNotNull { kana ->
        dev.mooma.kanade.core.glide.KanaSpelling.spellings(kana).lastOrNull()?.let { it to kana }
    }.filter { dev.mooma.kanade.core.glide.GlideLexicon.collapse(it.first).length >= 2 }
    val enLines = File(assets, "glide-en.txt").readLines()
    val en = dev.mooma.kanade.core.glide.GlideLexicon.english(enLines.asSequence())
    val enCases = zipfSample(enLines.map { it.substringBefore(' ') }, n, 8000, r).map { w ->
        w.filter { it != '\'' } to (if (w == "i" || w.startsWith("i'")) "I" + w.substring(1) else w)
    }.filter { dev.mooma.kanade.core.glide.GlideLexicon.collapse(it.first).length >= 2 }
    return listOf(GlideEvalSet("日本語", ja, jaCases), GlideEvalSet("英語", en, enCases))
}

data class GlideEvalResult(val top1: Double, val top3: Double, val medianMs: Double, val misses: List<String>)

fun runGlideEval(set: GlideEvalSet, params: dev.mooma.kanade.core.glide.GlideParams, keyHeight: Float, aim: Float): GlideEvalResult {
    val keys = glideKeys(keyHeight)
    val decoder = dev.mooma.kanade.core.glide.GlideDecoder(set.lexicon, params)
    val r = java.util.Random(5)
    var top1 = 0
    var top3 = 0
    val times = ArrayList<Double>()
    val misses = ArrayList<String>()
    for ((spelling, expected) in set.cases) {
        val (xs, ys) = humanGesture(spelling, keys, r, aim, keyHeight)
        val t = System.nanoTime()
        val result = decoder.decode(xs, ys, keys, 100f, limit = 9, keyHeight = if (System.getenv("GLIDE_SQUARE") != null) 100f else keyHeight).map { it.output }
        times += (System.nanoTime() - t) / 1e6
        if (result.firstOrNull() == expected) top1++ else if (misses.size < 10) misses += "$expected→${result.take(3)}"
        if (expected in result.take(3)) top3++
    }
    times.sort()
    return GlideEvalResult(100.0 * top1 / set.cases.size, 100.0 * top3 / set.cases.size, times[times.size / 2], misses)
}

/**
 * グライドの読み取りを、人の指に近い軌跡（[humanGesture]）で測る。よく使う語ほど多く試す。
 * 縦長のキー（スマホ、高さ 140）と横長のキー（Fold を開いた画面、高さ 65）の両方で見る
 */
fun evaluateGlide(assets: File, aim: Float) {
    val sets = glideEvalSets(assets, 400)
    for (set in sets) for (h in listOf(140f, 65f)) {
        // GLIDE_OLD を付けると、比べるために前の重み（DTW なし）で測る
        val params = if (System.getenv("GLIDE_OLD") != null) dev.mooma.kanade.core.glide.GlideParams()
        else if (set.name == "英語") dev.mooma.kanade.core.glide.GlideParams.ENGLISH else dev.mooma.kanade.core.glide.GlideParams.JAPANESE
        val res = runGlideEval(set, params, h, aim)
        println("${set.name}（キーの高さ $h）: ${set.cases.size} 語で 1位 ${"%.0f".format(res.top1)}%・3位以内 ${"%.0f".format(res.top3)}%、中央値 ${"%.1f".format(res.medianMs)}ms")
        println("  外れの例: ${res.misses.joinToString("、")}")
    }
}

/** 重みを1つずつ動かして、1位の割合（3位以内も少し見る）がいちばん良いものを探す（2周） */
fun tuneGlide(assets: File, aim: Float) {
    val sets = glideEvalSets(assets, 250)
    for (set in sets) {
        var best = if (set.name == "英語") dev.mooma.kanade.core.glide.GlideParams(0.275f, 0.255f, 6f, 0.75f, dtwSigma = 0.25f)
        else dev.mooma.kanade.core.glide.GlideParams(0.2f, 0.294f, 3f, 1.7f, dtwSigma = 0.2f)
        fun score(p: dev.mooma.kanade.core.glide.GlideParams) = listOf(140f, 65f).sumOf { h ->
            runGlideEval(set, p, h, aim).let { it.top1 + 0.3 * it.top3 }
        } / 2
        var bestScore = score(best)
        println("${set.name}: 始め $best → ${"%.1f".format(bestScore)}")
        repeat(2) {
            for (variant in listOf<(dev.mooma.kanade.core.glide.GlideParams) -> List<dev.mooma.kanade.core.glide.GlideParams>>(
                { p -> listOf(0.8f, 0.9f, 1.1f, 1.25f).map { p.copy(sigma = p.sigma * it) } },
                { p -> listOf(0.7f, 0.85f, 1.2f, 1.4f).map { p.copy(endSigma = p.endSigma * it) } },
                { p -> listOf(0.6f, 0.8f, 1.3f, 2f, 4f).map { p.copy(keySigma = p.keySigma * it) } },
                { p -> listOf(0.6f, 0.8f, 1.25f, 1.6f).map { p.copy(priorWeight = p.priorWeight * it) } },
                { p -> listOf(0.6f, 0.8f, 1.25f, 1.6f).map { p.copy(dtwSigma = p.dtwSigma * it) } },
            )) {
                for (p in variant(best)) {
                    val sc = score(p)
                    if (sc > bestScore) {
                        bestScore = sc
                        best = p
                    }
                }
            }
            println("${set.name}: $best → ${"%.1f".format(bestScore)}")
            System.out.flush()
        }
    }
}

/**
 * 日本語のグライドの上位を zenz で並べ直したときの正確さを、重みを変えて測る。
 * 読みごとに Mozc のいちばん良い変換を付け、「読み＋変換」の対数確率を zenz に聞く。文脈つきの見本（手で作った）も見る
 */
fun evaluateGlideZenz(assets: File, built: File, gguf: File, lib: File, aim: Float) {
    val zenz = openZenz(gguf, lib)
    val converter = loadConverter(built)
    val set = glideEvalSets(assets, (System.getenv("GLIDE_N") ?: "300").toInt()).first { it.name == "日本語" }
    val decoder = dev.mooma.kanade.core.glide.GlideDecoder(set.lexicon, dev.mooma.kanade.core.glide.GlideParams.JAPANESE)
    fun conversion(reading: String) = converter.convert(reading, n = 1).firstOrNull()?.words?.joinToString("") { it.surface } ?: reading
    // 文脈つきの見本: （入力中の読み, なぞる語の綴り, 正しい読み）
    val contextCases = listOf(
        Triple("がっこうに", "ikeru", "いける"), Triple("ほんが", "aru", "ある"), Triple("ともだちに", "au", "あう"),
        Triple("きょうは", "tenki", "てんき"), Triple("あしたは", "yasumi", "やすみ"), Triple("でんしゃに", "noru", "のる"),
        Triple("ごはんを", "taberu", "たべる"), Triple("えいがを", "miru", "みる"), Triple("みずを", "nomu", "のむ"),
        Triple("てがみを", "kaku", "かく"), Triple("おんがくを", "kiku", "きく"), Triple("かいぎに", "deru", "でる"),
        Triple("しごとが", "owaru", "おわる"), Triple("あめが", "furu", "ふる"), Triple("いえに", "kaeru", "かえる"),
        Triple("でんわを", "kakeru", "かける"), Triple("しゃしんを", "toru", "とる"), Triple("みちを", "aruku", "あるく"),
        Triple("ねつが", "aru", "ある"), Triple("かおを", "arau", "あらう"),
    )
    for (h in listOf(140f)) {
        val keys = glideKeys(h)
        val r = java.util.Random(5)
        class Case(val expected: String, val cands: List<dev.mooma.kanade.core.glide.GlideCandidate>, val lp: FloatArray?)
        val cases = ArrayList<Case>()
        val times = ArrayList<Double>()
        fun run(context: String, spelling: String, expected: String) {
            val (xs, ys) = humanGesture(spelling, keys, r, aim, h)
            var cands = decoder.decode(xs, ys, keys, 100f, limit = 9, keyHeight = h)
            if (context.isNotEmpty()) {
                val costs = HashMap<String, Int?>()
                fun cost(x: String) = costs.getOrPut(x) { converter.convert(x, n = 1).firstOrNull()?.cost }
                cands = decoder.rerank(cands) { w -> dev.mooma.kanade.core.glide.GlideLexicon.japanesePrior(context, w, ::cost) }
            }
            val t = System.nanoTime()
            val lp = zenz.scoreReadings("", cands.map { (context + it.output) to conversion(context + it.output) })
            times += (System.nanoTime() - t) / 1e6
            cases += Case(expected, cands, lp)
        }
        for ((spelling, expected) in set.cases) run("", spelling, expected)
        val plain = cases.size
        for ((context, spelling, expected) in contextCases) run(context, spelling, expected)
        if (h == 140f) for (c in cases.drop(plain)) {
            val base = c.cands.take(3).map { it.output }
            val z = c.lp?.let { decoder.rerankWithModel(c.cands, it, 0.4f, 0.5f) }?.take(3)?.map { it.output }
            val lps = c.cands.take(4).mapIndexed { i, x -> "${x.output}:${"%.1f".format(c.lp?.get(i) ?: 0f)}" }
            println("  ${c.expected}: 辞書 $base／zenz $z（$lps）")
        }
        times.sort()
        println("キーの高さ $h: zenz の時間 中央値 ${"%.0f".format(times[times.size / 2])}ms・95% ${"%.0f".format(times[times.size * 95 / 100])}ms")
        for (keep in listOf(1f, 0.5f, 0f)) for (w in listOf(0f, 0.2f, 0.4f, 0.7f, 1f, 1.5f)) {
            var t1 = 0
            var t3 = 0
            var c1 = 0
            for ((i, c) in cases.withIndex()) {
                val ranked = c.lp?.let { decoder.rerankWithModel(c.cands, it, w, keep) } ?: c.cands
                val out = ranked.map { it.output }
                if (i < plain) {
                    if (out.firstOrNull() == c.expected) t1++
                    if (c.expected in out.take(3)) t3++
                } else if (out.firstOrNull() == c.expected) c1++
            }
            println("  辞書の頻度を残す ${keep}・zenz の重み $w: 1位 ${100 * t1 / plain}%・3位以内 ${100 * t3 / plain}%、文脈つき ${c1}/${contextCases.size}")
        }
    }
}

fun fetchPinned(cacheDir: File, name: String, url: String, sha: String): File {
    val f = File(cacheDir, name)
    if (!f.exists() || sha256(f) != sha) {
        println("取得: $url")
        val tmp = File(cacheDir, "$name.tmp")
        URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(sha256(tmp) == sha) { "$name のハッシュが合わない" }
        tmp.renameTo(f)
    }
    return f
}

/** jmdict-simplified の JSON（zip の中の1ファイル）の words の要素を1つずつ渡す */
fun eachJmWord(zip: File, each: (com.fasterxml.jackson.databind.JsonNode) -> Unit) {
    java.util.zip.ZipFile(zip).use { z ->
        val entry = z.entries().asSequence().first { it.name.endsWith(".json") }
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        mapper.factory.createParser(z.getInputStream(entry)).use { p ->
            while (p.nextToken() != null) {
                if (p.currentToken == com.fasterxml.jackson.core.JsonToken.FIELD_NAME && p.currentName() == "words") {
                    p.nextToken() // START_ARRAY
                    while (p.nextToken() == com.fasterxml.jackson.core.JsonToken.START_OBJECT) each(mapper.readTree(p))
                    return
                }
            }
        }
    }
}

private val KATAKANA_WORD = Regex("^[ァ-ヺー・]+$")
/** 英字の表記（「pan-」のような接頭辞の書き方は入れない） */
private val LATIN_WORD = Regex("^[A-Za-z0-9]([A-Za-z0-9 .&'+!-]*[A-Za-z0-9.!+])?$")

/** カタカナのおおまかな子音の並び（英語の綴りと発音が近いかを見るため） */
private val KATAKANA_CONSONANT: Map<Char, Char> = buildMap {
    fun row(kana: String, c: Char) = kana.forEach { put(it, c) }
    row("カキクケコ", 'k'); row("ガギグゲゴ", 'g'); row("サシスセソ", 's'); row("ザジズゼゾヂヅ", 'z'); row("タツテト", 't'); row("チ", 't')
    row("ダデド", 'd'); row("ナニヌネノン", 'n'); row("ハヒヘホ", 'h'); row("フ", 'h'); row("バビブベボヴ", 'b'); row("パピプペポ", 'p')
    row("マミムメモ", 'm'); row("ヤユヨ", 'y'); row("ラリルレロ", 'r')
}

private fun dedupe(s: String) = buildString { for (c in s) if (isEmpty() || last() != c) append(c) }

/** カタカナと英語の綴りの、子音の並びの近さ（0〜1） */
fun soundSimilarity(katakana: String, english: String): Double {
    val a = dedupe(katakana.mapNotNull { KATAKANA_CONSONANT[it] }.joinToString(""))
    var e = english.lowercase()
    for ((from, to) in listOf("ph" to "f", "th" to "s", "ch" to "t", "sh" to "s", "ck" to "k", "x" to "ks")) e = e.replace(from, to)
    val b = dedupe(e.mapNotNull { c ->
        when (c) {
            in "aeiouyw" -> null
            'c', 'q' -> 'k'
            'l' -> 'r'
            'v' -> 'b'
            'f' -> 'h'
            'j' -> 'z'
            in 'a'..'z' -> c
            else -> null
        }
    }.joinToString(""))
    if (a.isEmpty() && b.isEmpty()) return 1.0
    // 最長共通部分列の長さで比べる
    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 1..a.length) for (j in 1..b.length) {
        dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
    }
    return 2.0 * dp[a.length][b.length] / (a.length + b.length)
}

/**
 * カタカナの読み（ひらがなにしたもの）→英語の表記の表（english.ksym）。
 * - JMnedict（固有名）: カタカナだけの読みと、英字の表記（グーグル→Google、トヨタ→Toyota）
 * - JMdict（外来語）: 漢字の無い語で、英語が元の語（ほかの言語・和製英語でない）の意味のうち、綴りと発音が近いもの（アップル→apple）
 */
fun englishTable(cacheDir: File): String {
    val map = LinkedHashMap<String, MutableList<String>>()
    fun add(katakana: String, english: String) {
        val reading = dev.mooma.kanade.core.CharWidth.toHiragana(katakana.replace("・", ""))
        val list = map.getOrPut(reading) { ArrayList() }
        if (english !in list && list.size < 6) list += english
    }
    eachJmWord(fetchPinned(cacheDir, "jmdict-eng-$JMDICT_VERSION.json.zip", JMDICT_URL, JMDICT_SHA256)) { w ->
        if (w["kanji"].size() > 0) return@eachJmWord
        val readings = w["kana"].map { it["text"].asText() }.filter { KATAKANA_WORD.matches(it) }
        if (readings.isEmpty()) return@eachJmWord
        for (sense in w["sense"]) {
            val sources = sense["languageSource"]
            if (sources.any { it["lang"].asText() != "eng" || it["wasei"].asBoolean() }) continue
            for (g in sense["gloss"]) {
                val t = g["text"].asText()
                if (!LATIN_WORD.matches(t) || t.split(' ').size > 2) continue
                for (r in readings) if (soundSimilarity(r, t) >= 0.6) add(r, t)
            }
        }
    }
    eachJmWord(fetchPinned(cacheDir, "jmnedict-all-$JMDICT_VERSION.json.zip", JMNEDICT_URL, JMNEDICT_SHA256)) { w ->
        val readings = w["kana"].map { it["text"].asText() }.filter { KATAKANA_WORD.matches(it) }
        if (readings.isEmpty()) return@eachJmWord
        for (t in w["translation"]) for (x in t["translation"]) {
            val text = x["text"].asText()
            if (LATIN_WORD.matches(text)) for (r in readings) add(r, text)
        }
    }
    return dev.mooma.kanade.core.dict.SymbolTable(map).write()
}
