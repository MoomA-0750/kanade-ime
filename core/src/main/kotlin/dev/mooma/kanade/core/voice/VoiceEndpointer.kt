package dev.mooma.kanade.core.voice

/**
 * 聞き続けるモードで、話し始めと話し終わり（ひと区切り）を音の大きさから見つける。
 * 周りの音の大きさ（雑音の床）を追い、それより十分大きい音が続いたら話し始め、小さい音が [endSilenceMs] 続いたら話し終わり。
 * 長く話し続けたら [maxSpeechMs] で区切る（認識が重くならないように）。
 */
class VoiceEndpointer(
    private val endSilenceMs: Int = 700,
    private val startSpeechMs: Int = 150,
    private val maxSpeechMs: Int = 20_000,
) {
    enum class Event { START, END }

    var inSpeech = false
        private set
    private var noise = -1.0
    private var loudMs = 0
    private var quietMs = 0
    private var speechMs = 0

    fun reset() {
        inSpeech = false
        loudMs = 0
        quietMs = 0
        speechMs = 0
    }

    /** 長さ [ms] の音の RMS（-1〜1 の音で）を渡す。話し始め・話し終わりになったらそれを返す */
    fun feed(rms: Double, ms: Int): Event? {
        if (noise < 0) noise = rms
        val threshold = maxOf(MIN_THRESHOLD, noise * 3)
        val loud = rms > threshold
        if (!inSpeech) {
            // 話していない間だけ雑音の床を追う（下がるのは速く、上がるのはゆっくり）
            noise = if (rms < noise) noise * 0.7 + rms * 0.3 else noise * 0.97 + rms * 0.03
            loudMs = if (loud) loudMs + ms else 0
            if (loudMs >= startSpeechMs) {
                inSpeech = true
                quietMs = 0
                speechMs = loudMs
                return Event.START
            }
            return null
        }
        speechMs += ms
        quietMs = if (loud) 0 else quietMs + ms
        if (quietMs >= endSilenceMs || speechMs >= maxSpeechMs) {
            reset()
            return Event.END
        }
        return null
    }

    private companion object {
        /** これより小さい音は話し声とみなさない（静かな部屋で雑音の床が 0 に近いとき用） */
        const val MIN_THRESHOLD = 0.01
    }
}
