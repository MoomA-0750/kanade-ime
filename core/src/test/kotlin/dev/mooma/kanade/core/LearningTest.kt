package dev.mooma.kanade.core

import dev.mooma.kanade.core.dict.LearningHistory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LearningTest {
    @Test
    fun historyKeepsRecentAndRoundTrips() {
        val h = LearningHistory(maxEntries = 2)
        h.learn("はし", "箸")
        h.learn("かみ", "紙")
        h.lookup("はし") // 読んだものは新しい側へ
        h.learn("き", "木")
        assertNull(h.lookup("かみ"))
        assertEquals("箸", h.lookup("はし"))
        assertEquals("木", LearningHistory.parse(h.write()).lookup("き"))
    }

    /** 学習を pinned として返す偽の辞書 */
    private class Source : CandidateSource {
        val learned = LearningHistory()
        override fun convert(reading: String, n: Int) =
            listOf(Conversion(listOf(Segment(reading, if (reading == "はし") "橋" else reading))))
        override fun segmentCandidates(reading: String, n: Int) = if (reading == "はし") listOf("橋", "箸", "端") else listOf(reading)
        override fun pinned(reading: String) = listOfNotNull(learned.lookup(reading))
        override fun learn(reading: String, surface: String) {
            learned.learn(reading, surface)
        }
    }

    @Test
    fun choosingAnotherCandidateIsLearnedAndComesFirstNextTime() {
        val source = Source()
        val e = InputEngine(EngineSettings(), source).apply { handle(KeyInput.SetJapanese(true)) }
        "hashi".forEach { e.handle(KeyInput.Text(it)) }
        e.handle(KeyInput.Space)
        e.handle(KeyInput.Space) // 箸
        e.handle(KeyInput.Enter)
        assertEquals("箸", source.learned.lookup("はし"))

        // 次は入力中（ライブ変換）から「箸」が出る
        "hashi".forEach { e.handle(KeyInput.Text(it)) }
        assertEquals("箸", e.composingText)
        // 1位のまま確定しても学習は変わらない。別のものを選べば上書き
        e.handle(KeyInput.Space)
        e.handle(KeyInput.Space)
        e.handle(KeyInput.Space) // 箸→橋→端
        e.handle(KeyInput.Enter)
        assertEquals("端", source.learned.lookup("はし"))
    }
}
