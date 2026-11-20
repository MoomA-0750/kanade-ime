package dev.mooma.kanade.core.voice

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class VoiceEarconTest {
    @Test
    fun shortQuietAndStartsAtOnce() {
        for (kind in VoiceEarcon.Kind.entries) {
            val pcm = VoiceEarcon.pcm(kind)
            val ms = pcm.size * 1000 / VoiceEarcon.SAMPLE_RATE
            assertTrue(ms in 300..700, "$kind の長さ $ms ms")
            // 先頭の無音は 5ms 以内（アタック 1ms で立ち上がる）
            val firstLoud = pcm.indexOfFirst { abs(it.toInt()) > 100 }
            assertTrue(firstLoud in 0..(VoiceEarcon.SAMPLE_RATE / 200), "$kind の先頭の無音 $firstLoud")
            // ピークは −1dBFS より十分小さい
            assertTrue(pcm.maxOf { abs(it.toInt()) } < 32767 * 0.2, "$kind のピーク")
            // 最後は 0 に戻る（ぷつっと鳴らない）
            assertTrue(abs(pcm.last().toInt()) < 50, "$kind の終わり")
        }
        // 確かめる用に書き出す（-Dkanade.earconDir=…）
        System.getProperty("kanade.earconDir")?.let { dir ->
            File(dir).mkdirs()
            for (kind in VoiceEarcon.Kind.entries) File(dir, "${kind.name.lowercase()}.wav").writeBytes(VoiceEarcon.wav(VoiceEarcon.pcm(kind)))
        }
    }
}
