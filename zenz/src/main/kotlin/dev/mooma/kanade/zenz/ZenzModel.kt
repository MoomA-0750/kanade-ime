package dev.mooma.kanade.zenz

import dev.mooma.kanade.core.CharWidth
import dev.mooma.kanade.core.convert.LanguageModel
import java.io.Closeable

/**
 * zenz v3 系（v3.2 なら右文脈にも対応するが、まだ使わない）。プロンプトは
 * `<左文脈> <読み（カタカナ）> <出力></s>`（azooKey の ZenzPromptBuilder と同じ）。
 * llama.cpp の文脈は1つなので、[score]・[generate]・[close] は錠で順番にする（IME の裏のスレッドと設定画面の計測が
 * 同じモデルを同時に使って落ちたことがある）。[cancel] だけは錠を取らずに呼べる。
 */
class ZenzModel private constructor(private var handle: Long) : LanguageModel, Closeable {
    private val lock = Any()

    /** 変換のプロフィール（「プログラマ。技術の話が多い」など。v3 から。空なら付けない）。プロンプトの先頭に置く */
    @Volatile var profile: String = ""

    /** 推論に使うスレッドの数 */
    fun setThreads(threads: Int) = synchronized(lock) {
        if (handle != 0L) ZenzNative.setThreads(handle, threads)
    }

    override fun score(leftContext: String, reading: String, candidates: List<String>, rightContext: String): FloatArray? = synchronized(lock) {
        if (handle == 0L) return null
        val prompt = prompt(leftContext, reading, rightContext, profile).encodeToByteArray()
        return ZenzNative.score(handle, prompt, candidates.map { normalize(it).encodeToByteArray() }.toTypedArray())
    }

    override fun generate(leftContext: String, reading: String, rightContext: String): String? = synchronized(lock) {
        if (handle == 0L) return null
        val prompt = prompt(leftContext, reading, rightContext, profile).encodeToByteArray()
        // 出力はおおむね読みより短い。ずれても打ち切れるよう読みの長さ＋余裕を上限にする
        val bytes = ZenzNative.greedy(handle, prompt, reading.length + 8) ?: return null
        return bytes.decodeToString().takeIf { it.isNotEmpty() && '�' !in it }
    }

    override fun scoreReadings(leftContext: String, items: List<Pair<String, String>>): FloatArray? = synchronized(lock) {
        if (handle == 0L) return null
        // プロンプトは左文脈と読みの始まりの印まで。読み・変換の始まりの印・変換を候補として続ける
        val left = normalize(leftContext).takeLast(MAX_LEFT_CONTEXT)
        val prompt = (profilePart(profile) + (if (left.isEmpty()) "" else "\uEE02$left") + "\uEE00").encodeToByteArray()
        return ZenzNative.score(
            handle, prompt,
            items.map { (reading, output) -> (CharWidth.toKatakana(normalize(reading)) + "\uEE01" + normalize(output)).encodeToByteArray() }.toTypedArray(),
        )
    }

    override fun verify(leftContext: String, reading: String, candidate: String, rightContext: String): String? = synchronized(lock) {
        if (handle == 0L) return null
        val prompt = prompt(leftContext, reading, rightContext, profile).encodeToByteArray()
        val bytes = ZenzNative.verify(handle, prompt, normalize(candidate).encodeToByteArray()) ?: return null
        if (bytes.isEmpty() || bytes[0].toInt() == 0) return ""
        // トークンがバイトの途中で切れていたら（UTF-8 として読めなければ）、直す手がかりにしない
        return bytes.copyOfRange(1, bytes.size).decodeToString().takeIf { '\uFFFD' !in it } ?: ""
    }

    override fun cancel() {
        if (handle != 0L) ZenzNative.cancel(handle)
    }

    override fun close() = synchronized(lock) {
        if (handle != 0L) ZenzNative.free(handle)
        handle = 0L
    }

    companion object {
        const val MAX_LEFT_CONTEXT = 40

        /** プロフィールの長さの上限（azooKey の ZenzPromptBuilder と同じ） */
        const val MAX_PROFILE = 25

        /** [modelPath] の GGUF を読む。ネイティブライブラリは読み込み済みであること */
        fun open(modelPath: String, threads: Int = 4, nCtx: Int = 512): ZenzModel? {
            val h = ZenzNative.init(modelPath.encodeToByteArray(), threads, nCtx)
            return if (h == 0L) null else ZenzModel(h)
        }

        /** 右の文脈（カーソルの後ろの文）は v3.2 から。左の文脈のあとに置く（azooKey の ZenzPromptBuilder と同じ並び） */
        fun prompt(leftContext: String, reading: String, rightContext: String = "", profile: String = ""): String {
            val left = normalize(leftContext).takeLast(MAX_LEFT_CONTEXT)
            val right = normalize(rightContext).take(MAX_LEFT_CONTEXT)
            val context = (if (left.isEmpty()) "" else "$left") + (if (right.isEmpty()) "" else "$right")
            return profilePart(profile) + context + "" + CharWidth.toKatakana(normalize(reading)) + ""
        }

        /** プロフィールは左文脈より前に置く（v3 の並び: プロフィール・話題・文体・設定・左文脈・右文脈・入力） */
        fun profilePart(profile: String): String = normalize(profile.trim()).take(MAX_PROFILE).let { if (it.isEmpty()) "" else "\uEE03$it" }

        /** トークナイザは半角スペースと改行を [UNK] にするので、全角スペースに替え、改行は消す */
        fun normalize(text: String) = text.replace(' ', '　').replace("\n", "").replace("\r", "")
    }
}
