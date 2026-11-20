package dev.mooma.kanade.core.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * 音声入力の効果音（全部の版で同じ音）。ファイルを持たず、ここで合成する。
 * 音は UI の音カタログ（kasane の sound/）で本人が決めた voice のパラメータ（マリンバ・C5 基準・乾いた音）を、
 * Web Audio の式（kasane の sound.js の `render`）のとおりに書き起こしたもの:
 *  - [Kind.START_TAP] 一回押しで認識を始めた（約 305ms）
 *  - [Kind.START_HOLD] 長押しで認識を始めた（約 603ms の余韻）
 *  - [Kind.DONE_TEXT] 認識が終わり、文字が入った（G5、約 603ms）
 *  - [Kind.DONE_EMPTY] 認識が終わったが、文字がなかった（C5→F4 の下行2音）
 * 各音: 立ち上がり 1ms、減衰（−60dB までの ms）、帯域 120Hz〜lowpass、大きさ −16dB、ゆらしなし
 */
object VoiceEarcon {
    const val SAMPLE_RATE = 48_000

    enum class Kind { START_TAP, START_HOLD, DONE_TEXT, DONE_EMPTY }

    /** 音の形（カタログの voice）。[notes] は基準 [f] からの半音 */
    private class Voice(val f: Double, val notes: List<Double>, val gap: Double, val decayMs: Double, val lowpass: Double)

    private const val ATTACK_MS = 1.0
    private const val HIGHPASS = 120.0
    private const val VOL_DB = -16.0
    private val PARTIALS = listOf(1.0 to 1.0, 3.93 to 0.28, 9.2 to 0.06) // マリンバ

    private fun voice(kind: Kind) = when (kind) {
        Kind.START_TAP -> Voice(523.0, listOf(0.0), 0.101, 304.0, 5720.0)
        Kind.START_HOLD -> Voice(523.0, listOf(0.0), 0.101, 513.0, 5720.0)
        Kind.DONE_TEXT -> Voice(523.0, listOf(6.8), 0.083, 602.0, 5743.0)
        Kind.DONE_EMPTY -> Voice(523.0, listOf(0.0, -6.8), 0.083, 304.0, 5743.0)
    }

    /** 16bit・モノラル・[SAMPLE_RATE] の PCM。[cents] は音程のずらし（試験用。0 ならそのまま） */
    fun pcm(kind: Kind, cents: Double = 0.0): ShortArray {
        val v = voice(kind)
        val attack = ATTACK_MS / 1000
        val decay = v.decayMs / 1000
        // 立ち上がり＋減衰（−60dB）で切る（カタログが示す長さ。Web Audio の余白 1.3 倍は聞こえない）。最後の 8ms で 0 へ
        val noteSeconds = attack + decay + 0.008
        val total = ((v.notes.size - 1) * v.gap + noteSeconds).let { (it * SAMPLE_RATE).toInt() + 1 }
        val mix = DoubleArray(total)
        val sum = PARTIALS.sumOf { it.second }
        v.notes.forEachIndexed { i, semi ->
            val start = (i * v.gap * SAMPLE_RATE).toInt()
            val f = v.f * Math.pow(2.0, (semi * 100 + cents) / 1200)
            val n = (noteSeconds * SAMPLE_RATE).toInt()
            PARTIALS.forEachIndexed { k, (ratio, amp) ->
                val tau = (if (k == 0) decay else decay / (1 + 0.7 * k)) / 6.9
                for (j in 0 until n) {
                    val t = j.toDouble() / SAMPLE_RATE
                    // 立ち上がり attack 秒で 1 へ、そのあと指数で減る（Web Audio の linearRamp と setTarget）
                    val env = if (t < attack) t / attack else exp(-(t - attack) / tau)
                    // 音の終わりでぷつっと鳴らないよう、最後の 8ms で 0 へ
                    val fade = min(1.0, (n - j).toDouble() / (0.008 * SAMPLE_RATE))
                    mix[start + j] += amp / sum * env * fade * sin(2 * PI * f * ratio * t)
                }
            }
        }
        val hp = Biquad.highpass(HIGHPASS)
        val lp = Biquad.lowpass(v.lowpass)
        val gain = Math.pow(10.0, VOL_DB / 20)
        return ShortArray(total) { (lp.run(hp.run(mix[it])) * gain * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /** Web Audio の BiquadFilterNode（Q は 0.7 dB）と同じ 2 次フィルター */
    private class Biquad(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        private val b0 = b0 / a0; private val b1 = b1 / a0; private val b2 = b2 / a0
        private val a1 = a1 / a0; private val a2 = a2 / a0
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

        fun run(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }

        companion object {
            private const val Q_DB = 0.7
            private fun parts(hz: Double): Triple<Double, Double, Double> {
                val w = 2 * PI * hz / SAMPLE_RATE
                return Triple(cos(w), sin(w) / (2 * Math.pow(10.0, Q_DB / 20)), w)
            }
            fun lowpass(hz: Double): Biquad {
                val (c, alpha, _) = parts(hz)
                return Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }
            fun highpass(hz: Double): Biquad {
                val (c, alpha, _) = parts(hz)
                return Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }
        }
    }

    /** WAV（試験と、音を確かめるため） */
    fun wav(samples: ShortArray): ByteArray {
        val data = samples.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        for (s in samples) b.putShort(s)
        return b.array()
    }
}
