package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.voice.SpokenPunctuation
import dev.mooma.kanade.whisper.WhisperModel
import java.io.File
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine

/** 音声入力（録音キーを押している間だけ録音し、離したら文字にする） */
interface VoiceService {
    /**
     * 録音を始める。マイクが使えなければ false。話しながらの途中の文字は [onPartial] で返す（裏のスレッドから）。
     * [english] なら英語のモデル（[DesktopVoice.finalModelEn]）で文字にする（Linux 版で、Kanade 以外の入力方式のとき）
     */
    fun start(english: Boolean = false, onPartial: (String) -> Unit = {}): Boolean

    /** 録音を止めて文字にする。結果（文字にできなければ null）は裏のスレッドから [onText] で返す */
    fun stop(onText: (String?) -> Unit)

    /** 録音を止めて、文字にせずに捨てる（録音中の Esc） */
    fun cancel()
}

/**
 * マイク（javax.sound）での音声入力。確定の文は [finalModel]（whisper.cpp か sherpa-onnx のモデル。Android 版と同じ一覧）で作る。
 * モデルは最初に使うときに読み、5分使わなければ放す（Android 版と同じ）。話した句読点（「まる」→「。」）も同じく直す
 */
class DesktopVoice(
    /** whisper.cpp のネイティブ（kanade_whisper.dll） */
    private val library: File,
    /** モデルの置き場所（%LOCALAPPDATA%\Kanade\data） */
    private val data: File,
    private val log: (String) -> Unit,
    /** 話しながらの途中の文字（なければ出さない） */
    private val partial: ReazonPartial? = null,
) : VoiceService {
    @Volatile private var lastPartial: String? = null

    /** 話した句読点（「まる」→「。」）を直す */
    @Volatile var spokenPunctuation = true

    private fun spoken(text: String, english: Boolean) = if (spokenPunctuation && !english) SpokenPunctuation.apply(text) else text
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "whisper").apply { isDaemon = true } }
    private var unload: java.util.concurrent.ScheduledFuture<*>? = null
    /** 読んだモデル（英語か → モデルと読んだもの） */
    private val loaded = HashMap<Boolean, Pair<VoiceFinal, FinalRecognizer>>()
    /** 記録に出す、最後に使ったモデル */
    @Volatile private var lastModel: VoiceFinal? = null

    /** 確定の文に使うモデル（設定の voice_final）。変えたら、次に使うときに読み直す */
    @Volatile var finalModel: VoiceFinal = VoiceFinal.DEFAULT

    /** 英語の口述に使うモデル（設定の voice_final_en） */
    @Volatile var finalModelEn: VoiceFinal = VoiceFinal.DEFAULT_EN

    /** いまの録音を英語で文字にするか（[start] で決める） */
    @Volatile private var english = false

    @Volatile private var line: TargetDataLine? = null
    private var recordThread: Thread? = null
    private val samples = java.io.ByteArrayOutputStream()

    override fun start(english: Boolean, onPartial: (String) -> Unit): Boolean {
        if (line != null) return true
        this.english = english
        val format = AudioFormat(WhisperModel.SAMPLE_RATE.toFloat(), 16, 1, true, false)
        val l = runCatching { AudioSystem.getTargetDataLine(format).apply { open(format) } }.getOrElse {
            log("マイクを開けない: $it")
            return false
        }
        synchronized(samples) { samples.reset() }
        l.start()
        line = l
        recordThread = Thread({
            val buf = ByteArray(3200)
            while (line === l) {
                val n = l.read(buf, 0, buf.size)
                if (n > 0) synchronized(samples) { if (samples.size() < MAX_BYTES) samples.write(buf, 0, n) }
            }
        }, "record").apply { isDaemon = true; start() }
        // 録っているあいだにモデルを読んでおく（離してから待たせない）
        val en = english
        worker.execute { model(en) }
        lastPartial = null
        val partial = partial
        if (partial != null && partial.isReady) {
            Thread({
                while (line === l) {
                    Thread.sleep(PARTIAL_INTERVAL_MS)
                    if (line !== l) break
                    val pcm = snapshot(samples = PARTIAL_MAX_SECONDS * WhisperModel.SAMPLE_RATE)
                    val text = runCatching { partial.recognize(pcm) }.getOrNull() ?: continue
                    if (line !== l) break
                    lastPartial = text
                    onPartial(spoken(text, en))
                }
            }, "partial").apply { isDaemon = true; start() }
        }
        return true
    }

    override fun cancel() {
        val l = line ?: return
        line = null
        l.stop()
        l.close()
        recordThread?.join(500)
        synchronized(samples) { samples.reset() }
        log("録音を取り消した")
        worker.execute { scheduleUnload() }
    }

    override fun stop(onText: (String?) -> Unit) {
        val l = line ?: return onText(null)
        line = null
        l.stop()
        l.close()
        recordThread?.join(500)
        // 試験用: -Dkanade.voiceWav があれば、録った音の代わりにその wav を使う（声を出さずに通しで確かめる）
        val bytes = System.getProperty("kanade.voiceWav")?.let { File(it).readBytes().copyOfRange(44, File(it).length().toInt()) }
            ?: synchronized(samples) { samples.toByteArray() }
        val pcm = FloatArray(bytes.size / 2) { i ->
            ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f
        }
        val en = english
        worker.execute {
            val started = System.nanoTime()
            val text = runCatching { model(en)?.transcribe(pcm) }.onFailure { log("音声認識: $it") }.getOrNull()
            log("${lastModel?.id} ${pcm.size / WhisperModel.SAMPLE_RATE.toFloat()}秒 -> ${(System.nanoTime() - started) / 1_000_000}ms ${text ?: "（なし）"}")
            // 確定の文が作れなければ、話している途中に出していた文を使う（Android 版と同じ）
            onText((text ?: lastPartial)?.let { spoken(it, en) })
            scheduleUnload()
        }
    }

    /** 録っている途中の音声（後ろの [samples] サンプルまで） */
    private fun snapshot(samples: Int): FloatArray {
        val bytes = synchronized(this.samples) { this.samples.toByteArray() }
        val from = maxOf(0, bytes.size / 2 - samples)
        return FloatArray(bytes.size / 2 - from) { i ->
            val j = from + i
            ((bytes[2 * j].toInt() and 0xFF) or (bytes[2 * j + 1].toInt() shl 8)).toShort() / 32768f
        }
    }

    /** 音声ファイル（16kHz・モノラル・16bit の wav）を文字にする（確かめる用） */
    fun transcribeWav(wav: File): String? {
        val bytes = wav.readBytes()
        val data = bytes.copyOfRange(44, bytes.size)
        val pcm = FloatArray(data.size / 2) { i -> ((data[2 * i].toInt() and 0xFF) or (data[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
        return model(System.getProperty("kanade.voiceEnglish") != null)?.transcribe(pcm)
    }

    /**
     * 確定の文を作るモデルを読む。選んだものが入っていなければ既定のもの、それもなければ null（話している途中の文を使う）。
     * 英語と日本語を行き来しても読み直さないよう、読んだものは言語ごとに1つずつ持つ（使わなければ5分で全部放す）
     */
    private fun model(en: Boolean): FinalRecognizer? = synchronized(this) {
        val wanted = if (en) {
            finalModelEn.takeIf { it.isReady(data) } ?: VoiceFinal.DEFAULT_EN.takeIf { it.isReady(data) } ?: return null
        } else {
            finalModel.takeIf { it.isReady(data) } ?: VoiceFinal.DEFAULT.takeIf { it.isReady(data) } ?: return null
        }
        loaded[en]?.let { (model, recognizer) -> if (model == wanted) return recognizer }
        // 同じ言語で別のモデルに替えたなら、前のものは放す
        loaded.remove(en)?.second?.close()
        val started = System.nanoTime()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val recognizer = runCatching {
            if (wanted.kind == VoiceFinal.Kind.WHISPER) {
                System.load(library.absolutePath)
                WhisperModel.open(File(data, wanted.path).absolutePath, threads = threads)?.let { WhisperFinal(it, if (en) "en" else "ja") }
            } else {
                SherpaFinal.open(wanted, data, threads)
            }
        }.onFailure { log("${wanted.id} を読めない: $it") }.getOrNull() ?: return null
        loaded[en] = wanted to recognizer
        lastModel = wanted
        log("${wanted.id} を読んだ ${(System.nanoTime() - started) / 1_000_000}ms")
        return recognizer
    }

    private fun scheduleUnload() = synchronized(this) {
        unload?.cancel(false)
        unload = worker.schedule({
            synchronized(this) {
                loaded.values.forEach { it.second.close() }
                loaded.clear()
            }
        }, 5, java.util.concurrent.TimeUnit.MINUTES)
    }

    companion object {
        /** 途中の文字を作る間隔（Android 版と同じ） */
        private const val PARTIAL_INTERVAL_MS = 600L
        /** 途中の文字は後ろの何秒だけで作るか（長く話しても重くならないように） */
        private const val PARTIAL_MAX_SECONDS = 30

        /** 録れる長さの上限（2分） */
        private const val MAX_BYTES = WhisperModel.SAMPLE_RATE * 2 * 120

        fun libraryFile(): File = ZenzReranker.libraryFile().let { File(it.parentFile, System.mapLibraryName("kanade_whisper")) }
    }
}
