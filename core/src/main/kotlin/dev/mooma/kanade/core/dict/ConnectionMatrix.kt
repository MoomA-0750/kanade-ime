package dev.mooma.kanade.core.dict

import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 品詞の接続コスト。cost(前の語の右ID, 次の語の左ID)。ID 0 は文頭・文末（BOS/EOS）。
 * 形式（ビッグエンディアン）: "KCON" size:int cost:short[size*size]（添字は rid*size+lid、Mozc と同じ並び）
 */
class ConnectionMatrix(buffer: ByteBuffer) {
    private val buf = buffer.duplicate().order(ByteOrder.BIG_ENDIAN)
    val size: Int

    init {
        require(buf.getInt(0) == MAGIC) { "kcon ではない" }
        size = buf.getInt(4)
    }

    fun cost(rid: Int, lid: Int): Int = buf.getShort(HEADER_SIZE + 2 * (rid * size + lid)).toInt()

    companion object {
        const val MAGIC = 0x4B434F4E // "KCON"
        const val HEADER_SIZE = 8

        /** Mozc の connection_single_column.txt（先頭行が size、以降 size*size 行のコスト）から書く */
        fun writeFromMozc(lines: Sequence<String>, out: OutputStream) {
            val it = lines.filter { l -> l.isNotEmpty() && !l.startsWith("#") }.iterator()
            val size = it.next().trim().toInt()
            val d = DataOutputStream(out.buffered(1 shl 16))
            d.writeInt(MAGIC)
            d.writeInt(size)
            var n = 0L
            while (it.hasNext()) {
                val idx = n++
                var c = it.next().trim().toInt()
                if (idx == 0L) c = 0 // BOS→EOS（Mozc も 0 にしている）
                d.writeShort(c.coerceAtMost(Short.MAX_VALUE.toInt()))
            }
            require(n == size.toLong() * size) { "接続行列の要素数が合わない: $n" }
            d.flush()
        }
    }
}
