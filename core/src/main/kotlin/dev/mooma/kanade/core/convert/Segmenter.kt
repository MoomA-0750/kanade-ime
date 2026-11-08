package dev.mooma.kanade.core.convert

import dev.mooma.kanade.core.Segment
import dev.mooma.kanade.core.dict.PosTable

/** ラティスの経路（語の並び）を文節にまとめる。内容語で新しい文節を始め、付属語は前に付ける */
class Segmenter(private val pos: PosTable) {
    /** 品詞 [id] で終わる語を予測の候補に出してよいか（[PosTable.predictable]） */
    fun predictable(id: Int): Boolean = pos.predictable(id)

    fun segments(words: List<Word>): List<Segment> {
        val result = ArrayList<Segment>()
        val reading = StringBuilder()
        val surface = StringBuilder()
        var afterPrefix = false
        for (w in words) {
            val kind = pos.kind(w.lid)
            val startsNew = kind != PosTable.Kind.ATTACH && !afterPrefix && reading.isNotEmpty()
            if (startsNew) {
                result += Segment(reading.toString(), surface.toString())
                reading.clear()
                surface.clear()
            }
            reading.append(w.reading)
            surface.append(w.surface)
            afterPrefix = kind == PosTable.Kind.PREFIX
        }
        if (reading.isNotEmpty()) result += Segment(reading.toString(), surface.toString())
        return result
    }
}
