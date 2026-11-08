package dev.mooma.kanade.core.voice

/**
 * 声のコマンド。1回の録音の中で、前後に間（無音）がある短い区切りが、まるごとコマンドの言葉と一致したときだけ働く
 * （[PauseSplitter]。文の中で「送信して」と言ったものは、そのまま書き取る）。Pixel の音声入力の「送信して」「次へ」
 * 「止めて」と、Windows の「それを削除」などにならう
 */
enum class VoiceCommand(val lastOnly: Boolean, vararg val words: String) {
    /** 入力欄の送信（Enter・送信ボタン）。取り消せないので、録音の最後の区切りのときだけ */
    SEND(true, "送信して", "送信", "送信する", "そうしんして", "ソウシンシテ", "送って"),
    /** 次の入力欄へ */
    NEXT(true, "次へ", "つぎへ", "次の欄", "次の入力欄"),
    /** 聞き続けるモードを終える */
    STOP(true, "止めて", "とめて", "ストップ", "停止", "音声入力を停止", "聞き取りを停止"),
    /** 直前に音声で入れた文を消す */
    UNDO(false, "取り消して", "取り消し", "とりけして", "元に戻して", "もとにもどして", "今のを消して", "それを削除"),
    /** 入力欄の文字を全部消す */
    CLEAR(true, "全部消して", "全て消して", "すべて消して", "すべて消去", "全て消去", "すべて消去して", "全部消去して");

    companion object {
        private val IGNORED = setOf('、', '。', '，', '．', ',', '.', '!', '！', '?', '？', ' ', '　', '「', '」')

        /** 区切りの文字が、まるごとコマンドの言葉か（句読点・空白は見ない）。違えば null */
        fun match(text: String): VoiceCommand? {
            var t = text.filter { it !in IGNORED }
            // 丁寧な言い方（「送信してください」「止めてお願いします」）も同じに扱う
            for (suffix in POLITE) if (t.length > suffix.length && t.endsWith(suffix)) t = t.dropLast(suffix.length)
            if (t.isEmpty() || t.length > MAX_LENGTH) return null
            return entries.firstOrNull { c -> c.words.any { it == t } }
        }

        private val POLITE = listOf("お願いします", "おねがいします", "ください", "下さい")

        private const val MAX_LENGTH = 12

        /** 前後の間がこれ以上ある区切りだけをコマンドとして見る（Pixel・Apple の約 0.5 秒より少し長く。読点の息継ぎで切れないように） */
        const val PAUSE_MS = 600

        /** コマンドとして見る区切りの長さ（話している部分） */
        const val MIN_SPEECH_MS = 250
        const val MAX_SPEECH_MS = 2000
    }
}

/**
 * 録った音声を、間（無音）で区切った話の区切りに分ける（声のコマンドを見分けるため）。
 * 音の大きさを 20ms ごとに見て、雑音の床より十分大きいところを話とし、[minPauseMs] より短い無音はつなぐ
 */
object PauseSplitter {
    data class Span(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    fun split(pcm: FloatArray, sampleRate: Int, minPauseMs: Int, minSpeechMs: Int = 120): List<Span> {
        val frame = sampleRate / 50
        val n = pcm.size / frame
        if (n == 0) return emptyList()
        val rms = DoubleArray(n) { f ->
            var sum = 0.0
            for (i in f * frame until (f + 1) * frame) sum += pcm[i] * pcm[i].toDouble()
            kotlin.math.sqrt(sum / frame)
        }
        // 雑音の床は小さいほうから 20% の大きさ（録音の大半が話でも、息継ぎ・頭と終わりの無音がある）
        val noise = rms.sorted()[n / 5]
        val threshold = maxOf(MIN_THRESHOLD, noise * 3)
        val pauseFrames = minPauseMs / 20
        val spans = ArrayList<Span>()
        var start = -1
        var lastLoud = -1
        for (f in 0 until n) {
            if (rms[f] <= threshold) continue
            if (start >= 0 && f - lastLoud - 1 >= pauseFrames) {
                spans += Span(start * frame, (lastLoud + 1) * frame)
                start = -1
            }
            if (start < 0) start = f
            lastLoud = f
        }
        if (start >= 0) spans += Span(start * frame, (lastLoud + 1) * frame)
        // 短すぎる音（咳・キーの音）は話としない
        return spans.filter { it.length * 1000L / sampleRate >= minSpeechMs }
    }

    private const val MIN_THRESHOLD = 0.01
}
