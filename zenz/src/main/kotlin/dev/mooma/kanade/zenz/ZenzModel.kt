package dev.mooma.kanade.zenz

import dev.mooma.kanade.core.CharWidth
import dev.mooma.kanade.core.convert.LanguageModel
import java.io.Closeable

/**
 * zenz v3 系（v3.2 なら右文脈にも対応するが、まだ使わない）。プロンプトは
 * `<左文脈> <読み（カタカナ）> <出力></s>`（azooKey の ZenzPromptBuilder と同じ）。
 * 1本のスレッドから呼ぶこと。[cancel] だけは別スレッドから呼べる。
 */
class ZenzModel private constructor(private var handle: Long) : LanguageModel, Closeable {

    override fun score(leftContext: String, reading: String, candidates: List<String>): FloatArray? {
        check(handle != 0L)
        val prompt = prompt(leftContext, reading).encodeToByteArray()
        return ZenzNative.score(handle, prompt, candidates.map { normalize(it).encodeToByteArray() }.toTypedArray())
    }

    override fun generate(leftContext: String, reading: String): String? {
        check(handle != 0L)
        val prompt = prompt(leftContext, reading).encodeToByteArray()
        // 出力はおおむね読みより短い。ずれても打ち切れるよう読みの長さ＋余裕を上限にする
        val bytes = ZenzNative.greedy(handle, prompt, reading.length + 8) ?: return null
        return bytes.decodeToString().takeIf { it.isNotEmpty() && '�' !in it }
    }

    override fun cancel() {
        if (handle != 0L) ZenzNative.cancel(handle)
    }

    override fun close() {
        if (handle != 0L) ZenzNative.free(handle)
        handle = 0L
    }

    companion object {
        const val MAX_LEFT_CONTEXT = 40

        /** [modelPath] の GGUF を読む。ネイティブライブラリは読み込み済みであること */
        fun open(modelPath: String, threads: Int = 4, nCtx: Int = 512): ZenzModel? {
            val h = ZenzNative.init(modelPath.encodeToByteArray(), threads, nCtx)
            return if (h == 0L) null else ZenzModel(h)
        }

        fun prompt(leftContext: String, reading: String): String {
            val left = normalize(leftContext).takeLast(MAX_LEFT_CONTEXT)
            val context = if (left.isEmpty()) "" else "$left"
            return context + "" + CharWidth.toKatakana(normalize(reading)) + ""
        }

        /** トークナイザは半角スペースと改行を [UNK] にするので、全角スペースに替え、改行は消す */
        fun normalize(text: String) = text.replace(' ', '　').replace("\n", "").replace("\r", "")
    }
}
