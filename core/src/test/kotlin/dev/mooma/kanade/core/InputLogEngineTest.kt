package dev.mooma.kanade.core

import dev.mooma.kanade.core.log.FieldKind
import dev.mooma.kanade.core.log.InputEvent
import dev.mooma.kanade.core.log.LogField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputLogEngineTest {
    private val table = mapOf(
        "へんかんを" to listOf("変換を", "返還を"),
        "たしかめる" to listOf("確かめる", "確める"),
        "はし" to listOf("橋", "箸"),
    )

    private class Source(val table: Map<String, List<String>>) : CandidateSource {
        override fun convert(reading: String, n: Int): List<Conversion> {
            if (reading == "へんかんをたしかめる") return listOf(Conversion(listOf(Segment("へんかんを", "変換を"), Segment("たしかめる", "確かめる"))))
            return (table[reading] ?: listOf(reading)).map { Conversion(listOf(Segment(reading, it))) }
        }
        override fun segmentCandidates(reading: String, n: Int) = table[reading] ?: listOf(reading)
    }

    private var now = 1_000L
    private val events = mutableListOf<InputEvent>()

    private fun engine(kind: FieldKind = FieldKind.NORMAL, app: String = "com.example", limited: Boolean = false, japanese: Boolean = true) =
        InputEngine(EngineSettings(), Source(table)).apply {
            recorder = { events += it }
            clock = { now }
            logField = LogField(kind, app, "", limited)
            if (japanese) handle(KeyInput.SetJapanese(true))
            events.clear()
        }

    private fun InputEngine.type(s: String) = s.forEach { handle(KeyInput.Text(it)); now += 10 }
    private fun ev(name: String) = events.filter { it.ev == name }

    @Test fun convertAndEnterMakesOneCommit() {
        val e = engine()
        e.type("hennkannwotasikameru")
        e.handle(KeyInput.Space)
        e.handle(KeyInput.Space)
        e.handle(KeyInput.Right)
        e.handle(KeyInput.Space)
        e.handle(KeyInput.Enter)
        val c = ev("commit").single()
        val f = c.fields
        assertEquals("enter", f["how"])
        assertEquals("へんかんをたしかめる", f["reading"])
        assertEquals("hennkannwotasikameru{SP}{SP}{R}{SP}{ENT}", f["keys"])
        assertEquals(true, f["conv"])
        @Suppress("UNCHECKED_CAST") val segs = f["segs"] as List<Map<String, Any?>>
        assertEquals(2, segs.size)
        assertEquals(true, segs[0]["moved"])
        assertEquals("com.example", f["app"])
        assertEquals("hw", f["src"])
        assertTrue(c.toJson().startsWith("{\"t\":"))
    }

    @Test fun zenzRerankedIsMarked() {
        val e = engine()
        val cmds = "hasi".map { e.handle(KeyInput.Text(it)).commands }.flatten()
        val r = cmds.filterIsInstance<ImeCommand.RequestRerank>().last()
        e.onReranked(r.id, listOf("箸", "橋"))
        e.handle(KeyInput.Enter)
        val f = ev("commit").single().fields
        assertEquals("reranked", f["zenz"])
        assertEquals("箸", f["zenz_top"])
        assertEquals("橋", f["dict_top"])
    }

    @Test fun switchAndDirectWords() {
        val e = engine()
        e.type("ka")
        e.handle(KeyInput.ToggleJapanese)
        e.type("push to GitHub ")
        e.handle(KeyInput.ToggleJapanese)
        assertEquals(listOf("commit", "switch", "direct", "direct", "direct", "switch"), events.map { it.ev })
        assertEquals("mode", ev("commit").single().fields["how"])
        assertEquals("en", ev("switch")[0].fields["to"])
        assertEquals(listOf("push", "to", "GitHub"), ev("direct").map { it.fields["text"] })
        assertEquals("space", ev("direct")[0].fields["end"])
        assertEquals("ja", ev("switch")[1].fields["to"])
        assertEquals("kapush to GitHub ".takeLast(32).let { "か" + "push to GitHub " }, ev("switch")[1].fields["before"])
    }

    @Test fun escapeCancels() {
        val e = engine()
        e.type("hoge")
        e.handle(KeyInput.Escape)
        val c = ev("cancel").single().fields
        assertEquals("ほげ", c["reading"])
        assertEquals("hoge{ESC}", c["keys"])
        assertTrue(ev("commit").isEmpty())
    }

    @Test fun backspaceRightAfterCommitIsFix() {
        val e = engine()
        e.type("ka")
        e.handle(KeyInput.Enter)
        val seq = ev("commit").single().fields["seq"]
        now += 1200
        e.handle(KeyInput.Backspace)
        e.handle(KeyInput.Backspace)
        e.handle(KeyInput.Text('a'))
        val fix = ev("fix").single().fields
        assertEquals(seq, fix["ref"])
        assertEquals(2, fix["bs"])
        assertEquals(1200L, fix["ms"])
    }

    @Test fun backspaceLongAfterCommitIsNotFix() {
        val e = engine()
        e.type("ka")
        e.handle(KeyInput.Enter)
        now += 6000
        e.handle(KeyInput.Backspace)
        e.flushLog()
        assertTrue(ev("fix").isEmpty())
    }

    @Test fun nothingForSecretNolearnUnknownOrOff() {
        for (kind in listOf(FieldKind.SECRET, FieldKind.NOLEARN, FieldKind.UNKNOWN)) {
            events.clear()
            val e = engine(kind)
            e.type("ka")
            e.handle(KeyInput.Enter)
            e.type("x")
            e.handle(KeyInput.Escape)
            e.handle(KeyInput.ToggleJapanese)
            e.type("hello ")
            e.flushLog()
            assertTrue(events.isEmpty(), "$kind: $events")
        }
        events.clear()
        val off = engine().apply { recorder = null }
        off.type("ka")
        off.handle(KeyInput.Enter)
        assertTrue(events.isEmpty())
    }

    @Test fun becomingSecretStopsRecording() {
        val e = engine()
        e.type("ka")
        e.logField = LogField(FieldKind.SECRET, "com.example")
        e.handle(KeyInput.Enter)
        assertTrue(events.isEmpty())
    }

    @Test fun limitedAppKeepsOnlyCommitAndSwitch() {
        val e = engine(app = "org.gnome.Terminal", limited = true)
        e.type("ka")
        e.handle(KeyInput.Enter)
        e.type("sa")
        e.handle(KeyInput.Escape)
        e.handle(KeyInput.ToggleJapanese)
        e.type("sudo ")
        e.handle(KeyInput.ToggleJapanese)
        e.flushLog()
        assertEquals(listOf("commit", "switch", "switch"), events.map { it.ev })
        assertNull(events[0].fields["keys"])
    }

    @Test fun secretLookingDirectWordIsDropped() {
        val e = engine()
        e.handle(KeyInput.ToggleJapanese)
        events.clear()
        e.type("aB3\$kLm9xQ ")
        e.type("ok ")
        e.flushLog()
        assertEquals(listOf("ok"), ev("direct").map { it.fields["text"] })
    }

    @Test fun typingDuringConversionKeepsTheNextKey() {
        val e = engine()
        e.type("hasi")
        e.handle(KeyInput.Space)
        e.type("k")
        e.handle(KeyInput.Enter)
        val cs = ev("commit")
        assertEquals("typing", cs[0].fields["how"])
        assertEquals("hasi{SP}", cs[0].fields["keys"])
        assertEquals("k{ENT}", cs[1].fields["keys"])
    }

    @Test fun voiceEvents() {
        val e = engine()
        e.logSource = "voice"
        e.logVoice("listen", "parakeet", "明日の会議は10時からです。", 3200)
        e.logVoiceCommand("send")
        assertEquals(listOf("voice", "voice_cmd"), events.map { it.ev })
        assertEquals("parakeet", events[0].fields["model"])
        assertEquals("voice", events[0].fields["src"])
    }
}
