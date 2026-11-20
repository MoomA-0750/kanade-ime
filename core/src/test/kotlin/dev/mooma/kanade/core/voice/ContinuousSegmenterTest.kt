package dev.mooma.kanade.core.voice

import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContinuousSegmenterTest {
    private val rate = 16_000

    /** [ms] の長さの音（[amp] の正弦波。0 なら小さな雑音） */
    private fun sound(ms: Int, amp: Float): FloatArray {
        val n = rate * ms / 1000
        val rnd = java.util.Random(ms.toLong())
        return FloatArray(n) { i -> if (amp == 0f) (rnd.nextFloat() - 0.5f) * 0.004f else amp * sin(2 * PI * 220 * i / rate).toFloat() }
    }

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size }
        return out
    }

    /** [pcm] を [chunk] サンプルずつ流し、出来事を（出た位置のサンプルと一緒に）返す */
    private fun run(s: ContinuousSegmenter, pcm: FloatArray, chunk: Int): List<Pair<Int, ContinuousSegmenter.Event>> {
        val out = ArrayList<Pair<Int, ContinuousSegmenter.Event>>()
        var at = 0
        while (at < pcm.size) {
            val end = minOf(pcm.size, at + chunk)
            for (e in s.feed(pcm.copyOfRange(at, end))) out += end to e
            at = end
        }
        return out
    }

    private fun describe(events: List<Pair<Int, ContinuousSegmenter.Event>>) = events.map { (at, e) ->
        // 位置は 100ms 単位にそろえて比べる（塊の大きさで、出来事を受け取る位置が少しずれるため）
        val t = at / (rate / 10)
        when (e) {
            is ContinuousSegmenter.Event.Segment -> "seg@$t:${e.pcm.size / (rate / 10)}"
            ContinuousSegmenter.Event.Start -> "start@$t"
            ContinuousSegmenter.Event.Idle -> "idle@$t"
        }
    }

    private val sample = concat(sound(1000, 0f), sound(1000, 0.2f), sound(1000, 0f), sound(500, 0.2f), sound(61_000, 0f))

    @Test
    fun twoSegmentsThenIdle() {
        val events = describe(run(ContinuousSegmenter(rate, idleMs = 60_000), sample, 1600))
        assertEquals(5, events.size, events.toString())
        assertTrue(events[0].startsWith("start@"), events.toString())
        assertTrue(events[1].startsWith("seg@"), events.toString())
        assertTrue(events[2].startsWith("start@"), events.toString())
        assertTrue(events[3].startsWith("seg@"), events.toString())
        assertTrue(events[4].startsWith("idle@"), events.toString())
        // 時間切れは、最後に話し終えてから 60 秒すぎたところ（話し終わりは 0.7 秒の無音のあと）
        val lastEnd = events[3].substringAfter("@").substringBefore(":").toInt()
        val idleAt = events[4].substringAfter("@").toInt()
        assertEquals(lastEnd + 601, idleAt)
    }

    @Test
    fun chunkSizeDoesNotMatter() {
        val a = describe(run(ContinuousSegmenter(rate, idleMs = 60_000), sample, 1600))
        val b = describe(run(ContinuousSegmenter(rate, idleMs = 60_000), sample, 160))
        val c = describe(run(ContinuousSegmenter(rate, idleMs = 60_000), sample, 7))
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun segmentKeepsPreRollAndSpeech() {
        val events = run(ContinuousSegmenter(rate, idleMs = 0), sample, 1600).map { it.second }
        val first = events.filterIsInstance<ContinuousSegmenter.Event.Segment>().first()
        // 話した 1 秒・話し始めの前の 0.5 秒まで・話し終わりの 0.7 秒の無音
        val seconds = first.pcm.size / rate.toDouble()
        assertTrue(seconds in 2.0..2.4, "区切りの長さ $seconds 秒")
        // idleMs = 0 なら時間切れにしない
        assertTrue(events.none { it == ContinuousSegmenter.Event.Idle })
    }

    @Test
    fun longSpeechIsCut() {
        val events = run(ContinuousSegmenter(rate, idleMs = 0), concat(sound(1000, 0f), sound(25_000, 0.2f)), 1600).map { it.second }
        assertEquals(1, events.count { it is ContinuousSegmenter.Event.Segment })
    }

    @Test
    fun flushReturnsSpeechInProgress() {
        val s = ContinuousSegmenter(rate, idleMs = 0)
        s.feed(concat(sound(1000, 0f), sound(800, 0.2f)))
        assertTrue(s.inSpeech)
        val pcm = assertNotNull(s.flush())
        assertTrue(pcm.size > rate * 8 / 10)
        assertFalse(s.inSpeech)
        assertEquals(0, s.bufferedSamples())
        // 話していないなら null
        s.feed(sound(1000, 0f))
        assertNull(s.flush())
    }

    @Test
    fun quietDoesNotPileUp() {
        val s = ContinuousSegmenter(rate, idleMs = 0)
        s.feed(sound(30_000, 0f))
        assertTrue(s.bufferedSamples() <= rate, "${s.bufferedSamples()}")
    }

    @Test
    fun commandCandidate() {
        assertTrue(VoiceCommand.isCandidate(concat(sound(700, 0f), sound(800, 0.2f), sound(700, 0f)), rate))
        assertFalse(VoiceCommand.isCandidate(concat(sound(700, 0f), sound(3000, 0.2f), sound(700, 0f)), rate))
        assertFalse(VoiceCommand.isCandidate(concat(sound(700, 0f), sound(800, 0.2f), sound(800, 0f), sound(800, 0.2f), sound(700, 0f)), rate))
    }

    /** 本物の声（リポジトリの見本）でも区切りが出て、話した音は全部どこかの区切りに入る */
    @Test
    fun realVoiceSample() {
        val wav = listOf(File("../eval/voice-sample-ja.wav"), File("eval/voice-sample-ja.wav")).firstOrNull { it.exists() } ?: return
        val bytes = wav.readBytes()
        val pcm = FloatArray((bytes.size - 44) / 2) { i ->
            ((bytes[44 + 2 * i].toInt() and 0xFF) or (bytes[45 + 2 * i].toInt() shl 8)).toShort() / 32768f
        }
        val s = ContinuousSegmenter(rate, idleMs = 0)
        val events = run(s, concat(pcm, sound(1500, 0f)), 1600).map { it.second }
        val segments = events.filterIsInstance<ContinuousSegmenter.Event.Segment>()
        assertTrue(segments.isNotEmpty())
        assertFalse(s.inSpeech)
    }
}
