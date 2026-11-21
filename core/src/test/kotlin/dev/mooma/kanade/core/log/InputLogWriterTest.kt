package dev.mooma.kanade.core.log

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InputLogWriterTest {
    private fun tmp(): File = Files.createTempDirectory("inputlog").toFile().also { it.deleteOnExit() }
    private fun day(s: String) = LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 3_600_000
    private fun ev(t: Long, n: Int = 0) = InputEvent(t, "commit", mapOf("seq" to n, "text" to "あ\"い\n"))
    private fun writer(dir: File, days: Int = 90, mb: Long = 200L * 1024 * 1024) =
        InputLogWriter(File(dir, "input-log"), { days }, { mb }, ZoneOffset.UTC, startThread = false)

    @Test fun oneJsonObjectPerLine() {
        val w = writer(tmp())
        w.record(ev(day("2026-11-22"), 1))
        w.record(ev(day("2026-11-22"), 2))
        w.flush()
        val lines = File(w.dir, "2026-11-22.jsonl").readLines()
        assertEquals(2, lines.size)
        assertEquals("""{"t":${day("2026-11-22")},"ev":"commit","seq":1,"text":"あ\"い\n"}""", lines[0])
    }

    @Test fun newFileEachDayWithHeader() {
        val w = writer(tmp())
        w.header = { InputEvent(0, "start", mapOf("v" to 1)) }
        w.record(ev(day("2026-11-22")))
        w.record(ev(day("2026-11-23")))
        w.flush()
        val a = File(w.dir, "2026-11-22.jsonl").readLines()
        val b = File(w.dir, "2026-11-23.jsonl").readLines()
        assertEquals(2, a.size)
        assertTrue(a[0].contains("\"ev\":\"start\""))
        assertEquals(2, b.size)
        assertTrue(b[0].contains("\"ev\":\"start\""))
    }

    @Test fun pruneByDays() {
        val w = writer(tmp(), days = 10)
        for (d in listOf("2026-11-01", "2026-11-20", "2026-11-22")) {
            w.record(ev(day(d)))
        }
        w.flush()
        w.prune(LocalDate.parse("2026-11-22"))
        assertEquals(listOf("2026-11-20.jsonl", "2026-11-22.jsonl"), w.dir.list()!!.sorted())
    }

    @Test fun pruneByTotalSizeKeepsToday() {
        val w = writer(tmp(), mb = 1)
        val big = "x".repeat(700_000)
        for (d in listOf("2026-11-20", "2026-11-21", "2026-11-22")) {
            w.record(InputEvent(day(d), "commit", mapOf("text" to big)))
            w.flush()
        }
        w.prune(LocalDate.parse("2026-11-22"))
        assertEquals(listOf("2026-11-22.jsonl"), w.dir.list()!!.sorted())
    }

    @Test fun recordNeverBlocksAndCountsDropped() {
        val w = writer(tmp())
        val t = day("2026-11-22")
        repeat(InputLogWriter.QUEUE_SIZE + 50) { w.record(ev(t, it)) }
        w.flush()
        val lines = File(w.dir, "2026-11-22.jsonl").readLines()
        assertEquals(InputLogWriter.QUEUE_SIZE + 1, lines.size)
        assertTrue(lines.last().contains("\"ev\":\"dropped\"") && lines.last().contains("\"n\":50"))
    }

    @Test fun permissions() {
        val w = writer(tmp())
        w.record(ev(day("2026-11-22")))
        w.flush()
        val dirPerms = Files.getPosixFilePermissions(w.dir.toPath())
        val filePerms = Files.getPosixFilePermissions(File(w.dir, "2026-11-22.jsonl").toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE), dirPerms)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), filePerms)
    }

    @Test fun clearAllAndStats() {
        val w = writer(tmp())
        w.record(ev(day("2026-11-22")))
        w.flush()
        assertEquals(1, w.stats().first)
        assertEquals(1, w.clearAll())
        assertEquals(0, w.stats().first)
    }

    @Test fun backgroundThreadWrites() {
        val dir = File(tmp(), "input-log")
        val w = InputLogWriter(dir, zone = ZoneOffset.UTC)
        w.record(ev(day("2026-11-22")))
        w.close()
        assertEquals(1, File(dir, "2026-11-22.jsonl").readLines().size)
    }
}
