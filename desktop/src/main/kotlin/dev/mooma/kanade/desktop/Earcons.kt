package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.voice.VoiceEarcon
import java.util.concurrent.Executors
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent

/**
 * 録音を始めた・終えたときの効果音を鳴らす（javax.sound。Windows・Linux・macOS で同じ）。
 * 設定の voice_sounds で切れる。macOS では「ユーザインターフェイスのサウンドエフェクトを再生」を切っていたら鳴らさない
 */
class Earcons(private val log: (String) -> Unit = {}) {
    @Volatile var enabled = true
    private val player = Executors.newSingleThreadExecutor { Thread(it, "earcon").apply { isDaemon = true } }
    private val format = AudioFormat(VoiceEarcon.SAMPLE_RATE.toFloat(), 16, 1, true, false)
    @Volatile private var macUiSounds: Pair<Long, Boolean>? = null

    fun play(kind: VoiceEarcon.Kind) {
        if (!enabled) return
        player.execute {
            if (Os.isMac && !macUiSoundsOn()) return@execute
            runCatching {
                val pcm = VoiceEarcon.pcm(kind)
                val bytes = ByteArray(pcm.size * 2)
                for (i in pcm.indices) {
                    bytes[2 * i] = (pcm[i].toInt() and 0xFF).toByte()
                    bytes[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
                }
                val clip = AudioSystem.getClip()
                clip.addLineListener { if (it.type == LineEvent.Type.STOP) clip.close() }
                clip.open(format, bytes, 0, bytes.size)
                clip.start()
            }.onFailure { log("効果音を鳴らせない: $it") }
        }
    }

    /** macOS の「ユーザインターフェイスのサウンドエフェクトを再生」（com.apple.sound.uiaudio.enabled。無ければ入）。10 秒ごとに見直す */
    private fun macUiSoundsOn(): Boolean {
        val now = System.currentTimeMillis()
        macUiSounds?.let { (at, on) -> if (now - at < 10_000) return on }
        val on = runCatching {
            val p = ProcessBuilder("/usr/bin/defaults", "read", "-g", "com.apple.sound.uiaudio.enabled").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out != "0"
        }.getOrDefault(true)
        macUiSounds = now to on
        return on
    }
}
