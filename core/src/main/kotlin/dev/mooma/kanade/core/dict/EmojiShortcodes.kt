package dev.mooma.kanade.core.dict

/**
 * 絵文字の短い名前（`:rocket:` → 🚀）。名前は GitHub の gemoji（Slack・GitHub と同じ）。
 * 表は `scripts/gen-emoji-shortcodes.py` で作り、core のリソースに入れてある（どの版のエンジンでも同じものを使う）。
 */
class EmojiShortcodes(private val entries: List<Entry>) {
    /** [names] は短い名前（最初のものが代表）、[words] はタグと英語の説明の語 */
    class Entry(val emoji: String, val names: List<String>, val words: List<String>)

    /** 候補。[name] は候補の窓に出す名前（打った文字に合ったもの） */
    data class Match(val emoji: String, val name: String)

    val size: Int get() = entries.size

    private val byName: Map<String, Entry> = HashMap<String, Entry>().also { m -> for (e in entries) for (n in e.names) m.putIfAbsent(n, e) }

    /** 名前がちょうど [name] の絵文字 */
    fun exact(name: String): String? = byName[name]?.emoji

    /** [prefix] で始まる名前があるか（数字を名前の続きとみるか、候補の番号とみるかを決める） */
    fun hasNameStartingWith(prefix: String): Boolean = entries.any { e -> e.names.any { it.startsWith(prefix) } }

    /**
     * [query] に合う絵文字を、よく合う順に。名前がちょうど合うもの、[recent]（最近使った順）、名前の頭が合うもの、
     * 名前の途中の語（`_` の後ろ）の頭が合うもの、タグ・説明の語の頭が合うものの順。同じ組の中は表の順（分類の順）
     */
    fun search(query: String, recent: List<String> = emptyList(), limit: Int = 45): List<Match> {
        val q = query.lowercase()
        if (q.isEmpty()) return emptyList()
        val exact = ArrayList<Match>()
        val head = ArrayList<Match>()
        val inner = ArrayList<Match>()
        val tagged = ArrayList<Match>()
        for (e in entries) {
            val same = e.names.firstOrNull { it == q }
            val starts = if (same == null) e.names.firstOrNull { it.startsWith(q) } else null
            val inside = if (same == null && starts == null) e.names.firstOrNull { n -> n.split('_', '-').drop(1).any { it.startsWith(q) } } else null
            when {
                same != null -> exact += Match(e.emoji, same)
                starts != null -> head += Match(e.emoji, starts)
                inside != null -> inner += Match(e.emoji, inside)
                q.length >= 2 && e.words.any { it.startsWith(q) } -> tagged += Match(e.emoji, e.names.first())
            }
        }
        val rest = head + inner + tagged
        val used = recent.mapNotNull { r -> rest.firstOrNull { it.emoji == r } }
        return (exact + used + rest).distinctBy { it.emoji }.take(limit)
    }

    companion object {
        val EMPTY = EmojiShortcodes(emptyList())

        /** 名前に使う文字（`+1`・`t-rex`・`8ball` など） */
        fun isNameChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '+' || c == '-'

        /** 「絵文字<TAB>名前…<TAB>言葉…」の行から。`#` で始まる行は飛ばす */
        fun parse(text: String) = EmojiShortcodes(
            text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
                val f = line.split('\t')
                val names = f.getOrNull(1)?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
                if (f[0].isEmpty() || names.isEmpty()) null
                else Entry(f[0], names, f.getOrNull(2)?.split(' ')?.filter { it.isNotEmpty() }.orEmpty())
            }.toList(),
        )

        /** core に入れてある表（初めて使うときに読む） */
        val default: EmojiShortcodes by lazy {
            val stream = EmojiShortcodes::class.java.getResourceAsStream("/dev/mooma/kanade/core/dict/emoji-shortcodes.tsv")
            stream?.bufferedReader(Charsets.UTF_8)?.use { parse(it.readText()) } ?: EMPTY
        }
    }
}
