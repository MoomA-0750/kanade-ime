package dev.mooma.kanade

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import androidx.annotation.StringRes
import dev.mooma.kanade.whisper.WhisperModel
import java.io.File

/**
 * 音声認識のモデル。APK には入れない。設定画面の「ダウンロード」で落とす（[ModelDownloader]）か、
 * 手元のファイルを選んでアプリ内へ写す
 */
enum class VoiceModel(@StringRes val labelRes: Int, val file: String, val url: String, val bytes: Long) {
    SMALL(
        R.string.voice_model_label_whisper_small, "ggml-small-q5_1.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin", 190_085_487L,
    ),
    KOTOBA(
        R.string.voice_model_label_kotoba_whisper, "ggml-kotoba-whisper-v2.0-q5_0.bin",
        "https://huggingface.co/kotoba-tech/kotoba-whisper-v2.0-ggml/resolve/main/ggml-kotoba-whisper-v2.0-q5_0.bin", 537_819_875L,
    ),
    TURBO(
        R.string.voice_model_label_whisper_turbo, "ggml-large-v3-turbo-q5_0.bin",
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin", 574_041_195L,
    );

    fun path(context: Context): File = File(context.getExternalFilesDir("models"), file)

    /** 取得済み（大きさが合っている） */
    fun isReady(context: Context): Boolean = path(context).let { it.exists() && it.length() == bytes }

    /**
     * 選んだファイル（手元に落としてあるもの）をアプリ内へ写す。大きさが合わなければ写さずに false。
     * 時間がかかるので UI スレッドから呼ばないこと
     */
    fun importFrom(context: Context, uri: Uri, progress: (Long) -> Unit): Boolean {
        val dest = path(context)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    progress(total)
                }
            }
        } ?: return false
        if (tmp.length() != bytes) {
            tmp.delete()
            return false
        }
        return tmp.renameTo(dest)
    }
}

/**
 * 押している間だけ録音し、離したら whisper で文字にする。モデルは初めて使うときに読み、そのまま持っておく。
 * 録音・認識は専用のスレッドで行い、結果は [onResult] でメインスレッド以外から返す（呼ぶ側で UI スレッドへ）。
 */
object VoiceInput {
    private val worker = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "kanade-whisper") }
    private var unloadTask: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var model: WhisperModel? = null
    /** 読んでいるモデルのファイル（同梱の whisper も、取り込んだ whisper.cpp のモデルも、ファイルで見分ける） */
    @Volatile private var loadedFile: File? = null
    @Volatile private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    private val samples = ShortArrayBuilder()
    /** 録ってある音声の頭が変わるたびに増える数（取り出し・切り詰め。逐次認識が流れを始め直す目印） */
    @Volatile var epoch = 0
        private set

    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 試験用の音声を流しているか（[INJECT_FILE]） */
    @Volatile private var injecting = false

    val isRecording: Boolean get() = recorder != null || injecting

    /** 録音を始める。権限がなければ false */
    @SuppressLint("MissingPermission")
    fun start(context: Context): Boolean {
        if (isRecording) return true
        if (!hasPermission(context)) return false
        if (startInjection(context)) return true
        val min = AudioRecord.getMinBufferSize(WhisperModel.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, WhisperModel.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, WhisperModel.SAMPLE_RATE),
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        synchronized(samples) {
            samples.clear()
            epoch++
        }
        r.startRecording()
        recorder = r
        recordThread = Thread({
            val buf = ShortArray(1600)
            while (recorder === r) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) synchronized(samples) { if (samples.size < MAX_SAMPLES) samples.append(buf, n) }
            }
        }, "kanade-record").also { it.start() }
        return true
    }

    /**
     * 試験用: デバッグ版で files/[INJECT_FILE]（16kHz・モノラル・16bit の wav）があれば、マイクの代わりにそれを実時間で流す
     * （エミュレータにはマイクの音が入らないので、話しながらの途中表示を確かめるため）。流し終えたら無音を流す
     */
    private fun startInjection(context: Context): Boolean {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        val file = File(context.filesDir, INJECT_FILE)
        if (!file.exists()) return false
        val bytes = file.readBytes()
        val pcm = ShortArray(maxOf(0, (bytes.size - 44) / 2)) { ((bytes[44 + it * 2].toInt() and 0xff) or (bytes[45 + it * 2].toInt() shl 8)).toShort() }
        synchronized(samples) {
            samples.clear()
            epoch++
        }
        injecting = true
        recordThread = Thread({
            val chunk = 1600
            var pos = 0
            val silence = ShortArray(chunk)
            while (injecting) {
                Thread.sleep(100)
                val n = minOf(chunk, pcm.size - pos)
                synchronized(samples) {
                    if (samples.size < MAX_SAMPLES) {
                        if (n > 0) samples.append(pcm.copyOfRange(pos, pos + n), n) else samples.append(silence, chunk)
                    }
                }
                if (n > 0) pos += n
            }
        }, "kanade-record").also { it.start() }
        return true
    }

    /** 録音中の、ここまでの音声（話しながら途中の文字を出すため） */
    fun snapshot(): FloatArray = synchronized(samples) { WhisperModel.pcm16ToFloat(samples.array, samples.size) }

    /**
     * 逐次認識に流す、[from] サンプル目からの音声。[fromEpoch] のあとに頭が変わっていたら（取り出し・切り詰め）、
     * 頭から全部を返す。返すのは (今の epoch, 音声, 次に渡す from)
     */
    fun since(fromEpoch: Int, from: Int): Triple<Int, FloatArray, Int> = synchronized(samples) {
        val start = if (fromEpoch == epoch) minOf(from, samples.size) else 0
        val n = samples.size - start
        val out = FloatArray(n) { samples.array[start + it] / 32768f }
        Triple(epoch, out, samples.size)
    }

    /** 録ってある音声の長さ（サンプル数） */
    fun sampleCount(): Int = synchronized(samples) { samples.size }

    /** [from]〜[to] サンプルの音の大きさ（RMS、-1〜1 の音で） */
    fun rms(from: Int, to: Int): Double = synchronized(samples) {
        val end = minOf(to, samples.size)
        if (end <= from) return 0.0
        var sum = 0.0
        for (i in from until end) {
            val v = samples.array[i] / 32768.0
            sum += v * v
        }
        kotlin.math.sqrt(sum / (end - from))
    }

    /** 録音は続けたまま、ここまでの音声を取り出して空にする（聞き続けるモードのひと区切り） */
    fun take(): FloatArray = synchronized(samples) {
        val pcm = WhisperModel.pcm16ToFloat(samples.array, samples.size)
        samples.clear()
        epoch++
        pcm
    }

    /** 録音は続けたまま、最後の [keep] サンプルだけ残す（話していない間の音を溜めない。話し始めの直前は残す） */
    fun trimTo(keep: Int) = synchronized(samples) {
        if (samples.size > keep) epoch++
        samples.keepLast(keep)
    }

    /** 録音をやめて、録った音声を返す（録音していなければ null） */
    fun stopRecording(): FloatArray? {
        if (injecting) {
            injecting = false
            recordThread?.join(500)
            recordThread = null
            return snapshot()
        }
        val r = recorder ?: return null
        recorder = null
        recordThread?.join(500)
        recordThread = null
        runCatching { r.stop() }
        r.release()
        return snapshot()
    }

    /** 録音をやめて捨てる */
    fun cancel() {
        if (injecting) {
            injecting = false
            recordThread?.join(500)
            recordThread = null
            model?.cancel()
            return
        }
        val r = recorder ?: return
        recorder = null
        recordThread?.join(500)
        recordThread = null
        runCatching { r.stop() }
        r.release()
        model?.cancel()
    }

    /** whisper で音声を文字にする（設定画面の試験からも使う）。[onResult] には文字（失敗・空なら null）と、認識にかかった ms を渡す */
    fun transcribe(context: Context, pcm: FloatArray, modelFile: File, threads: Int, onResult: (String?, Long) -> Unit) {
        worker.execute {
            val m = load(modelFile, threads)
            if (m == null || pcm.size < WhisperModel.SAMPLE_RATE / 4) {
                onResult(null, 0)
                return@execute
            }
            val t = System.nanoTime()
            val text = runCatching { m.transcribe(pcm) }.getOrNull()?.takeIf { it.isNotBlank() }
            onResult(text, (System.nanoTime() - t) / 1_000_000)
            scheduleUnload()
        }
    }

    /**
     * しばらく使わなければモデルを放す（数百 MB あり、IME のプロセスが裏で落とされやすくなるため）。
     * 次に使うときに読み直す（small で 0.2 秒ほど）
     */
    private fun scheduleUnload() {
        unloadTask?.cancel(false)
        unloadTask = worker.schedule({
            model?.close()
            model = null
            loadedFile = null
        }, IDLE_UNLOAD_MINUTES, java.util.concurrent.TimeUnit.MINUTES)
    }

    /** モデルを読む（別のファイルに変わっていたら読み直す）。worker のスレッドから呼ぶ */
    private fun load(file: File, threads: Int): WhisperModel? {
        if (loadedFile == file && model != null) return model
        model?.close()
        model = null
        loadedFile = null
        if (!file.exists()) return null
        return runCatching {
            System.loadLibrary("kanade_whisper")
            WhisperModel.open(file.absolutePath, threads)
        }.getOrNull()?.also {
            model = it
            loadedFile = file
        }
    }

    /** これだけ使わなければモデルを放す */
    const val IDLE_UNLOAD_MINUTES = 5L

    /** 試験用にマイクの代わりに流す音声（デバッグ版だけ） */
    const val INJECT_FILE = "voice-inject.wav"

    /** 60 秒で録音を打ち切る */
    private const val MAX_SAMPLES = WhisperModel.SAMPLE_RATE * 60

    private class ShortArrayBuilder {
        var array = ShortArray(WhisperModel.SAMPLE_RATE * 5)
        var size = 0
        fun clear() {
            size = 0
        }

        fun keepLast(keep: Int) {
            if (size <= keep) return
            System.arraycopy(array, size - keep, array, 0, keep)
            size = keep
        }

        fun append(buf: ShortArray, n: Int) {
            if (size + n > array.size) array = array.copyOf(maxOf(array.size * 2, size + n))
            System.arraycopy(buf, 0, array, size, n)
            size += n
        }
    }
}
