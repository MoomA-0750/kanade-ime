package dev.mooma.kanade.core.dict

/**
 * スニペット。打った文字の並びの末尾がキーと一致した瞬間に、出力へ置き換える（`@@`→メールアドレス、`->`→「→」）。
 * キーは打ったキーの文字そのもの（日本語入力中でも、かなや全角になる前の文字で見る）。
 *
 * 書式はユーザー辞書と同じ `キー 出力`。
 */
class Snippets(entries: List<Pair<String, String>>) {
    /** 長いキーから先に見る（`->` と `-->` があれば `-->` を優先） */
    private val sorted = entries.distinctBy { it.first }.sortedByDescending { it.first.length }

    val size: Int get() = sorted.size

    /** [typed] の末尾に一致するキーと出力。なければ null */
    fun matchSuffix(typed: CharSequence): Pair<String, String>? = sorted.firstOrNull { (key, _) ->
        typed.length >= key.length && typed.regionMatches(typed.length - key.length, key, 0, key.length)
    }

    val maxKeyLength: Int = sorted.firstOrNull()?.first?.length ?: 0

    companion object {
        val EMPTY = Snippets(emptyList())

        fun parse(text: String) = Snippets(UserDictionary.parsePairs(text))
    }
}
