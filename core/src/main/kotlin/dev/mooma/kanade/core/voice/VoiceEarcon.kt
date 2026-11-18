package dev.mooma.kanade.core.voice

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * 録音を始めた・終えたときの効果音（全部の版で同じ音）。ファイルを持たず、ここで合成する。
 * 始めるは上がる2音（ド→ソ、C6→G6）、終えるは同じ2音を逆向き（ソ→ド）。トグルのオン・オフと同じ形で、上がる＝始まる、下がる＝終わる。
 * 倍音は (1, 0.35, 0.12)、45ms＋70ms、先頭の無音なし・アタック 2ms・末尾は指数で減って 8ms で消える。
 * 毎回少し（±25 セント）ゆらして、何度も聞いても耳につかないようにする
 */
object VoiceEarcon {
    const val SAMPLE_RATE = 48_000
    private const val C6 = 1046.50
    private const val G6 = 1567.98
    /** 全体の大きさ。瞬時ラウドネスの最大が −27.5 LUFS 前後（確定・トグルの段 −24〜−28 の控えめな側）になるよう測って決めた */
    const val GAIN = 0.26

    enum class Kind { START, STOP }

    /** 16bit・モノラル・[SAMPLE_RATE] の PCM。[cents] は音程のゆらし（0 ならゆらさない） */
    fun pcm(kind: Kind, cents: Double = 0.0): ShortArray {
        val shift = Math.pow(2.0, cents / 1200)
        val notes = when (kind) {
            Kind.START -> listOf(C6 to 0.045, G6 to 0.070)
            Kind.STOP -> listOf(G6 to 0.045, C6 to 0.070)
        }
        val out = ArrayList<Short>()
        for ((f0, seconds) in notes) {
            val n = (seconds * SAMPLE_RATE).toInt()
            val f = f0 * shift
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                val attack = min(1.0, t / 0.002)
                // 音の終わりへ向けて減らし、最後の 8ms で 0 へ（つなぎ目でぷつっと鳴らないように）
                val decay = exp(-t / (seconds * 0.6))
                val tail = min(1.0, (n - i).toDouble() / (0.008 * SAMPLE_RATE))
                val wave = sin(2 * PI * f * t) + 0.35 * sin(2 * PI * 2 * f * t) + 0.12 * sin(2 * PI * 3 * f * t)
                val v = GAIN * attack * decay * tail * wave / 1.47
                out.add((v * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        return out.toShortArray()
    }

    /** 鳴らすたびのゆらし（±25 セント） */
    fun randomCents(): Double = (Math.random() * 2 - 1) * 25

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
