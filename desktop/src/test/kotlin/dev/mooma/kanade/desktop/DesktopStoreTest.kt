package dev.mooma.kanade.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopStoreTest {
    private fun store() = DesktopStore(Files.createTempDirectory("kanade-store").toFile())

    @Test
    fun settingsRoundTrip() {
        val s = store()
        s.ensureFiles()
        assertEquals(DesktopSettings(), s.loadSettings())
        val changed = DesktopSettings(fullWidthSymbols = false, punctuation = "，．", toggles = setOf(WinToggle.CTRL_SPACE), lastJapanese = true)
        s.saveSettings(changed)
        assertEquals(changed, s.loadSettings())
        assertTrue(s.read(s.snippetsFile).contains("->"))
    }

    @Test
    fun importsAndroidBackup() {
        val s = store()
        s.ensureFiles()
        val json = File(s.dir, "backup.json")
        json.writeText(
            """
            {"format":"kanade-settings","version":1,"exportedAt":0,
             "prefs":{
               "live_conversion":{"type":"boolean","value":false},
               "punctuation":{"type":"string","value":"、．"},
               "toggle_triggers":{"type":"stringSet","value":["CTRL_SPACE","RIGHT_ALT_TAP"]},
               "soft_keyboard":{"type":"string","value":"auto"}
             },
             "files":{"user_dictionary.txt":"めーる me@example.com\n","snippets.txt":"@@ me@example.com\n"}}
            """.trimIndent(),
        )
        val result = s.importAndroid(json)
        assertEquals(3, result.settings)
        assertEquals(2, result.files)
        val loaded = s.loadSettings()
        assertFalse(loaded.liveConversion)
        assertEquals("、．", loaded.punctuation)
        assertEquals(setOf(WinToggle.CTRL_SPACE, WinToggle.RIGHT_ALT_TAP), loaded.toggles)
        assertEquals("めーる me@example.com\n", s.read(s.userDictionaryFile))
        // 前の中身は .bak に残る
        assertTrue(File(s.userDictionaryFile.path + ".bak").readText().contains("Kanade"))
    }

    @Test
    fun rejectsOtherFiles() {
        val s = store()
        val json = File(s.dir, "other.json").apply { writeText("""{"format":"other"}""") }
        assertTrue(runCatching { s.importAndroid(json) }.isFailure)
    }
}
