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

    @Test
    fun maruAndTenOnlyAtTheEndOfASentence() {
        assertEquals("今日は晴れです。明日は雨", SpokenPunctuation.apply("今日は晴れですまる。明日は雨"))
        assertEquals("今日は晴れです。", SpokenPunctuation.apply("今日は晴れですまる"))
        // 文の途中の「丸い」「点数」「100点」はそのまま
        assertEquals("丸い月", SpokenPunctuation.apply("丸い月"))
        assertEquals("100点。", SpokenPunctuation.apply("100点。"))
        assertEquals("てんぷら", SpokenPunctuation.apply("てんぷら"))
        // 「くてん」の聞き違い（九点）も、文の切れ目なら句点
        assertEquals("終わりました。次は", SpokenPunctuation.apply("終わりました九点、次は"))
        assertEquals("九点差で勝った", SpokenPunctuation.apply("九点差で勝った"))
        assertEquals("はい、そうです", SpokenPunctuation.apply("はい当店、そうです"))
        assertEquals("当店のおすすめ", SpokenPunctuation.apply("当店のおすすめ"))
        // 「くてん」の「く」が落ちて「点」になったもの（文の終わりの形の後ろ）は句点
        assertEquals("今日はいい天気です。", SpokenPunctuation.apply("今日はいい天気です点。"))
        assertEquals("はい、", SpokenPunctuation.apply("はい点"))
    }

    @Test
    fun customTableAddsAndRemovesWords() {
        val table = SpokenPunctuation(SpokenPunctuation.parse("ほし ★\n# くてんは消した\nかいぎょう \\n"))
        assertEquals("いいね★", table.apply("いいねほし"))
        assertEquals("終わり句点", table.apply("終わり句点"))
        assertEquals("一\n二", table.apply("一かいぎょう二"))
    }

    @Test
    fun autoPunctuationCanBeTurnedOff() {
        // 認識が付けた「、」は捨て、話した「くてん」は残す
        assertEquals("今日は晴れ明日は雨。", SpokenPunctuation.DEFAULT.apply("今日は晴れ、明日は雨くてん", autoPunctuation = false))
        assertEquals("本当？", SpokenPunctuation.DEFAULT.apply("本当？", autoPunctuation = false))
    }

    @Test
    fun fillersAreRemoved() {
        assertEquals("今日は雨です", Fillers.remove("えーと、今日は雨です"))
        assertEquals("今日は雨です", Fillers.remove("えと今日は雨です"))
        assertEquals("今日は、雨です", Fillers.remove("今日は、えー、雨です"))
        assertEquals("あの人です", Fillers.remove("あの人です"))
        assertEquals("ええ、そうです", Fillers.remove("ええ、そうです"))
    }

    @Test
    fun commandsMatchOnlyTheWholeSegment() {
        assertEquals(VoiceCommand.SEND, VoiceCommand.match("送信して。"))
        assertEquals(VoiceCommand.STOP, VoiceCommand.match("ストップ"))
        assertNull(VoiceCommand.match("これを送信して"))
        assertNull(VoiceCommand.match(""))
    }

    private fun tone(ms: Int, rate: Int = 16000) = FloatArray(rate * ms / 1000) { (kotlin.math.sin(it * 0.1) * 0.3).toFloat() }
    private fun silence(ms: Int, rate: Int = 16000) = FloatArray(rate * ms / 1000)

    @Test
    fun pausesSplitTheRecording() {
        val pcm = tone(1500) + silence(800) + tone(600) + silence(200) + tone(300)
        val spans = PauseSplitter.split(pcm, 16000, minPauseMs = 500)
        // 0.8 秒の間で分かれ、0.2 秒の間はつながる
        assertEquals(2, spans.size)
        assertEquals(1500, spans[0].length * 1000 / 16000)
        assertEquals(1100, spans[1].length * 1000 / 16000)
    }
}
