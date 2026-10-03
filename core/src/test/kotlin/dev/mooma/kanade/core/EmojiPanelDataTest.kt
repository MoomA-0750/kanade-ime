package dev.mooma.kanade.core

import dev.mooma.kanade.core.dict.EmojiPanelData
import kotlin.test.Test
import kotlin.test.assertEquals

class EmojiPanelDataTest {
    private val lines = """
        # group: Smileys & Emotion
        1F600                                                  ; fully-qualified     # 😀 E1.0 grinning face
        263A                                                   ; unqualified         # ☺ E0.6 smiling face
        263A FE0F                                              ; fully-qualified     # ☺️ E0.6 smiling face
        # group: People & Body
        1F44B                                                  ; fully-qualified     # 👋 E0.6 waving hand
        1F44B 1F3FB                                            ; fully-qualified     # 👋🏻 E1.0 waving hand: light skin tone
        # group: Component
        1F3FB                                                  ; component           # 🏻 E1.0 light skin tone
        # group: Animals & Nature
        1F431                                                  ; fully-qualified     # 🐱 E0.6 cat face
    """.trimIndent().lines()

    private val mozc = listOf(
        "1F600\t😀\tにこにこ わらい\t\tにっこり笑う顔\t笑顔",
        "1F431\t🐱\tねこ ネコ\t\t猫の顔\t猫",
        "263A\t☺\tほほえみ\t\t白い笑顔\t絵文字",
    )

    @Test
    fun parsesGroupsAndSkipsVariants() {
        val data = EmojiPanelData.fromUnicodeTest(lines)
        assertEquals(listOf("Smileys & Emotion", "People & Body", "Animals & Nature"), data.groups.map { it.name })
        assertEquals(listOf("😀", "☺️"), data.groups[0].emojis)
        assertEquals(listOf("👋"), data.groups[1].emojis)
        // 書いて読み直しても同じ
        val again = EmojiPanelData.read(data.write())
        assertEquals(data.groups.map { it.emojis }, again.groups.map { it.emojis })
    }

    @Test
    fun searchesEnglishAndJapanese() {
        val data = EmojiPanelData.read(EmojiPanelData.fromUnicodeTest(lines, EmojiPanelData.japaneseFromMozc(mozc)).write())
        assertEquals(listOf("🐱"), data.search("cat"))
        // ローマ字はかなにして探す。カタカナの読みはひらがなにそろえる
        assertEquals(listOf("🐱"), data.search("neko"))
        assertEquals(listOf("🐱"), data.search("ねこ"))
        // 異体字セレクタの有無を越えて日本語の言葉が付く
        assertEquals(listOf("☺️"), data.search("hohoemi"))
        // 頭に合うものが先（パネルの並びの順）、途中に含むものが後
        assertEquals(listOf("😀", "☺️", "🐱"), data.search("face"))
        assertEquals(listOf("☺️", "😀"), data.search("smi") + data.search("rinn"))
        assertEquals(emptyList(), data.search(""))
    }
}
