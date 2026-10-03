package dev.mooma.kanade

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import dev.mooma.kanade.whisper.WhisperModel
import java.io.File
import java.util.concurrent.Executors

/**
 * sherpa-onnx で動かす音声認識のモデル（ReazonSpeech・SenseVoice）。どちらも一括（オフライン）型。
 * モデルは HuggingFace から設定画面で落とす（[ModelDownloader]）か、手元のファイルを取り込む。
 * モデルごとに専用のスレッドを持つ（話しながらの途中表示と、確定の認識を並べて回せるように）。
 */
class SherpaRecognizer(
    /** 画面に出す名前 */
    val label: String,
    private val dirName: String,
    /** HuggingFace のファイルの置き場（リビジョンを固定）。名前を後ろに付けて落とす */
    val fileUrl: String,
    /** ファイル名と大きさ（大きさが合えば取得済み） */
    val files: Map<String, Long>,
    private val config: (dir: File, threads: Int) -> OfflineModelConfig,
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "kanade-$dirName") }
    private var unloadTask: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var recognizer: OfflineRecognizer? = null

    fun dir(context: Context) = File(context.getExternalFilesDir("models"), dirName)

    fun isReady(context: Context) = readyCount(context) == files.size

    /** 取得済みのファイルの数（設定画面の表示用） */
    fun readyCount(context: Context) = files.count { (name, size) -> File(dir(context), name).let { it.exists() && it.length() == size } }

    fun downloadItems(context: Context) = files.map { (name, size) -> ModelDownloader.Item(fileUrl + name, File(dir(context), name), size) }

    /**
     * 選んだファイルを名前で見分けてアプリ内へ写す（複数まとめて選べる）。写せた数を返す。UI スレッドから呼ばないこと
     */
    fun importFrom(context: Context, uris: List<Uri>): Int {
        val d = dir(context).apply { mkdirs() }
        var count = 0
        for (uri in uris) {
            val name = displayName(context, uri) ?: continue
            // ブラウザが「tokens (1).txt」のように名前を変えることがあるので、知っている名前を含むかで見る
            val target = files.keys.firstOrNull { name == it || name.startsWith(it.substringBeforeLast('.')) } ?: continue
            val tmp = File(d, "$target.part")
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } } ?: continue
            if (tmp.length() == files.getValue(target)) {
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
     * モデルがなければ null
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
            }.getOrNull()?.let { tidy(it) }?.takeIf { it.isNotEmpty() }
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
        val c = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = WhisperModel.SAMPLE_RATE, featureDim = 80),
            modelConfig = config(dir(context), threads),
            decodingMethod = "greedy_search",
        )
        return runCatching { OfflineRecognizer(null, c) }.getOrNull()?.also { recognizer = it }
    }

    companion object {
        /**
         * 認識結果の書き方をそろえる。SenseVoice は語の間に空白を入れるので、日本語の文字に挟まれた空白は消す
         * （英単語の間の空白は残す）。半角の ? ! は全角にする
         */
        fun tidy(text: String): String {
            val t = text.trim()
            val out = StringBuilder()
            for ((i, c) in t.withIndex()) {
                if (c == ' ' || c == '　') {
                    val prev = out.lastOrNull()
                    val next = t.getOrNull(i + 1)
                    if (prev == null || next == null || prev == ' ' || !(prev.code < 0x80 && next.code < 0x80)) continue
                }
                out.append(
                    when (c) {
                        '?' -> if (out.lastOrNull()?.let { it.code >= 0x80 } == true) '？' else c
                        '!' -> if (out.lastOrNull()?.let { it.code >= 0x80 } == true) '！' else c
                        else -> c
                    },
                )
            }
            return out.toString()
        }

        /**
         * ReazonSpeech の k2 zipformer（csukuangfj/reazonspeech-k2-v2-ja-en の int8、約 73MB）。
         * 速い（手元の遅い VM でも 5 秒の音声に 0.2 秒）ので、話しながらの途中表示に使う。句読点は付かない
         */
        val reazon = SherpaRecognizer(
            "ReazonSpeech", "reazonspeech",
            "https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/main/",
            mapOf(
                "encoder-epoch-35-avg-1.int8.onnx" to 70_876_409L,
                "decoder-epoch-35-avg-1.int8.onnx" to 1_308_690L,
                "joiner-epoch-35-avg-1.int8.onnx" to 1_033_417L,
                "tokens.txt" to 26_631L,
            ),
        ) { d, threads ->
            OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(d, "encoder-epoch-35-avg-1.int8.onnx").path,
                    decoder = File(d, "decoder-epoch-35-avg-1.int8.onnx").path,
                    joiner = File(d, "joiner-epoch-35-avg-1.int8.onnx").path,
                ),
                tokens = File(d, "tokens.txt").path,
                numThreads = threads,
                modelType = "transducer",
            )
        }

        /**
         * NVIDIA parakeet-tdt_ctc-0.6b-ja（CTC の側、sherpa-onnx の int8、約 656MB）。日本語専用で、JSUT の文字誤り率 6.5%
         * （whisper small は 14.2%、large-v3 は 7.1%）。句読点が付く。CC-BY-4.0
         */
        val parakeet = SherpaRecognizer(
            "parakeet-ja", "parakeet-ja",
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8/resolve/bef18eb066808c90bd0f5df5be685767b0732de8/",
            mapOf(
                "model.int8.onnx" to 655_542_604L,
                "tokens.txt" to 28_557L,
            ),
        ) { d, threads ->
            OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = File(d, "model.int8.onnx").path),
                tokens = File(d, "tokens.txt").path,
                numThreads = threads,
            )
        }

        /**
         * SenseVoice-Small（FunAudioLLM、sherpa-onnx の int8 版 2024-07-17、約 239MB）。中・英・日・韓・広東語。
         * 非自己回帰で速い。逆テキスト正規化を入れると句読点が付く（2025-09-09 版は日本語の見本が中国語に崩れ、句読点も付かなかった）
         */
        val senseVoice = SherpaRecognizer(
            "SenseVoice", "sensevoice-2024-07-17",
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/",
            mapOf(
                "model.int8.onnx" to 239_233_841L,
                "tokens.txt" to 315_894L,
            ),
        ) { d, threads ->
            OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = File(d, "model.int8.onnx").path,
                    language = "ja",
                    useInverseTextNormalization = true,
                ),
                tokens = File(d, "tokens.txt").path,
                numThreads = threads,
            )
        }
    }
}
