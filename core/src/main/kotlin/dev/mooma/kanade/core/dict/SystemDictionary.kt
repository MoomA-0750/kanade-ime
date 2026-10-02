package dev.mooma.kanade.core.dict

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 読み→単語の辞書。[DictionaryBuilder] が書いたバイナリ（kdic）を、ByteBuffer のまま読む。
 * Android では APK 内の非圧縮 asset を mmap するので、起動時の読み込みも展開もない。
 *
 * 形式（ビッグエンディアン）:
 * ```
 * "KDIC" version:int readingCount:int entryCount:int readingChars:int surfaceChars:int
 * readingOffsets:int[N+1]  entryStarts:int[N+1]  readingChars:char[]
 * entries: (lid:short rid:short cost:short surfaceLen:short surfaceOffset:int)[E]
 * surfaceChars:char[]
 * ```
 * 読みは String の辞書順に並ぶ。surfaceLen が [SURFACE_SAME] なら表記＝読み、[SURFACE_KATAKANA] なら読みのカタカナ。
 */
class SystemDictionary(buffer: ByteBuffer) {
    private val buf: ByteBuffer = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)

    val readingCount: Int
    val entryCount: Int
    private val readingOffsetsPos: Int
    private val entryStartsPos: Int
    private val readingCharsPos: Int
    private val entriesPos: Int
    private val surfaceCharsPos: Int

    init {
        require(buf.getInt(0) == MAGIC) { "kdic ではない" }
        require(buf.getInt(4) == VERSION) { "kdic の版が違う: ${buf.getInt(4)}" }
        readingCount = buf.getInt(8)
        entryCount = buf.getInt(12)
        val readingChars = buf.getInt(16)
        readingOffsetsPos = HEADER_SIZE
        entryStartsPos = readingOffsetsPos + 4 * (readingCount + 1)
        readingCharsPos = entryStartsPos + 4 * (readingCount + 1)
        entriesPos = readingCharsPos + 2 * readingChars
        surfaceCharsPos = entriesPos + ENTRY_SIZE * entryCount
    }

    fun reading(index: Int): String {
        val from = readingStart(index)
        val len = readingLength(index)
        val chars = CharArray(len) { buf.getChar(readingCharsPos + 2 * (from + it)) }
        return String(chars)
    }

    fun entriesOf(readingIndex: Int): IntRange =
        buf.getInt(entryStartsPos + 4 * readingIndex) until buf.getInt(entryStartsPos + 4 * (readingIndex + 1))

    fun lid(entry: Int): Int = buf.getShort(entriesPos + ENTRY_SIZE * entry).toInt() and 0xFFFF
    fun rid(entry: Int): Int = buf.getShort(entriesPos + ENTRY_SIZE * entry + 2).toInt() and 0xFFFF
    fun cost(entry: Int): Int = buf.getShort(entriesPos + ENTRY_SIZE * entry + 4).toInt()

    fun surface(entry: Int, reading: String): String {
        val len = buf.getShort(entriesPos + ENTRY_SIZE * entry + 6).toInt() and 0xFFFF
        return when (len) {
            SURFACE_SAME -> reading
            SURFACE_KATAKANA -> buildString { for (c in reading) append(if (c in 'ぁ'..'ゖ') c + 0x60 else c) }
            else -> {
                val off = buf.getInt(entriesPos + ENTRY_SIZE * entry + 8)
                String(CharArray(len) { buf.getChar(surfaceCharsPos + 2 * (off + it)) })
            }
        }
    }

    /**
     * [text] の [start] から始まる読みのうち、辞書にあるものをすべて返す（共通接頭辞検索）。
     * 戻り値は (読みの長さ, 読みの添字) の組。
     */
    fun prefixSearch(text: CharSequence, start: Int, maxLength: Int = 64): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>(8)
        // 先頭 L-1 文字が一致する読みの範囲 [lo, hi) を、L 文字目の比較で絞っていく
        var lo = 0
        var hi = readingCount
        val end = minOf(text.length, start + maxLength)
        var depth = 0
        while (start + depth < end && lo < hi) {
            val c = text[start + depth]
            lo = lowerBound(lo, hi, depth, c)
            hi = upperBound(lo, hi, depth, c)
            depth++
            if (lo < hi && readingLength(lo) == depth) result += depth to lo
        }
        return result
    }

    private fun readingStart(i: Int) = buf.getInt(readingOffsetsPos + 4 * i)
    private fun readingLength(i: Int) = buf.getInt(readingOffsetsPos + 4 * (i + 1)) - readingStart(i)

    /** 読み i の depth 文字目。短くて無ければ -1（短い読みが先に並ぶ） */
    private fun charAt(i: Int, depth: Int): Int =
        if (depth < readingLength(i)) buf.getChar(readingCharsPos + 2 * (readingStart(i) + depth)).code else -1

    private fun lowerBound(from: Int, to: Int, depth: Int, c: Char): Int {
        var l = from
        var h = to
        while (l < h) {
            val m = (l + h) ushr 1
            if (charAt(m, depth) < c.code) l = m + 1 else h = m
        }
        return l
    }

    private fun upperBound(from: Int, to: Int, depth: Int, c: Char): Int {
        var l = from
        var h = to
        while (l < h) {
            val m = (l + h) ushr 1
            if (charAt(m, depth) <= c.code) l = m + 1 else h = m
        }
        return l
    }

    companion object {
        const val MAGIC = 0x4B444943 // "KDIC"
        const val VERSION = 1
        const val HEADER_SIZE = 24
        const val ENTRY_SIZE = 12
        const val SURFACE_SAME = 0xFFFF
        const val SURFACE_KATAKANA = 0xFFFE
    }
}
