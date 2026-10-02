package dev.mooma.kanade.core.dict

/**
 * ユーザー辞書（macOS のユーザ辞書と同じ考え方）。読み（ひらがな）→語。
 * 変換ではラティスに強く優先するコストで入り、候補では辞書の語より前に固定する。
 *
 * 書式（1行に1件）: `よみ 語`（最初の空白で区切る）。`#` で始まる行と空行は無視する。
 */
class UserDictionary(private val entries: List<Pair<String, String>>) {
    private val byReading: Map<String, List<String>> = entries.groupBy({ it.first }, { it.second })

    val size: Int get() = entries.size

    /** 一番長い読みの長さ（ラティスで共通接頭辞を探すときの上限） */
    val maxReadingLength: Int = entries.maxOfOrNull { it.first.length } ?: 0

    fun lookup(reading: String): List<String> = byReading[reading].orEmpty()

    companion object {
        val EMPTY = UserDictionary(emptyList())

        fun parse(text: String): UserDictionary = UserDictionary(parsePairs(text))

        /** `キー 値` の行を読む（スニペットと共通）。値の中の `\n` は改行 */
        fun parsePairs(text: String): List<Pair<String, String>> = text.lineSequence().mapNotNull { line ->
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) return@mapNotNull null
            val sep = s.indexOfFirst { it == ' ' || it == '\t' }
            if (sep <= 0) return@mapNotNull null
            val value = s.substring(sep + 1).trimStart().replace("\\n", "\n")
            if (value.isEmpty()) null else s.substring(0, sep) to value
        }.toList()
    }
}
