package dev.mooma.kanade.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceTest {
    @Test
    fun spokenPunctuation() {
        assertEquals("今日は晴れ。", SpokenPunctuation.apply("今日は晴れ句点"))
        // 認識が付けた句読点と重なったら一つに
        assertEquals("今日は晴れ。明日は雨、", SpokenPunctuation.apply("今日は晴れ、句点。明日は雨読点"))
        assertEquals("はい\nいいえ", SpokenPunctuation.apply("はい改行いいえ"))
        assertEquals("「こんにちは」と言った", SpokenPunctuation.apply("かぎかっここんにちはかぎかっことじと言った"))
        assertEquals("本当？", SpokenPunctuation.apply("本当はてなマーク"))
        assertEquals("（注）", SpokenPunctuation.apply("括弧注括弧閉じ"))
        // 普通の言葉はそのまま
        assertEquals("かっこいい車", SpokenPunctuation.apply("かっこいい車"))
        assertEquals("今日は晴れです。", SpokenPunctuation.apply("今日は晴れです。"))
    }

    @Test
    fun endpointer() {
        val e = VoiceEndpointer()
        // 静か（雑音の床を覚える）
        repeat(10) { assertNull(e.feed(0.002, 100)) }
        // 話し始め
        assertNull(e.feed(0.05, 100))
        assertEquals(VoiceEndpointer.Event.START, e.feed(0.05, 100))
        // 話している間の短い間は区切らない
        repeat(3) { assertNull(e.feed(0.002, 100)) }
        assertNull(e.feed(0.05, 100))
        // 0.7 秒静かなら話し終わり
        repeat(6) { assertNull(e.feed(0.002, 100)) }
        assertEquals(VoiceEndpointer.Event.END, e.feed(0.002, 100))
        // 小さな物音は話し始めにしない
        assertNull(e.feed(0.006, 100))
        assertNull(e.feed(0.006, 100))
    }

    @Test
    fun longSpeechIsCut() {
        val e = VoiceEndpointer(maxSpeechMs = 1000)
        repeat(5) { e.feed(0.002, 100) }
        e.feed(0.05, 100)
        assertEquals(VoiceEndpointer.Event.START, e.feed(0.05, 100))
        val events = (0 until 10).mapNotNull { e.feed(0.05, 100) }
        // 区切ったあとも話し続けていれば、次の区切りが始まる
        assertEquals(listOf(VoiceEndpointer.Event.END, VoiceEndpointer.Event.START), events)
    }
}
