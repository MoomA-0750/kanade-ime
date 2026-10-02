package dev.mooma.kanade.core.dict

import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 品詞 ID ごとの、文節の区切り方。Mozc の id.def（`ID 品詞1,品詞2,…`）から作る。
 * 形式: "KPOS" size:int kind:byte[size]
 */
class PosTable(private val kinds: ByteArray) {
    enum class Kind { CONTENT, ATTACH, PREFIX }

    fun kind(id: Int): Kind = when (kinds.getOrElse(id) { 0 }.toInt()) {
        1 -> Kind.ATTACH
        2 -> Kind.PREFIX
        else -> Kind.CONTENT
    }

    fun write(out: OutputStream) {
        val d = DataOutputStream(out)
        d.writeInt(MAGIC)
        d.writeInt(kinds.size)
        d.write(kinds)
        d.flush()
    }

    companion object {
        const val MAGIC = 0x4B504F53 // "KPOS"

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
                id to kind.toByte()
            }
            val size = (entries.maxOfOrNull { it.first } ?: -1) + 1
            val kinds = ByteArray(size)
            for ((id, k) in entries) kinds[id] = k
            return PosTable(kinds)
        }
    }
}
