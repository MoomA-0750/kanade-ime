package dev.mooma.kanade.core

import dev.mooma.kanade.core.dict.EnglishSpelling
import dev.mooma.kanade.core.dict.SymbolTable
import kotlin.test.Test
import kotlin.test.assertEquals

class EnglishSpellingTest {
    private val spelling = EnglishSpelling(
        SymbolTable(
            mapOf(
                "あっぷる" to listOf("apple", "Apple"),
                "うぉっち" to listOf("watch"),
                "わーるど" to listOf("world"),
                "かっぷ" to listOf("cup"),
                "こんぴゅーたー" to listOf("computer"),
                "ぐーぐる" to listOf("Google"),
                "まっぷ" to listOf("map"),
            ),
        ),
    )

    @Test
    fun direct() = assertEquals(listOf("apple", "Apple"), spelling.lookup("あっぷる"))

    @Test
    fun longVowelVariant() = assertEquals(listOf("computer"), spelling.lookup("こんぴゅーた"))

    @Test
    fun compoundJoinsWords() {
        assertEquals(listOf("world cup", "World Cup"), spelling.lookup("わーるどかっぷ"))
        // 固有名が混じれば、頭を大文字にした形が先
        assertEquals(listOf("Google Map", "Google map"), spelling.lookup("ぐーぐるまっぷ"))
    }

    @Test
    fun notKatakanaReadingOrUnknown() {
        assertEquals(emptyList(), spelling.lookup("かんじ"))
        assertEquals(emptyList(), spelling.lookup("漢字"))
    }
}
