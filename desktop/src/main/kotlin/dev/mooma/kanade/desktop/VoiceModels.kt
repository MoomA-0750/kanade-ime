package dev.mooma.kanade.desktop

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import dev.mooma.kanade.whisper.WhisperModel
import java.io.Closeable
import java.io.File

/**
 * 確定の文を作る音声認識のモデル（Android 版の VoiceCatalog と同じ id・同じファイル）。置き場所は %LOCALAPPDATA%\Kanade\data。
 * 落とすのは設定の画面（windows/settings の VoiceModels.cs）。一覧を変えるときは両方を直す
 */
enum class VoiceFinal(val id: String, val path: String, val files: List<String>, val kind: Kind) {
    PARAKEET("parakeet", "parakeet-ja", listOf("model.int8.onnx", "tokens.txt"), Kind.NEMO),
    REAZON("reazon", "reazonspeech", ReazonPartial.FILES, Kind.TRANSDUCER),
    SENSEVOICE("sensevoice", "sensevoice-2024-07-17", listOf("model.int8.onnx", "tokens.txt"), Kind.SENSEVOICE),
    WHISPER_SMALL("whisper-small", "ggml-small-q5_1.bin", emptyList(), Kind.WHISPER),
    WHISPER_KOTOBA("whisper-kotoba", "ggml-kotoba-whisper-v2.0-q5_0.bin", emptyList(), Kind.WHISPER),
    WHISPER_TURBO("whisper-turbo", "ggml-large-v3-turbo-q5_0.bin", emptyList(), Kind.WHISPER),
    ;

    enum class Kind { WHISPER, TRANSDUCER, NEMO, SENSEVOICE }

    fun isReady(data: File): Boolean = if (kind == Kind.WHISPER) File(data, path).exists() else files.all { File(File(data, path), it).exists() }

    companion object {
        /** Windows 版の既定（配布物に入っている whisper small） */
        val DEFAULT = WHISPER_SMALL

        fun of(id: String?): VoiceFinal = entries.find { it.id == id } ?: DEFAULT
    }
}

/** 確定の文を作るもの（読んだモデル1つ） */
interface FinalRecognizer : Closeable {
    fun transcribe(pcm: FloatArray): String?
}

class WhisperFinal(private val model: WhisperModel) : FinalRecognizer {
    override fun transcribe(pcm: FloatArray) = model.transcribe(pcm)
    override fun close() = model.close()
}

/** sherpa-onnx のモデル（parakeet・ReazonSpeech・SenseVoice）。Android 版の SherpaRecognizer と同じ設定 */
class SherpaFinal(private val recognizer: OfflineRecognizer) : FinalRecognizer {
    override fun transcribe(pcm: FloatArray): String? {
        if (WhisperModel.isSilent(pcm)) return null
        val stream = recognizer.createStream()
        return try {
            // ReazonSpeech と同じく、前後に少し無音を足す（音声がいきなり始まると前を落とすことがある）
            val pad = 8000
            stream.acceptWaveform(FloatArray(pcm.size + 2 * pad).also { pcm.copyInto(it, pad) }, 16000)
            recognizer.decode(stream)
            tidy(recognizer.getResult(stream).text).takeIf { it.isNotEmpty() }
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        fun open(model: VoiceFinal, data: File, threads: Int): SherpaFinal {
            val d = File(data, model.path)
            val builder = OfflineModelConfig.builder().setTokens(File(d, "tokens.txt").path).setNumThreads(threads).setDebug(false)
            when (model.kind) {
                VoiceFinal.Kind.TRANSDUCER -> builder.setModelType("transducer").setTransducer(
                    OfflineTransducerModelConfig.builder()
                        .setEncoder(File(d, ReazonPartial.FILES[0]).path)
                        .setDecoder(File(d, ReazonPartial.FILES[1]).path)
                        .setJoiner(File(d, ReazonPartial.FILES[2]).path)
                        .build(),
                )
                VoiceFinal.Kind.NEMO -> builder.setNemo(OfflineNemoEncDecCtcModelConfig.builder().setModel(File(d, "model.int8.onnx").path).build())
                VoiceFinal.Kind.SENSEVOICE -> builder.setSenseVoice(
                    OfflineSenseVoiceModelConfig.builder()
                        .setModel(File(d, "model.int8.onnx").path)
                        .setLanguage("ja")
                        .setInverseTextNormalization(true)
                        .build(),
                )
                VoiceFinal.Kind.WHISPER -> error("whisper は WhisperFinal で")
            }
            val config = OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(builder.build())
                .setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build())
                .setDecodingMethod("greedy_search")
                .build()
            return SherpaFinal(OfflineRecognizer(config))
        }

        /**
         * 書き方をそろえる（Android 版の SherpaRecognizer.tidy と同じ）。SenseVoice は語の間に空白を入れるので、
         * 日本語の文字に挟まれた空白は消す（英単語の間の空白は残す）。半角の ? ! は全角にする
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
    }
}
