package dev.mooma.kanade.core

/** 確定したかな1まとまりと、その元になったローマ字。[autoClose] は開き括弧を打ったときに自動で入れた閉じ括弧 */
data class KanaUnit(val text: String, val raw: String, val autoClose: Boolean = false)

/**
 * ローマ字を1文字ずつ受け取り、かなが決まった分を [KanaUnit] で返す。
 * 綴りの途中（"k"、"ky" など）は [pending] に残る。
 */
class RomajiConverter {
    var pending: String = ""
        private set

    fun feed(c: Char): List<KanaUnit> {
        pending += c.lowercaseChar()
        val out = mutableListOf<KanaUnit>()
        while (pending.isNotEmpty()) {
            val exact = RomajiTable.entries[pending]
            val isPrefix = pending in RomajiTable.prefixes
            if (exact != null && !isPrefix) {
                out += KanaUnit(exact, pending)
                pending = ""
                break
            }
            if (isPrefix) break

            // 表にない綴り。先頭1文字だけ確定させて残りを読み直す
            val head = pending[0]
            val next = pending.getOrNull(1)
            out += when {
                head == 'n' && next != null -> KanaUnit("ん", "n")
                next != null && head == next && head.isDoublableConsonant() -> KanaUnit("っ", head.toString())
                // 「matcha」のように t の次が ch なら「っ」（Google 日本語入力・MS-IME と同じ）
                head == 't' && next == 'c' -> KanaUnit("っ", "t")
                else -> KanaUnit(head.toString(), head.toString())
            }
            pending = pending.substring(1)
        }
        return out
    }

    /** 確定・記号入力の直前に呼ぶ。残った "n" は「ん」、それ以外はそのまま返す */
    fun flush(): List<KanaUnit> {
        val p = pending
        pending = ""
        return when {
            p.isEmpty() -> emptyList()
            p == "n" -> listOf(KanaUnit("ん", "n"))
            else -> listOf(KanaUnit(p, p))
        }
    }

    /** 綴りの途中を1文字消す。消せたら true */
    fun deleteLast(): Boolean {
        if (pending.isEmpty()) return false
        pending = pending.dropLast(1)
        return true
    }

    /** 綴りの途中の末尾 [n] 文字を消す（スニペットに置き換えるとき） */
    fun dropLast(n: Int) {
        pending = pending.dropLast(n)
    }

    fun clear() {
        pending = ""
    }

    private fun Char.isDoublableConsonant() = this in 'a'..'z' && this !in "aiueon"
}
