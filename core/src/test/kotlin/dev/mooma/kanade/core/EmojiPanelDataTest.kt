package dev.mooma.kanade.core

import dev.mooma.kanade.core.dict.EmojiPanelData
import kotlin.test.Test
import kotlin.test.assertEquals

class EmojiPanelDataTest {
    @Test
    fun parsesGroupsAndSkipsVariants() {
        val lines = """
            # group: Smileys & Emotion
            1F600                                                  ; fully-qualified     # 😀 E1.0 grinning face
            263A                                                   ; unqualified         # ☺ E0.6 smiling face
            263A FE0F                                              ; fully-qualified     # ☺️ E0.6 smiling face
            # group: People & Body
            1F44B                                                  ; fully-qualified     # 👋 E0.6 waving hand
            1F44B 1F3FB                                            ; fully-qualified     # 👋🏻 E1.0 waving hand: light skin tone
            # group: Component
            1F3FB                                                  ; component           # 🏻 E1.0 light skin tone
        """.trimIndent().lines()
        val data = EmojiPanelData.fromUnicodeTest(lines)
        assertEquals(listOf("Smileys & Emotion", "People & Body"), data.groups.map { it.name })
        assertEquals(listOf("😀", "☺️"), data.groups[0].emojis)
        assertEquals(listOf("👋"), data.groups[1].emojis)
        // 書いて読み直しても同じ
        val again = EmojiPanelData.read(data.write())
        assertEquals(data.groups.map { it.emojis }, again.groups.map { it.emojis })
    }
}
