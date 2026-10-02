package dev.mooma.kanade.core.dict

import java.io.DataOutputStream
import java.io.OutputStream

/** 辞書の1語。Mozc の dictionary*.txt の1行（読み 左ID 右ID コスト 表記）に当たる */
data class WordEntry(val reading: String, val lid: Int, val rid: Int, val cost: Int, val surface: String)

/** [WordEntry] の集まりから kdic（[SystemDictionary] の形式）を書く */
object DictionaryBuilder {
    fun parseMozcLine(line: String): WordEntry? {
        if (line.isEmpty() || line.startsWith("#")) return null
        val f = line.split('\t')
        if (f.size < 5) return null
        return WordEntry(f[0], f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4])
    }

    fun write(words: List<WordEntry>, out: OutputStream) {
        // 読みごとにまとめ、同じ読みの中はコストの小さい順（同じ語の重複は Mozc の辞書にも多いので残す）
        val byReading = words.groupBy { it.reading }.toSortedMap()
        val readings = byReading.keys.toList()

        val surfaceChars = StringBuilder()
        val surfaceIndex = HashMap<String, Int>()
        fun surfaceRef(w: WordEntry): Pair<Int, Int> {
            if (w.surface == w.reading) return SystemDictionary.SURFACE_SAME to 0
            if (w.surface == toKatakana(w.reading)) return SystemDictionary.SURFACE_KATAKANA to 0
            require(w.surface.length < SystemDictionary.SURFACE_KATAKANA) { "表記が長すぎる: ${w.surface}" }
            val off = surfaceIndex.getOrPut(w.surface) {
                val o = surfaceChars.length
                surfaceChars.append(w.surface)
                o
            }
            return w.surface.length to off
        }

        val d = DataOutputStream(out.buffered(1 shl 16))
        val entryCount = words.size
        val readingCharCount = readings.sumOf { it.length }
        val sortedEntries = readings.map { r -> byReading.getValue(r).sortedBy { it.cost } }
        val refs = sortedEntries.map { list -> list.map { surfaceRef(it) } }

        d.writeInt(SystemDictionary.MAGIC)
        d.writeInt(SystemDictionary.VERSION)
        d.writeInt(readings.size)
        d.writeInt(entryCount)
        d.writeInt(readingCharCount)
        d.writeInt(surfaceChars.length)

        var off = 0
        for (r in readings) { d.writeInt(off); off += r.length }
        d.writeInt(off)
        var e = 0
        for (list in sortedEntries) { d.writeInt(e); e += list.size }
        d.writeInt(e)
        for (r in readings) d.writeChars(r)
        for ((i, list) in sortedEntries.withIndex()) {
            for ((j, w) in list.withIndex()) {
                val (len, sOff) = refs[i][j]
                d.writeShort(w.lid)
                d.writeShort(w.rid)
                d.writeShort(w.cost.coerceAtMost(Short.MAX_VALUE.toInt()))
                d.writeShort(len)
                d.writeInt(sOff)
            }
        }
        d.writeChars(surfaceChars.toString())
        d.flush()
    }

    private fun toKatakana(s: String) = buildString { for (c in s) append(if (c in 'ぁ'..'ゖ') c + 0x60 else c) }
}
