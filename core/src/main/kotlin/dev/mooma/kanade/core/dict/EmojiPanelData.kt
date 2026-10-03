package dev.mooma.kanade.core.dict

/**
 * 絵文字パネルの中身。Unicode の emoji-test.txt から、グループごとの並び（キーボードでよく使われる標準の順）を取る。
 * 肌の色の違いと「Component」のグループは入れない（並びが長くなりすぎるため）。
 */
class EmojiPanelData(val groups: List<Group>) {
    class Group(val name: String, val emojis: List<String>)

    /** 1行に「グループ名<TAB>絵文字」 */
    fun write(): String = buildString {
        for (g in groups) for (e in g.emojis) append(g.name).append('\t').append(e).append('\n')
    }

    companion object {
        private val SKIN_TONES = 0x1F3FB..0x1F3FF

        fun fromUnicodeTest(lines: List<String>): EmojiPanelData {
            val groups = LinkedHashMap<String, MutableList<String>>()
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
                groups.getOrPut(group) { ArrayList() } += String(points.toIntArray(), 0, points.size)
            }
            return EmojiPanelData(groups.map { (name, list) -> Group(name, list) })
        }

        fun read(text: String): EmojiPanelData {
            val groups = LinkedHashMap<String, MutableList<String>>()
            for (line in text.lineSequence()) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                groups.getOrPut(line.substring(0, tab)) { ArrayList() } += line.substring(tab + 1)
            }
            return EmojiPanelData(groups.map { (name, list) -> Group(name, list) })
        }
    }
}
