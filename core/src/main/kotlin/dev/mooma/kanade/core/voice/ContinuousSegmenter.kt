package dev.mooma.kanade.core.voice

/**
 * 聞き続けるモードの区切り。録った音を順に [feed] で渡すと、話し始め・ひと区切り（その音）・話さないままの時間切れを返す。
 * 時刻は渡した音の長さで数える（壁の時計を見ないので、音のファイルを流す試験が時刻に左右されない）。
 * 音は [FRAME_MS] ずつに切り直して [VoiceEndpointer] に大きさを渡すので、渡す塊の大きさで結果は変わらない。
 * 話していないあいだの音はためない（話し始めの直前の [preRollMs] だけ残す）
 */
class ContinuousSegmenter(
    private val sampleRate: Int = 16_000,
    /** これだけ話さなければ [Event.Idle] を返す（ms。0 なら返さない） */
    private val idleMs: Long,
    private val endpointer: VoiceEndpointer = VoiceEndpointer(),
    private val preRollMs: Int = 500,
) {
    sealed interface Event {
        /** 話し始めた */
        data object Start : Event
        /** ひと区切り話し終えた。[pcm] は話し始めの少し前からの音 */
        class Segment(val pcm: FloatArray) : Event
        /** [idleMs] のあいだ話さなかった（1回だけ返す。次に話すまでは返さない） */
        data object Idle : Event
    }

    private val frame = sampleRate * FRAME_MS / 1000
    private val preRoll = sampleRate * preRollMs / 1000

    /** ためている音（話し始めの前の少しと、話している途中） */
    private var buffer = FloatArray(sampleRate * 2)
    private var size = 0

    /** まだ大きさを見ていない端数の始まり（[buffer] の中の位置） */
    private var pending = 0

    /** 最後に話した（話し始め・話し終わり）ときから、話さずに流れた音の長さ */
    private var quietMs = 0L
    private var idleSent = false

    val inSpeech: Boolean get() = endpointer.inSpeech

    /** ためている音のサンプル数（話している途中の文字を、今の区切りだけで作るため） */
    fun bufferedSamples(): Int = size

    fun feed(chunk: FloatArray): List<Event> {
        val events = ArrayList<Event>(1)
        append(chunk)
        while (size - pending >= frame) {
            val from = pending
            pending += frame
            when (endpointer.feed(rms(from, pending), FRAME_MS)) {
                VoiceEndpointer.Event.START -> {
                    quietMs = 0
                    idleSent = false
                    events += Event.Start
                }
                VoiceEndpointer.Event.END -> {
                    quietMs = 0
                    idleSent = false
                    events += Event.Segment(buffer.copyOf(pending))
                    drop(pending)
                }
                null -> if (!endpointer.inSpeech) {
                    quietMs += FRAME_MS
                    // 話していない間の音は溜めない（話し始めの直前だけ残す）
                    if (pending > preRoll * 2) drop(pending - preRoll)
                    if (idleMs > 0 && !idleSent && quietMs > idleMs) {
                        idleSent = true
                        events += Event.Idle
                    }
                }
            }
        }
        return events
    }

    /** 終えるとき: 話している途中なら、そこまでの音（なければ null）。区切りの状態は始めに戻す */
    fun flush(): FloatArray? {
        val speaking = endpointer.inSpeech
        val pcm = if (speaking && size > 0) buffer.copyOf(size) else null
        endpointer.reset()
        size = 0
        pending = 0
        quietMs = 0
        idleSent = false
        return pcm
    }

    private fun append(chunk: FloatArray) {
        if (size + chunk.size > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + chunk.size))
        chunk.copyInto(buffer, size)
        size += chunk.size
    }

    /** 頭から [n] サンプルを捨てる */
    private fun drop(n: Int) {
        buffer.copyInto(buffer, 0, n, size)
        size -= n
        pending -= n
    }

    private fun rms(from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += buffer[i] * buffer[i].toDouble()
        return kotlin.math.sqrt(sum / (to - from))
    }

    companion object {
        /** 音の大きさを見る間隔（Android 版の continuousTick と同じ 100ms） */
        const val FRAME_MS = 100
    }
}
