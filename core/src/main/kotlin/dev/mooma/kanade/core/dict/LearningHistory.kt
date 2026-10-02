package dev.mooma.kanade.core.dict

/**
 * 学習（前に選んだ候補）。読み→最後に選んだ表記。新しく選んだものほど残り、古いものから捨てる。
 * 文節の候補ではユーザー辞書の次に固定し、ラティスにもユーザー辞書と辞書の間のコストで入れる。
 *
 * 保存の形（UTF-8 の行、古い順）: `読み<TAB>表記`
 */
class LearningHistory(private val maxEntries: Int = 5000) {
    // accessOrder=true で、選び直した読みが末尾（新しい側）に来る
    private val map = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > maxEntries
    }

    /** 変わったら true（保存の要否に使う） */
    @Synchronized
    fun learn(reading: String, surface: String): Boolean {
        if (reading.isEmpty() || surface.isEmpty()) return false
        val old = map.put(reading, surface)
        return old != surface
    }

    @Synchronized
    fun lookup(reading: String): String? = map[reading]

    @Synchronized
    fun clear() = map.clear()

    val size: Int @Synchronized get() = map.size

    @Synchronized
    fun write(): String = buildString {
        for ((r, s) in map) append(r).append('\t').append(s.replace("\n", "\\n")).append('\n')
    }

    /**
     * 学習した語を、ラティスに入れるためユーザー辞書の形にする。1文字の読み（「は→葉」など）は入れない
     * （助詞などの位置を食いやすいため。文節の候補の先頭には出る）
     */
    @Synchronized
    fun asDictionary(): UserDictionary = UserDictionary(map.entries.filter { it.key.length >= 2 }.map { it.key to it.value })

    companion object {
        fun parse(text: String, maxEntries: Int = 5000): LearningHistory = LearningHistory(maxEntries).also { h ->
            for (line in text.lineSequence()) {
                val tab = line.indexOf('\t')
                if (tab > 0) h.map[line.substring(0, tab)] = line.substring(tab + 1).replace("\\n", "\n")
            }
        }
    }
}
