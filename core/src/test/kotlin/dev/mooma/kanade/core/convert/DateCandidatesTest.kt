package dev.mooma.kanade.core.convert

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DateCandidatesTest {
    private val d = DateCandidates { LocalDateTime.of(2026, 10, 3, 1, 50) }

    @Test
    fun dates() {
        assertEquals(
            listOf("2026/10/03", "2026-10-03", "2026年10月3日", "10月3日（土）", "10/3（土）", "令和8年10月3日"),
            d.lookup("きょう"),
        )
        assertEquals("2026/10/04", d.lookup("あした").first())
        assertEquals("2026/10/01", d.lookup("おととい").first())
        assertTrue(d.lookup("はし").isEmpty())
    }

    @Test
    fun timesAndYears() {
        assertEquals(listOf("1:50", "1時50分", "午前1時50分"), d.lookup("いま"))
        assertEquals(listOf("2027年", "令和9年"), d.lookup("らいねん"))
    }
}
