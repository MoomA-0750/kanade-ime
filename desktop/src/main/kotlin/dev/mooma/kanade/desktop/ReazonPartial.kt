package dev.mooma.kanade.desktop

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * 話しながらの途中の文字（ReazonSpeech の k2 zipformer、sherpa-onnx）。Android 版の SherpaRecognizer.reazon と同じモデル。
 * 速い（数秒の音声に 0.1〜0.2 秒）ので、録っている途中の音声を繰り返し文字にする。句読点は付かない
 */
class ReazonPartial(private val dir: File, private val log: (String) -> Unit) {
    private var recognizer: OfflineRecognizer? = null
    private var failed = false

    val isReady: Boolean get() = FILES.all { File(dir, it).exists() }

    @Synchronized
    fun recognize(pcm: FloatArray): String? {
        val r = load() ?: return null
        val stream = r.createStream()
        // 前後に 0.5 秒の無音を足す。このモデルは音声がいきなり始まると、前の部分を丸ごと落とすことがある
        // （見本の 8.8 秒の音声で、そのままだと最後の文だけ、足すと全文。足しても落とす音声はある。docs/WINDOWS-PLAN.md）
        val padded = FloatArray(pcm.size + 2 * PAD).also { pcm.copyInto(it, PAD) }
        return try {
            stream.acceptWaveform(padded, SAMPLE_RATE)
            r.decode(stream)
            r.getResult(stream).text.trim().takeIf { it.isNotEmpty() }
        } finally {
            stream.release()
        }
    }

    private fun load(): OfflineRecognizer? {
        recognizer?.let { return it }
        if (failed || !isReady) return null
        val started = System.nanoTime()
        recognizer = runCatching {
            val model = OfflineModelConfig.builder()
                .setTransducer(
                    OfflineTransducerModelConfig.builder()
                        .setEncoder(File(dir, FILES[0]).path)
                        .setDecoder(File(dir, FILES[1]).path)
                        .setJoiner(File(dir, FILES[2]).path)
                        .build(),
                )
                .setTokens(File(dir, FILES[3]).path)
                .setNumThreads(4)
                .setModelType("transducer")
                .setDebug(false)
                .build()
            OfflineRecognizer(
                OfflineRecognizerConfig.builder()
                    .setOfflineModelConfig(model)
                    .setFeatureConfig(FeatureConfig.builder().setSampleRate(SAMPLE_RATE).setFeatureDim(80).build())
                    .setDecodingMethod("greedy_search")
                    .build(),
            )
        }.onFailure { log("ReazonSpeech を読めない: $it") }.getOrNull()
        failed = recognizer == null
        recognizer?.let { log("ReazonSpeech を読んだ ${(System.nanoTime() - started) / 1_000_000}ms") }
        return recognizer
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val PAD = SAMPLE_RATE / 2

        /** Android 版と同じファイル（csukuangfj/reazonspeech-k2-v2-ja-en の int8） */
        val FILES = listOf(
            "encoder-epoch-35-avg-1.int8.onnx", "decoder-epoch-35-avg-1.int8.onnx", "joiner-epoch-35-avg-1.int8.onnx", "tokens.txt",
        )
        const val URL = "https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/main/"
    }
}
