package dev.mooma.kanade.core

import dev.mooma.kanade.core.dict.LearningHistory
import dev.mooma.kanade.core.dict.Snippets
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** でたらめなキーの列で、例外が出ないことと、状態の約束が崩れないことを見る */
class FuzzTest {
    /** 読みを2文字ずつの文節に分け、候補を3つずつ出す偽の辞書（学習・ユーザー辞書も持つ） */
    private class Source : CandidateSource {
        val learned = LearningHistory()
        override fun convert(reading: String, n: Int): List<Conversion> {
            val segs = reading.chunked(2).map { Segment(it, it.uppercase()) }
            return listOf(Conversion(segs), Conversion(listOf(Segment(reading, "[$reading]"))))
        }
        override fun segmentCandidates(reading: String, n: Int) = listOf(reading.uppercase(), "<$reading>", reading)
        override fun pinned(reading: String) = listOfNotNull(learned.lookup(reading))
        override fun learn(reading: String, surface: String) {
            learned.learn(reading, surface)
        }
        override fun labelFor(reading: String, candidate: String) = if (candidate == reading) "ひらがな" else null
    }

    private val keys: List<KeyInput> = buildList {
        for (c in "aiueokstnhmyrwgzdbpcjfvlxq-,.'[]/~1239!@>") add(KeyInput.Text(c))
        addAll(
            listOf(
                KeyInput.Backspace, KeyInput.DeleteForward, KeyInput.Enter, KeyInput.Space, KeyInput.ShiftSpace,
                KeyInput.Escape, KeyInput.Up, KeyInput.Down, KeyInput.Left, KeyInput.Right, KeyInput.Home, KeyInput.End,
                KeyInput.ShiftLeft, KeyInput.ShiftRight, KeyInput.ToggleJapanese, KeyInput.Other,
                KeyInput.SelectCandidate(0), KeyInput.SelectCandidate(2), KeyInput.SelectCandidate(7),
            ),
        )
        for (t in WidthTarget.entries) add(KeyInput.ConvertWidth(t))
    }

    @Test
    fun randomKeySequences() {
        val rnd = Random(20261003)
        repeat(3000) { run ->
            val source = Source()
            val e = InputEngine(EngineSettings(liveConversion = run % 3 != 0), source).apply {
                snippets = Snippets.parse("-> →\n@@ me@example.com\n")
                handle(KeyInput.SetJapanese(true))
            }
            val pending = ArrayList<ImeCommand.RequestRerank>()
            repeat(60) {
                val k = keys[rnd.nextInt(keys.size)]
                val r = e.handle(k)
                pending += r.commands.filterIsInstance<ImeCommand.RequestRerank>()
                // 並べ替えの結果を、ときどき遅れて・順番を入れ替えて返す
                if (pending.isNotEmpty() && rnd.nextInt(3) == 0) {
                    val req = pending.removeAt(rnd.nextInt(pending.size))
                    e.onReranked(req.id, req.candidates.shuffled(rnd))
                }
                // 約束: 変換中なら未確定がある。未確定がなければ空の文字列
                if (e.isConverting) assertTrue(e.isComposing, "変換中なのに未確定がない: $k")
                if (!e.isComposing) assertEquals("", e.composingText.takeIf { e.japanese } ?: "")
            }
            // 最後に確定すれば未確定は残らない
            e.commitAll()
            assertFalse(e.isComposing)
        }
    }
}
