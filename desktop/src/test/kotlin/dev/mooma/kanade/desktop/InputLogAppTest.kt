package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.InputEngine
import dev.mooma.kanade.core.convert.DictionaryCandidateSource
import dev.mooma.kanade.core.convert.KanaKanjiConverter
import dev.mooma.kanade.core.convert.Segmenter
import dev.mooma.kanade.core.dict.ConnectionMatrix
import dev.mooma.kanade.core.dict.DictionaryBuilder
import dev.mooma.kanade.core.dict.PosTable
import dev.mooma.kanade.core.dict.SystemDictionary
import dev.mooma.kanade.core.dict.WordEntry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputLogAppTest {
    private fun source(): DictionaryCandidateSource {
        val out = ByteArrayOutputStream()
        DictionaryBuilder.write(listOf(WordEntry("か", 1, 1, 3000, "蚊")), out)
        val conn = ByteArrayOutputStream()
        ConnectionMatrix.writeFromMozc((listOf("3") + List(9) { "0" }).asSequence(), conn)
        return DictionaryCandidateSource(
            KanaKanjiConverter(SystemDictionary(ByteBuffer.wrap(out.toByteArray())), ConnectionMatrix(ByteBuffer.wrap(conn.toByteArray())), unknownPosId = 1),
            Segmenter(PosTable(ByteArray(3))),
        )
    }

    private fun app(inputLog: Boolean): Triple<DesktopApp, EngineHost, DesktopStore> {
        val store = DesktopStore(Files.createTempDirectory("kanade-ilog").toFile())
        store.ensureFiles()
        store.saveSettings(DesktopSettings(inputLog = inputLog))
        val host = EngineHost(InputEngine())
        val app = DesktopApp(store, host, source(), null, {})
        app.start()
        return Triple(app, host, store)
    }

    @Test
    fun defaultIsOffAndExportKeepsItOut() {
        assertFalse(DesktopSettings().inputLog)
        val (_, host, store) = app(false)
        assertNull(host.engine.recorder)
        store.saveSettings(DesktopSettings(inputLog = true, inputLogDays = 30))
        assertEquals(DesktopSettings(inputLog = true, inputLogDays = 30), store.loadSettings())
        val json = java.io.File(store.dir, "export.json")
        store.exportAndroid(json)
        assertFalse(json.readText().contains("input_log"))
    }

    @Test
    fun onWritesTodaysFileAndClearRemovesIt() {
        val (app, host, store) = app(true)
        assertNotNull(host.engine.recorder)
        val d = FakeDll.inProcess(host).apply { hello() }
        d.focus()
        d.field("normal", "com.example")
        d.type("{Henkan}ka{Enter}")
        app.save()
        val files = store.inputLogDir.listFiles { f -> f.name.endsWith(".jsonl") }!!
        assertEquals(1, files.size)
        val text = files[0].readText()
        assertTrue(text.lines().first().contains("\"ev\":\"start\""), text)
        assertTrue(text.contains("\"ev\":\"commit\"") && text.contains("com.example"), text)
        val (ok, _) = app.handleAdmin("clear-input-log", "")
        assertTrue(ok)
        assertEquals(0, store.inputLogDir.listFiles { f -> f.name.endsWith(".jsonl") }!!.size)
    }
}
