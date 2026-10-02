package dev.mooma.kanade.whisper

import java.io.Closeable

/** native/whisper/whisper_bridge.cpp の入口。ライブラリの読み込みは呼ぶ側が先に済ませる */
internal object WhisperNative {
    external fun init(modelPath: ByteArray, threads: Int): Long
    external fun free(handle: Long)
    external fun cancel(handle: Long)
    external fun transcribe(handle: Long, pcm: FloatArray, language: ByteArray, prompt: ByteArray): ByteArray?
}

/**
 * 音声認識（whisper.cpp）。[transcribe]・[close] は錠で順番にする。[cancel] だけは錠を取らずに呼べる。
 */
class WhisperModel private constructor(private var handle: Long) : Closeable {
    private val lock = Any()

    /** 16kHz・モノラル・-1〜1 の音声を文字にする。ほぼ無音・中断・失敗・幻聴（よく出る決まり文句）は null */
    fun transcribe(pcm: FloatArray, language: String = "ja", prompt: String = DEFAULT_PROMPT): String? = synchronized(lock) {
        if (handle == 0L) return null
        if (isSilent(pcm)) return null
        val bytes = WhisperNative.transcribe(handle, pcm, language.encodeToByteArray(), prompt.encodeToByteArray()) ?: return null
        val text = bytes.decodeToString().trim()
        return text.takeIf { it.isNotEmpty() && it.trimEnd('。', '.', '!', '！') !in HALLUCINATIONS }
    }

    fun cancel() {
        if (handle != 0L) WhisperNative.cancel(handle)
    }

    override fun close() = synchronized(lock) {
        if (handle != 0L) WhisperNative.free(handle)
        handle = 0L
    }

    companion object {
        const val SAMPLE_RATE = 16000

        /** 句読点を付けた日本語の書き方を促す手本（whisper はプロンプトの文体に寄る） */
        const val DEFAULT_PROMPT = "以下は、日本語の話し言葉を、句読点を付けて書き起こしたものです。"

        fun open(modelPath: String, threads: Int = 4): WhisperModel? {
            val h = WhisperNative.init(modelPath.encodeToByteArray(), threads)
            return if (h == 0L) null else WhisperModel(h)
        }

        /**
         * whisper が無音や雑音から作りやすい決まり文句（動画の字幕で学習したため）。これだけが出たら捨てる。
         * 末尾の句点は外して比べる
         */
        val HALLUCINATIONS = setOf(
            "ご視聴ありがとうございました", "ご視聴ありがとうございます", "ありがとうございました",
            "チャンネル登録よろしくお願いします", "チャンネル登録お願いします", "おやすみなさい",
            "字幕は視聴者によって作成されました", "Thank you for watching", "Thanks for watching",
        )

        /** ほぼ無音か（一番大きい音が小さく、平均の音量も小さい） */
        fun isSilent(pcm: FloatArray): Boolean {
            if (pcm.isEmpty()) return true
            var peak = 0f
            var sum = 0.0
            for (v in pcm) {
                val a = kotlin.math.abs(v)
                if (a > peak) peak = a
                sum += v * v
            }
            val rms = kotlin.math.sqrt(sum / pcm.size)
            return peak < 0.02f && rms < 0.003
        }

        /** 16bit PCM（リトルエンディアン）を -1〜1 の float にする */
        fun pcm16ToFloat(pcm: ShortArray, length: Int = pcm.size): FloatArray = FloatArray(length) { pcm[it] / 32768f }
    }
}
