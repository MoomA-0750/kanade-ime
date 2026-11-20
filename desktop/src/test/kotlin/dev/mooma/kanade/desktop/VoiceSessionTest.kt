package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.InputEngine
import dev.mooma.kanade.core.voice.VoiceEarcon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 録音キーの押し方（長押し・短押し）と聞き続けるモード（SPEC 8-3・8-11・8-13・8-15） */
class VoiceSessionTest {
    private val rate = 16_000

    /**
     * 試験用の音声入力。録音は試験が [push] で流し、区切りの認識は音の大きさで決まった文を返す
     * （0.2 → こんにちは。 0.3 → 元気です。 0.25 → 送って 0.35 → 取り消して 0.4 → 止めて 0.45 → 全部消して）
     */
    private class FakeVoice : VoiceService {
        var recording = false
        var onAudio: ((FloatArray) -> Unit)? = null
        var halted = 0
        var canceled = 0
        /** false なら認識の結果を [waiting] にためて、試験が好きな順に返す */
        var autoAnswer = true
        val waiting = mutableListOf<Pair<String?, (String?) -> Unit>>()
        var trimmedTo = -1

        override fun start(english: Boolean, onPartial: (String) -> Unit, onAudio: ((FloatArray) -> Unit)?): Boolean {
            recording = true
            this.onAudio = onAudio
            return true
        }
        override fun stop(onText: (String?) -> Unit) {
            recording = false
            onText("長押しの文。")
        }
        override fun cancel() {
            recording = false
            canceled++
        }
        override fun halt() {
            recording = false
            halted++
        }
        override fun trim(keepSamples: Int) {
            trimmedTo = keepSamples
        }
        override fun recognize(pcm: FloatArray, english: Boolean, onText: (String?) -> Unit) {
            val loud = (pcm.maxOf { abs(it) } * 100 + 0.5).toInt()
            val text = when (loud) {
                20 -> "こんにちは。"
                30 -> "元気です。"
                25 -> "送って"
                35 -> "取り消して"
                40 -> "止めて"
                45 -> "全部消して"
                else -> null
            }
            if (autoAnswer) onText(text) else waiting += text to onText
        }

        fun push(pcm: FloatArray) {
            // 本物と同じく 100ms ずつ流す
            var at = 0
            while (at < pcm.size) {
                val end = minOf(pcm.size, at + 1600)
                onAudio?.invoke(pcm.copyOfRange(at, end))
                at = end
            }
        }
    }

    private fun sound(ms: Int, amp: Float): FloatArray {
        val n = (rate.toLong() * ms / 1000).toInt()
        val rnd = java.util.Random(ms.toLong())
        return FloatArray(n) { i -> if (amp == 0f) (rnd.nextFloat() - 0.5f) * 0.004f else amp * sin(2 * PI * 220 * i / rate).toFloat() }
    }

    /** 前後に間を置いた、ひと区切りの話 */
    private fun utterance(amp: Float, ms: Int = 1000) = sound(500, 0f) + sound(ms, amp) + sound(1000, 0f)

    private var now = 0L
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()

    private fun advance(ms: Long) {
        now += ms
        val due = timers.filter { it.first <= now }
        timers.removeAll(due)
        due.forEach { it.second() }
    }

    private val voice = FakeVoice()
    private val earcons = mutableListOf<VoiceEarcon.Kind>()
    private val host = EngineHost(InputEngine(), voice = voice, clock = { now }, schedule = { ms, block ->
        val t = (now + ms) to block
        timers += t
        ({ timers.remove(t) })
    }).also { h -> h.earconListener = { earcons += it } }

    private fun fcitx() = FakeDll.inProcess(host).apply { hello("fcitx5") }

    /** 短く押して離す（聞き続けるモードに入る・終える） */
    private fun FakeDll.tap() {
        send("voice", "down", "ja")
        advance(100)
        send("voice", "up")
    }

    @Test
    fun tapListensAndInsertsEachSegment() {
        val d = fcitx()
        d.tap()
        assertEquals("listen", d.voiceState)
        assertEquals(listOf(VoiceEarcon.Kind.START_TAP), earcons)
        voice.push(sound(1000, 0f) + utterance(0.2f))
        assertEquals("こんにちは。", d.toString())
        voice.push(utterance(0.3f))
        assertEquals("こんにちは。元気です。", d.toString())
        // 話していないあいだは、録音の側にためる音も少しだけ
        assertTrue(voice.trimmedTo in 0..rate)
        // もう一度押すと終わる（離しは見ない）。文字が入ったので上がる音
        d.tap()
        assertEquals("off", d.voiceState)
        assertEquals(1, voice.halted)
        assertEquals(VoiceEarcon.Kind.DONE_TEXT, earcons.last())
        assertEquals("こんにちは。元気です。", d.toString())
    }

    @Test
    fun stoppingWhileSpeakingKeepsTheSpeech() {
        val d = fcitx()
        d.tap()
        voice.push(sound(500, 0f) + sound(800, 0.2f))
        d.tap()
        assertEquals("こんにちは。", d.toString())
    }

    @Test
    fun holdRecordsUntilRelease() {
        val d = fcitx()
        d.send("voice", "down", "ja")
        assertEquals("hold", d.voiceState)
        advance(400)
        assertEquals(listOf(VoiceEarcon.Kind.START_HOLD), earcons)
        d.send("voice", "up")
        assertEquals("長押しの文。", d.toString())
        assertEquals("off", d.voiceState)
        assertEquals(VoiceEarcon.Kind.DONE_TEXT, earcons.last())
    }

    /** Windows の DLL は F12 をキーとして送ってくる。Esc もエンジンが取る */
    @Test
    fun windowsKeysAndEscape() {
        val d = FakeDll.inProcess(host).apply { hello() }
        d.key(WinKey(down = true, vk = VK.F12, char = 0, mods = 0))
        advance(100)
        d.key(WinKey(down = false, vk = VK.F12, char = 0, mods = 0))
        voice.push(utterance(0.2f))
        assertEquals("こんにちは。", d.toString())
        // 話している途中に Esc: 捨てて終える。Esc はアプリへ行かない
        voice.push(sound(500, 0f) + sound(800, 0.3f))
        d.type("{Esc}")
        assertEquals("こんにちは。", d.toString())
        assertEquals(1, voice.halted)
        assertEquals(VoiceEarcon.Kind.DONE_EMPTY, earcons.last())
        // 長押し
        d.key(WinKey(down = true, vk = VK.F12, char = 0, mods = 0))
        advance(400)
        d.key(WinKey(down = false, vk = VK.F12, char = 0, mods = 0))
        assertEquals("こんにちは。長押しの文。", d.toString())
    }

    @Test
    fun resultsGoInSpokenOrder() {
        val d = fcitx()
        voice.autoAnswer = false
        d.tap()
        voice.push(utterance(0.2f) + utterance(0.3f))
        assertEquals(2, voice.waiting.size)
        // 後の区切りが先に認識できても、話した順に入る
        voice.waiting[1].let { (text, answer) -> answer(text) }
        assertEquals("", d.toString())
        voice.waiting[0].let { (text, answer) -> answer(text) }
        assertEquals("こんにちは。元気です。", d.toString())
    }

    @Test
    fun movingToAnotherFieldStopsAndDropsLateResults() {
        val d = fcitx()
        voice.autoAnswer = false
        d.tap()
        voice.push(utterance(0.2f))
        d.send("voice", "blur")
        assertEquals("off", d.voiceState)
        voice.waiting.forEach { (text, answer) -> answer(text) }
        assertEquals("", d.toString())
        // Windows の形（focus）でも終わる
        d.tap()
        assertEquals("listen", d.voiceState)
        d.send("focus", "1")
        assertEquals("off", d.voiceState)
    }

    @Test
    fun waitsWhileTypingWithKanade() {
        val d = fcitx()
        d.type("{Ctrl+Space}")
        d.tap()
        d.type("ka")
        voice.push(utterance(0.2f))
        assertEquals("[か]", d.toString())
        d.type("{Enter}")
        assertEquals("かこんにちは。", d.toString())
    }

    @Test
    fun voiceCommands() {
        val d = fcitx()
        d.tap()
        voice.push(utterance(0.2f) + utterance(0.25f, 600))
        assertTrue(d.received.any { it == listOf("voiceaction", "send") })
        assertEquals("こんにちは。\n", d.toString())
        voice.push(utterance(0.3f) + utterance(0.35f, 600))
        assertTrue(d.received.any { it == listOf("voiceaction", "undo", "元気です。") })
        assertEquals("こんにちは。\n", d.toString())
        voice.push(utterance(0.45f, 600))
        assertEquals("", d.toString())
        voice.push(utterance(0.4f, 600))
        assertEquals("off", d.voiceState)
    }

    @Test
    fun voiceCommandsCanBeTurnedOffAndLongSpeechIsText() {
        val d = fcitx()
        host.voiceCommands = false
        d.tap()
        voice.push(utterance(0.25f, 600))
        assertEquals("送って", d.toString())
        host.voiceCommands = true
        // 長い区切り（2 秒を超える）はコマンドとして見ない
        voice.push(utterance(0.25f, 2500))
        assertEquals("送って送って", d.toString())
    }

    @Test
    fun stopsAfterSilence() {
        val d = fcitx()
        host.continuousIdleSeconds = 30
        d.tap()
        voice.push(sound(29_000, 0f))
        assertEquals("listen", d.voiceState)
        voice.push(sound(2_000, 0f))
        assertEquals("off", d.voiceState)
        assertEquals(VoiceEarcon.Kind.DONE_EMPTY, earcons.last())
        // 0 なら終えない
        host.continuousIdleSeconds = 0
        d.tap()
        voice.push(sound(200_000, 0f))
        assertEquals("listen", d.voiceState)
    }

    @Test
    fun toggleKeyListens() {
        val d = fcitx()
        d.send("voice", "toggle", "ja")
        assertEquals("listen", d.voiceState)
        voice.push(utterance(0.2f))
        d.send("voice", "toggle", "ja")
        assertEquals("off", d.voiceState)
        assertEquals("こんにちは。", d.toString())
    }
}
