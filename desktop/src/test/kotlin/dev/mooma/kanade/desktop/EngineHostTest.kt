package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.InputEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineHostTest {
    private fun dll(host: EngineHost = EngineHost(InputEngine())) = FakeDll.inProcess(host).apply { hello() }

    @Test
    fun protocolEscapes() {
        val fields = listOf("commit", "a\tb\\c\nd\re", "", "末尾\\")
        assertEquals(fields, Protocol.decode(Protocol.encode(fields)))
        assertFalse(Protocol.encode(fields).contains('\n'))
    }

    @Test
    fun directModePassesKeys() {
        val d = dll()
        d.type("Hello, world{BS}D")
        assertEquals("Hello, worlD", d.toString())
        assertFalse(d.japanese)
    }

    @Test
    fun romajiThenEnter() {
        val d = dll()
        d.type("{Ctrl+Space}")
        assertTrue(d.japanese)
        d.type("nihongo")
        assertEquals("[にほんご]", d.toString())
        d.type("{Enter}")
        assertEquals("にほんご", d.toString())
        // 入力中でなければ Enter はアプリへ
        d.type("{Enter}")
        assertEquals("にほんご\n", d.toString())
    }

    @Test
    fun toggleKeys() {
        val d = dll()
        d.type("{RAlt}")
        assertTrue(d.japanese)
        d.type("{RAlt}")
        assertFalse(d.japanese)
        d.type("{Henkan}")
        assertTrue(d.japanese)
        d.type("{Muhenkan}")
        assertFalse(d.japanese)
        d.type("{Zenkaku}")
        assertTrue(d.japanese)
    }

    @Test
    fun rightAltWithAnotherKeyDoesNotToggle() {
        val d = dll()
        d.key(WinKey(true, VK.RMENU, 0, Mods.ALT))
        d.key(WinKey(true, 'X'.code, 0, Mods.ALT))
        d.key(WinKey(false, 'X'.code, 0, Mods.ALT))
        d.key(WinKey(false, VK.RMENU, 0, 0))
        assertFalse(d.japanese)
    }

    @Test
    fun rightAltUpAloneTogglesEvenIfDownWasNotSeen() {
        // RichEdit などでは Alt を押したことが届かない
        val d = dll()
        d.type("ab")
        d.key(WinKey(false, VK.RMENU, 0, 0))
        assertTrue(d.japanese)
    }

    @Test
    fun shortcutCommitsThenPasses() {
        val d = dll()
        d.type("{Henkan}ka{Ctrl+s}")
        assertEquals("か", d.toString())
    }

    @Test
    fun widthKeys() {
        val d = dll()
        d.type("{Henkan}kana{F7}")
        assertEquals("[カナ]", d.toString())
        d.type("{Ctrl+t}")
        assertEquals("[kana]", d.toString())
    }

    @Test
    fun anotherClientTakesOver() {
        val host = EngineHost(InputEngine())
        val a = FakeDll.inProcess(host, 1).apply { hello() }
        val b = FakeDll.inProcess(host, 2).apply { hello() }
        a.type("{Henkan}ka")
        assertEquals("[か]", a.toString())
        // b にフォーカスが移った（a の入力中の文字は TSF がそのまま確定させる）。モードは共通
        b.type("ki{Enter}")
        assertEquals("き", b.toString())
    }

    /** 試験用の候補（「かな」→ 仮名・カナ・かな） */
    private val source = object : dev.mooma.kanade.core.CandidateSource {
        override fun convert(reading: String, n: Int) =
            listOf(dev.mooma.kanade.core.Conversion(listOf(dev.mooma.kanade.core.Segment(reading, if (reading == "かな") "仮名" else reading))))
        override fun segmentCandidates(reading: String, n: Int) = if (reading == "かな") listOf("仮名", "カナ", "かな") else listOf(reading)
    }

    @Test
    fun candidatesGoToDllAndClickSelects() {
        val d = FakeDll.inProcess(EngineHost(InputEngine(candidateSource = source))).apply { hello() }
        d.type("{Henkan}kana{Space}{Space}")
        assertEquals(listOf("仮名", "カナ", "かな"), d.candidates, d.received.toString())
        d.select(2)
        assertTrue(d.candidates.isEmpty())
        assertEquals("かな", d.toString())
    }

    @Test
    fun keyScriptMarksShiftForCapitals() {
        val keys = KeyScript.parse("A{Ctrl+Space}")
        assertEquals(Mods.SHIFT, keys[0].mods)
        assertEquals(VK.SPACE, keys[2].vk)
        assertEquals(Mods.CTRL, keys[2].mods)
    }

    @Test
    fun unknownCommandDoesNotBreak() {
        val host = EngineHost(InputEngine())
        val d = FakeDll.inProcess(host)
        host.onLine(object : Client {
            override val id = 9
            override fun send(lines: List<String>) {}
        }, "nonsense\tx")
        d.hello()
        d.type("ok")
        assertEquals("ok", d.toString())
    }

    /** 試験用の音声入力: 止めたら、英語かどうかで決まった文を返す */
    private class FakeVoice : VoiceService {
        var recording = false
        var english: Boolean? = null
        var canceled = 0
        override fun start(english: Boolean, onPartial: (String) -> Unit): Boolean {
            recording = true
            this.english = english
            return true
        }
        override fun stop(onText: (String?) -> Unit) {
            recording = false
            onText(if (english == true) "Hello world." else "こんにちは。")
        }
        override fun cancel() {
            recording = false
            canceled++
        }
    }

    @Test
    fun voiceFromAnyInputMethod() {
        val voice = FakeVoice()
        val host = EngineHost(InputEngine(), voice = voice)
        val d = FakeDll.inProcess(host).apply { hello("fcitx5") }
        // つないだら録音キーが届く
        assertTrue(d.received.any { it == listOf("voicekeys", "F12,Alt_R", "Control+Alt+space") })
        // 英字（keyboard-us）のときに録音キー → 英語のモデルで、確定が届く
        d.send("voice", "start", "en")
        assertTrue(voice.recording)
        assertEquals(true, voice.english)
        assertTrue(d.received.any { it[0] == "indicator" && it[1] == "🎤 EN" })
        d.send("voice", "stop")
        assertEquals("Hello world.", d.toString())
        // Kanade のときは日本語。入力中の文字は先に確定する
        d.type("{Ctrl+Space}ka")
        d.send("voice", "start", "ja")
        assertEquals(false, voice.english)
        assertEquals("Hello world.か", d.toString())
        d.send("voice", "stop")
        assertEquals("Hello world.かこんにちは。", d.toString())
        // Esc での取り消し: 文字は入らない
        d.send("voice", "start", "ja")
        d.send("voice", "cancel")
        assertEquals(1, voice.canceled)
        d.send("voice", "stop")
        assertEquals("Hello world.かこんにちは。", d.toString())
        // 設定で録音キーを替えたら、すぐ届く
        host.voiceKeys = "F9" to ""
        assertTrue(d.received.any { it == listOf("voicekeys", "F9", "") })
    }
}
