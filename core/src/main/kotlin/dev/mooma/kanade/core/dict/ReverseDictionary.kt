package dev.mooma.kanade.core.dict

import dev.mooma.kanade.core.CharWidth

/**
 * 表記→読みの逆引き（kdic の形で、読みの欄に表記、表記の欄に読みが入っている。dictool の reverseEntries が作る）。
 * 漢字の入った文の読みを求める（再変換、音声の文、ユーザー辞書の語を音声の文に当てる）
 */
class ReverseDictionary(private val dict: SystemDictionary) {
    /** 文の区切り。[reading] は [surface] の読み（かな・記号はそのまま、カタカナはひらがなに） */
    data class Piece(val surface: String, val reading: String)

    /**
     * [text] を、辞書の語とかな・記号に分けて読みを付ける。コスト（語のコスト＋区切りごとの重み）が最小の分け方。
     * 読めない漢字があれば null
     */
    fun pieces(text: String): List<Piece>? {
        if (text.isEmpty()) return emptyList()
        val n = text.length
        val best = LongArray(n + 1) { Long.MAX_VALUE }
        val back = arrayOfNulls<Piece>(n + 1)
        best[0] = 0
        for (i in 0 until n) {
            if (best[i] == Long.MAX_VALUE) continue
            val c = text[i]
            if (!CharWidth.isIdeographic(c)) {
                // かな・記号・英数字は1字ずつ、そのまま読みにする
                offer(best, back, i + 1, best[i] + CHAR_COST, Piece(c.toString(), CharWidth.toHiragana(c.toString())))
            }
            for ((len, index) in dict.prefixSearch(text, i, MAX_WORD)) {
                val surface = text.substring(i, i + len)
                for (e in dict.entriesOf(index)) {
                    offer(best, back, i + len, best[i] + dict.cost(e) + PIECE_COST, Piece(surface, dict.surface(e, surface)))
                }
            }
        }
        if (best[n] == Long.MAX_VALUE) return null
        val out = ArrayList<Piece>()
        var pos = n
        while (pos > 0) {
            val p = back[pos]!!
            out += p
            pos -= p.surface.length
        }
        out.reverse()
        // かな・記号の1字ずつの区切りは、続いていればまとめる
        val merged = ArrayList<Piece>()
        for (p in out) {
            val last = merged.lastOrNull()
            val single = p.surface.length == 1 && !CharWidth.isIdeographic(p.surface[0]) && p.reading == CharWidth.toHiragana(p.surface)
            val lastSingle = last != null && last.surface.none { CharWidth.isIdeographic(it) } && last.reading == CharWidth.toHiragana(last.surface)
            if (single && lastSingle) merged[merged.lastIndex] = Piece(last!!.surface + p.surface, last.reading + p.reading) else merged += p
        }
        return merged
    }

    /** [text] の読み。読めない漢字があれば null */
    fun readingOf(text: String): String? = pieces(text)?.joinToString("") { it.reading }

    private fun offer(best: LongArray, back: Array<Piece?>, end: Int, cost: Long, piece: Piece) {
        if (cost < best[end]) {
            best[end] = cost
            back[end] = piece
        }
    }

    private companion object {
        const val MAX_WORD = 8
        /** 区切りを増やすほど高くする（長い語で読むほうを選ぶ） */
        const val PIECE_COST = 2000L
        /** かな1字の重み。安すぎると「書け」を「書（しょ）＋け」と読む（接尾の「書」はコストがとても小さい） */
        const val CHAR_COST = 1500L
    }
}
