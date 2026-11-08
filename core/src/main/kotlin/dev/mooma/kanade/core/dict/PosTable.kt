package dev.mooma.kanade.core.dict

import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 品詞 ID ごとの、文節の区切り方と、予測の候補に出してよい形か。Mozc の id.def（`ID 品詞1,品詞2,…`）から作る。
 * 形式: "KPOS" size:int kind:byte[size]（下の4ビットが [Kind]、[NOT_PREDICTABLE] のビットが立っていれば予測に出さない）
 */
class PosTable(private val kinds: ByteArray) {
    enum class Kind { CONTENT, ATTACH, PREFIX }

    fun kind(id: Int): Kind = when (kinds.getOrElse(id) { 0 }.toInt() and KIND_MASK) {
        1 -> Kind.ATTACH
        2 -> Kind.PREFIX
        else -> Kind.CONTENT
    }

    /**
     * この品詞で終わる語を、予測の候補に出してよいか。活用の途中の形（「軌道に乗っ」の連用タ接続、「修せよ」の命令形、
     * 「領し」の連用形など）は、続きを打たないと文にならないので出さない
     */
    fun predictable(id: Int): Boolean = kinds.getOrElse(id) { 0 }.toInt() and NOT_PREDICTABLE == 0

    fun write(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC)
        d.writeInt(kinds.size)
        d.write(kinds)
        d.flush()
    }

    companion object {
        const val MAGIC = 0x4B504F53 // "KPOS"
        private const val KIND_MASK = 0x0F
        private const val NOT_PREDICTABLE = 0x10

        /** 予測に出してよい活用形（活用しない語の「*」も出す）。形容詞の連用テ接続は「よろしく」「美しく」のように単独で使う */
        private val PREDICTABLE_FORMS = setOf("*", "基本形", "口語基本形", "現代基本形", "音便基本形", "文語基本形", "体言接続")

        /** [pos]（id.def の品詞を , で分けたもの）の語を予測に出してよいか */
        fun predictableForm(pos: List<String>): Boolean {
            val form = pos.getOrNull(5) ?: "*"
            if (form in PREDICTABLE_FORMS) return true
            return pos[0] == "形容詞" && form == "連用テ接続"
        }

        fun read(buffer: ByteBuffer): PosTable {
            val b = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
            require(b.getInt(0) == MAGIC) { "kpos ではない" }
            val n = b.getInt(4)
            return PosTable(ByteArray(n) { b.get(8 + it) })
        }

        /**
         * 助詞・助動詞・非自立・接尾・記号（開き括弧を除く）は前の文節に付く。接頭詞は次の語と同じ文節になる。
         * それ以外（名詞・動詞自立・形容詞自立・副詞など）で新しい文節が始まる。
         */
        fun fromMozcIdDef(lines: List<String>): PosTable {
            val entries = lines.mapNotNull { line ->
                val sp = line.indexOf(' ')
                if (sp < 0) return@mapNotNull null
                val id = line.substring(0, sp).toIntOrNull() ?: return@mapNotNull null
                val pos = line.substring(sp + 1).split(',')
                val kind = when {
                    pos[0] == "接頭詞" -> 2
                    pos[0] == "助詞" || pos[0] == "助動詞" -> 1
                    pos.getOrNull(1) == "非自立" || pos.getOrNull(1) == "接尾" -> 1
                    pos[0] == "記号" && pos.getOrNull(1) != "括弧開" -> 1
                    else -> 0
                }
                id to (kind or if (predictableForm(pos)) 0 else NOT_PREDICTABLE).toByte()
            }
            val size = (entries.maxOfOrNull { it.first } ?: -1) + 1
            val kinds = ByteArray(size)
            for ((id, k) in entries) kinds[id] = k
            return PosTable(kinds)
        }
    }
}
