package dev.mooma.kanade

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.mooma.kanade.whisper.WhisperModel
import java.io.File
import java.util.concurrent.Executors

/**
 * 逐次（ストリーミング）型の音声認識。NVIDIA Nemotron-3.5-ASR-Streaming-0.6B（sherpa-onnx の int8、560ms 区切り、約 682MB）。
 * 届いた音声だけを処理するので、話が長くなっても1回の計算が増えない（[SherpaRecognizer.reazon] で毎回全部をかけ直すのと違う）。
 * 日本語で句読点も付く。話しながらの途中表示に使い、話し終わったら確定用のモデルの文で書き換える（Pixel・iPhone と同じ2段構え）。
 * 確定用として「適用」したときは、録った音声を最後まで流して文にする
 */
object StreamingRecognizer {
    const val LABEL = "Nemotron-3.5 Streaming"
    private const val DIR = "nemotron-3.5-streaming-560ms"
    /** HuggingFace のファイルの置き場（リビジョンを固定） */
    private const val URL =
        "https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11/resolve/ab43d895f5985b1bbab8b6eac8607fcdc05343f3/"
    val files = mapOf(
        "encoder.int8.onnx" to 657_601_403L,
        "decoder.int8.onnx" to 14_978_075L,
        "joiner.int8.onnx" to 9_504_438L,
        "tokens.txt" to 131_440L,
    )

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "kanade-nemotron") }
    private var unloadTask: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var recognizer: OnlineRecognizer? = null
    /** 話しながらの途中表示の流れ（worker のスレッドだけが触る） */
    private var stream: OnlineStream? = null

    fun dir(context: Context) = File(context.getExternalFilesDir("models"), DIR)

    fun isReady(context: Context) = files.all { (name, size) -> File(dir(context), name).let { it.exists() && it.length() == size } }

    fun downloadItems(context: Context) = files.map { (name, size) -> ModelDownloader.Item(URL + name, File(dir(context), name), size) }

    fun delete(context: Context) {
        worker.execute { release() }
        dir(context).deleteRecursively()
    }

    /** 先に読んでおく（初めの読み込みに数秒かかるので、録音を始めたときに呼ぶ） */
    fun preload(context: Context, threads: Int) {
        val app = context.applicationContext
        worker.execute {
            load(app, threads)
            scheduleUnload()
        }
    }

    /**
     * 話しながらの途中表示。[reset] なら新しい流れで始め、[pcm]（前回の続きの音声）を流して、ここまでの文字を [onResult] に返す
     * （worker のスレッドから。モデルがなければ null）
     */
    fun feed(context: Context, pcm: FloatArray, reset: Boolean, threads: Int, onResult: (String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val r = load(app, threads)
            if (r == null) {
                onResult(null)
                return@execute
            }
            val text = runCatching {
                if (reset || stream == null) {
                    stream?.release()
                    stream = newStream(r).also { it.acceptWaveform(LEAD, WhisperModel.SAMPLE_RATE) }
                }
                val s = stream!!
                if (pcm.isNotEmpty()) s.acceptWaveform(pcm, WhisperModel.SAMPLE_RATE)
                while (r.isReady(s)) r.decode(s)
                r.getResult(s).text
            }.getOrNull()?.let { SherpaRecognizer.tidy(it) }
            onResult(text)
            scheduleUnload()
        }
    }

    /** 途中表示の流れを捨てる（録音を終えたとき） */
    fun finishStream() {
        worker.execute {
            stream?.release()
            stream = null
        }
    }

    /** 録った音声をまとめて文にする（確定用として使うとき・設定画面の試験）。後ろに無音を足して最後の区切りまで出し切る */
    fun recognize(context: Context, pcm: FloatArray, threads: Int, onResult: (String?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val r = load(app, threads)
            if (r == null || WhisperModel.isSilent(pcm)) {
                onResult(null)
                return@execute
            }
            val text = runCatching {
                val s = newStream(r)
                s.acceptWaveform(LEAD + pcm + FloatArray(WhisperModel.SAMPLE_RATE), WhisperModel.SAMPLE_RATE)
                s.inputFinished()
                while (r.isReady(s)) r.decode(s)
                val t = r.getResult(s).text
                s.release()
                t
            }.getOrNull()?.let { SherpaRecognizer.tidy(it) }?.takeIf { it.isNotEmpty() }
            onResult(text)
            scheduleUnload()
        }
    }

    /**
     * 流れの頭に足す 0.25 秒の無音。いきなり話し始める音声だと出だしを取り違えた
     * （見本の「今日はいい天気ですね」が「キュワイ天気ですね」に。足すと直った）
     */
    private val LEAD = FloatArray(WhisperModel.SAMPLE_RATE / 4)

    private fun newStream(r: OnlineRecognizer): OnlineStream = r.createStream("").also { it.setOption("language", "ja") }

    /** しばらく使わなければ放す（VoiceInput と同じ） */
    private fun scheduleUnload() {
        unloadTask?.cancel(false)
        unloadTask = worker.schedule({ release() }, VoiceInput.IDLE_UNLOAD_MINUTES, java.util.concurrent.TimeUnit.MINUTES)
    }

    private fun release() {
        stream?.release()
        stream = null
        recognizer?.release()
        recognizer = null
    }

    private fun load(context: Context, threads: Int): OnlineRecognizer? {
        recognizer?.let { return it }
        if (!isReady(context)) return null
        val d = dir(context)
        val c = OnlineRecognizerConfig(
            // Nemotron は 128 次元の特徴量
            featConfig = FeatureConfig(sampleRate = WhisperModel.SAMPLE_RATE, featureDim = 128),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(d, "encoder.int8.onnx").path,
                    decoder = File(d, "decoder.int8.onnx").path,
                    joiner = File(d, "joiner.int8.onnx").path,
                ),
                tokens = File(d, "tokens.txt").path,
                numThreads = threads,
            ),
            decodingMethod = "greedy_search",
        )
        return runCatching { OnlineRecognizer(null, c) }.getOrNull()?.also { recognizer = it }
    }
}
