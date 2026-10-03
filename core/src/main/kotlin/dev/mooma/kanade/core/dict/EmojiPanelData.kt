package dev.mooma.kanade.core.dict

import dev.mooma.kanade.core.RomajiConverter

/**
 * 絵文字パネルの中身。Unicode の emoji-test.txt から、グループごとの並び（キーボードでよく使われる標準の順）を取る。
 * 肌の色の違いと「Component」のグループは入れない（並びが長くなりすぎるため）。
 * 検索のために、絵文字ごとの言葉（英語の名前、Mozc の読み・日本語の名前・説明）を持つ。
 */
class EmojiPanelData(val groups: List<Group>, private val keywords: Map<String, List<String>> = emptyMap()) {
    class Group(val name: String, val emojis: List<String>)

    /** 1行に「グループ名<TAB>絵文字<TAB>空白区切りの言葉」 */
    fun write(): String = buildString {
        for (g in groups) for (e in g.emojis) {
            append(g.name).append('\t').append(e)
            keywords[e]?.let { append('\t').append(it.joinToString(" ")) }
            append('\n')
        }
    }

    /**
     * 言葉で探す。[query] はキーボードで打った文字のまま（ローマ字ならかなにもして探す）。
     * 言葉の頭に合うものを先に、途中に含むものを後に、パネルの並びの順で返す
     */
    fun search(query: String, limit: Int = 200): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val forms = listOfNotNull(q, toKana(q).takeIf { it != q }, toHiragana(q).takeIf { it != q })
        val head = ArrayList<String>()
        val inside = ArrayList<String>()
        for (g in groups) for (e in g.emojis) {
            val words = keywords[e] ?: continue
            when {
                words.any { w -> forms.any { w.startsWith(it) } } -> head += e
                words.any { w -> forms.any { w.contains(it) } } -> inside += e
            }
        }
        return (head + inside).take(limit)
    }

    companion object {
        private val SKIN_TONES = 0x1F3FB..0x1F3FF

        /** ローマ字をひらがなに（綴りの途中はそのまま残す） */
        fun toKana(romaji: String): String {
            if (romaji.any { it !in 'a'..'z' && it != '-' && it != '\'' }) return romaji
            val r = RomajiConverter()
            val out = StringBuilder()
            for (c in romaji) r.feed(c).forEach { out.append(it.text) }
            r.flush().forEach { out.append(it.text) }
            return out.toString()
        }

        /** カタカナをひらがなに */
        fun toHiragana(s: String) = String(s.map { if (it in 'ァ'..'ヶ') it - 0x60 else it }.toCharArray())

        /** 絵文字を言葉と突き合わせるときの形（異体字セレクタを外す） */
        fun normalize(emoji: String) = emoji.replace("️", "")

        /**
         * emoji-test.txt の行と、絵文字ごとの日本語の言葉（Mozc の読み・名前・説明。キーは [normalize] した絵文字）から作る
         */
        fun fromUnicodeTest(lines: List<String>, japanese: Map<String, List<String>> = emptyMap()): EmojiPanelData {
            val groups = LinkedHashMap<String, MutableList<String>>()
            val keywords = HashMap<String, List<String>>()
            var group = ""
            for (line in lines) {
                if (line.startsWith("# group:")) {
                    group = line.removePrefix("# group:").trim()
                    continue
                }
                if (line.startsWith("#") || line.isBlank() || group == "Component") continue
                // 例: 1F600                                                  ; fully-qualified     # 😀 E1.0 grinning face
                val (codes, rest) = line.split(';', limit = 2).takeIf { it.size == 2 } ?: continue
                if (!rest.trimStart().startsWith("fully-qualified")) continue
                val points = codes.trim().split(' ').map { it.toInt(16) }
                if (points.any { it in SKIN_TONES }) continue
                val emoji = String(points.toIntArray(), 0, points.size)
                groups.getOrPut(group) { ArrayList() } += emoji
                // 英語の名前（「E1.0 grinning face」の版の後ろ）
                val english = rest.substringAfter('#').trim().split(' ', limit = 3).getOrNull(2)?.lowercase()?.replace(':', ' ')
                    ?.split(' ', '-')?.filter { it.isNotBlank() }.orEmpty()
                val words = (english + japanese[normalize(emoji)].orEmpty()).distinct()
                if (words.isNotEmpty()) keywords[emoji] = words
            }
            return EmojiPanelData(groups.map { (name, list) -> Group(name, list) }, keywords)
        }

        /** Mozc の emoji_data.tsv から、絵文字ごとの日本語の言葉（読み・日本語の名前・説明） */
        fun japaneseFromMozc(lines: List<String>): Map<String, List<String>> {
            val out = HashMap<String, List<String>>()
            for (line in lines) {
                if (line.startsWith("#") || line.isBlank()) continue
                val f = line.split('\t')
                if (f.size < 6) continue
                val words = (f[2].split(' ') + f[4].split(' ') + f[5].split(' ')).map { toHiragana(it.trim()) }.filter { it.isNotEmpty() && it != "絵文字" }
                out[normalize(f[1])] = words.distinct()
            }
            return out
        }

        fun read(text: String): EmojiPanelData {
            val groups = LinkedHashMap<String, MutableList<String>>()
            val keywords = HashMap<String, List<String>>()
            for (line in text.lineSequence()) {
                val f = line.split('\t')
                if (f.size < 2 || f[0].isEmpty()) continue
                groups.getOrPut(f[0]) { ArrayList() } += f[1]
                if (f.size >= 3 && f[2].isNotEmpty()) keywords[f[1]] = f[2].split(' ')
            }
            return EmojiPanelData(groups.map { (name, list) -> Group(name, list) }, keywords)
        }
    }
}
