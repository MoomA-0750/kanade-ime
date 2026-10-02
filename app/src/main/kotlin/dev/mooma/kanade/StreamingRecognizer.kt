package dev.mooma.kanade

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import dev.mooma.kanade.whisper.WhisperModel
import java.io.File
import java.util.concurrent.Executors

/**
 * 話しながら文字を出すための音声認識（ReazonSpeech の zipformer、sherpa-onnx）。
 * 本来は一括の認識だが、速い（手元の遅い VM でも 5 秒の音声に 0.2 秒）ので、録音中に「ここまでの音声」を繰り返し認識して途中の文字を出す。
 * 句読点は付かないので、確定の文は whisper があれば whisper で作る。
 */
object StreamingRecognizer {
    /** モデル（csukuangfj/reazonspeech-k2-v2-ja-en の int8）。4 つのファイルをブラウザで取得して設定画面で取り込む */
    const val PAGE_URL = "https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/tree/main"
    val FILES = mapOf(
        "encoder-epoch-35-avg-1.int8.onnx" to 70_876_409L,
        "decoder-epoch-35-avg-1.int8.onnx" to 1_308_690L,
        "joiner-epoch-35-avg-1.int8.onnx" to 1_033_417L,
        "tokens.txt" to 26_631L,
    )

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "kanade-reazon") }
    private var unloadTask: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var recognizer: OfflineRecognizer? = null

    fun dir(context: Context) = File(context.getExternalFilesDir("models"), "reazonspeech")

    fun isReady(context: Context) = FILES.all { (name, size) -> File(dir(context), name).let { it.exists() && it.length() == size } }

    /** 取り込み済みのファイルの数（設定画面の表示用） */
    fun readyCount(context: Context) = FILES.count { (name, size) -> File(dir(context), name).let { it.exists() && it.length() == size } }

    /**
     * 選んだファイルを名前で見分けてアプリ内へ写す（複数まとめて選べる）。写せた数を返す。UI スレッドから呼ばないこと
     */
    fun importFrom(context: Context, uris: List<Uri>): Int {
        val d = dir(context).apply { mkdirs() }
        var count = 0
        for (uri in uris) {
            val name = displayName(context, uri) ?: continue
            // ブラウザが「tokens (1).txt」のように名前を変えることがあるので、知っている名前を含むかで見る
            val target = FILES.keys.firstOrNull { name == it || name.startsWith(it.substringBeforeLast('.')) } ?: continue
            val tmp = File(d, "$target.part")
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } } ?: continue
            if (tmp.length() == FILES.getValue(target)) {
                tmp.renameTo(File(d, target))
                count++
            } else {
                tmp.delete()
            }
        }
        return count
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /**
     * 認識する（専用のスレッドで）。前後に 0.5 秒の無音を足す（足さないと文頭・文末を落としやすかった）。
     * 句読点やスペースは付かない。モデルがなければ null
     */
    fun recognize(context: Context, pcm: FloatArray, threads: Int, onResult: (String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val r = load(app, threads)
            if (r == null || WhisperModel.isSilent(pcm)) {
                onResult(null)
                return@execute
            }
            val pad = FloatArray(WhisperModel.SAMPLE_RATE / 2)
            val text = runCatching {
                val stream = r.createStream()
                stream.acceptWaveform(pad + pcm + pad, WhisperModel.SAMPLE_RATE)
                r.decode(stream)
                val t = r.getResult(stream).text
                stream.release()
                t
            }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            onResult(text)
            // しばらく使わなければ放す（VoiceInput と同じ）
            unloadTask?.cancel(false)
            unloadTask = worker.schedule({
                recognizer?.release()
                recognizer = null
            }, VoiceInput.IDLE_UNLOAD_MINUTES, java.util.concurrent.TimeUnit.MINUTES)
        }
    }

    private fun load(context: Context, threads: Int): OfflineRecognizer? {
        recognizer?.let { return it }
        if (!isReady(context)) return null
        val d = dir(context)
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = WhisperModel.SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(d, "encoder-epoch-35-avg-1.int8.onnx").path,
                    decoder = File(d, "decoder-epoch-35-avg-1.int8.onnx").path,
                    joiner = File(d, "joiner-epoch-35-avg-1.int8.onnx").path,
                ),
                tokens = File(d, "tokens.txt").path,
                numThreads = threads,
                modelType = "transducer",
            ),
            decodingMethod = "greedy_search",
        )
        return runCatching { OfflineRecognizer(null, config) }.getOrNull()?.also { recognizer = it }
    }
}
