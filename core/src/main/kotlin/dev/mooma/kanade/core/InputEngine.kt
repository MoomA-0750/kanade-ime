package dev.mooma.kanade.core

/** Android のキーイベントから独立した入力。どの物理キーがどれになるかは IME 側が決める */
sealed interface KeyInput {
    /** 印字できる文字（Shift などの修飾はキー配列側で反映済み） */
    data class Text(val char: Char) : KeyInput
    data object Backspace : KeyInput
    data object Enter : KeyInput
    data object Space : KeyInput
    /** Shift+Space。変換中は前の候補へ */
    data object ShiftSpace : KeyInput
    data object Escape : KeyInput
    /** 上下の矢印。変換中は候補の選択を動かす */
    data object Up : KeyInput
    data object Down : KeyInput
    data class SetJapanese(val on: Boolean) : KeyInput
    data object ToggleJapanese : KeyInput
    data class ConvertWidth(val target: WidthTarget) : KeyInput
    /** 上記以外（左右の矢印、Ctrl 併用など）。未確定があれば確定してからアプリへ渡す */
    data object Other : KeyInput
}

/** F6〜F10 に相当する変換先 */
enum class WidthTarget { HIRAGANA, KATAKANA, HALF_KATAKANA, FULL_ALNUM, HALF_ALNUM }

/** IME がアプリへ行う操作 */
sealed interface ImeCommand {
    /** 未確定文字列を置き換える（空文字なら未確定を消す） */
    data class SetComposing(val text: String) : ImeCommand
    data class Commit(val text: String) : ImeCommand
    data class ModeChanged(val japanese: Boolean) : ImeCommand
    /** 候補の一覧を出す（変換中）。[selected] は選択中の位置 */
    data class ShowCandidates(val candidates: List<String>, val selected: Int) : ImeCommand
    data object HideCandidates : ImeCommand
    /**
     * 言語モデルでの並べ替えを頼む。IME は裏で計算し、終わったら [InputEngine.onReranked] に [id] とともに返す。
     * 新しい要求が出たら古いものは捨ててよい（止めてよい）
     */
    data class RequestRerank(val id: Long, val leftContext: String, val reading: String, val candidates: List<String>) : ImeCommand
}

/** [consumed] が false なら、キーを IME で止めずにアプリへ渡す */
data class EngineResult(val commands: List<ImeCommand>, val consumed: Boolean)

data class EngineSettings(
    /** 日本語入力中の数字・記号・大文字を全角にする（MS-IME・Google 日本語入力の既定） */
    val fullWidthSymbols: Boolean = true,
    /** 打鍵ごとに変換結果を未確定文字列に出す（ライブ変換）。切ると Space を押すまでかなのまま */
    val liveConversion: Boolean = true,
    /** 辞書から取る候補の数 */
    val candidateCount: Int = 10,
)

/** かな漢字変換の候補を出すもの（辞書とラティス）。速いので入力のたびに同期で呼ぶ */
fun interface CandidateSource {
    fun candidates(reading: String, n: Int): List<String>
}

/**
 * 入力エンジン。ローマ字→かな、未確定文字列、ライブ変換と変換中の候補選択を持つ。
 * Android には依存せず、言語モデルの並べ替えは [ImeCommand.RequestRerank] で外に頼み、結果を [onReranked] で受ける。
 */
class InputEngine(
    var settings: EngineSettings = EngineSettings(),
    var candidateSource: CandidateSource? = null,
) {
    var japanese: Boolean = false
        private set

    /** 未確定の直前にある確定済みの文（言語モデルの文脈）。IME が入力の始まりに入れ、確定のたびにこちらでも足す */
    var leftContext: String = ""

    private val romaji = RomajiConverter()
    private val units = mutableListOf<KanaUnit>()

    /** 候補（[candidatesFor] の読みに対するもの）。並べ替えが届いたら差し替わる */
    private var candidates: List<String> = emptyList()
    private var candidatesFor: String? = null
    private var requestId = 0L

    /** Space を押して候補を選んでいる最中 */
    private var converting = false
    private var selected = 0

    /** Esc・Backspace で変換をやめたあとや F6〜F10 のあとは、次に打つまでかなのまま出す */
    private var showReading = false

    val isComposing: Boolean get() = units.isNotEmpty() || romaji.pending.isNotEmpty()
    val isConverting: Boolean get() = converting

    /** 確定済みのかな（読み）。綴りの途中は含まない */
    private val reading: String get() = units.joinToString("") { it.text }

    /** 未確定文字列として出すもの */
    val composingText: String
        get() {
            if (converting) return candidates.getOrElse(selected) { reading }
            if (showReading || !settings.liveConversion) return reading + romaji.pending
            return (bestFor(reading) ?: reading) + romaji.pending
        }

    fun handle(input: KeyInput): EngineResult = when (input) {
        is KeyInput.SetJapanese -> setMode(input.on)
        KeyInput.ToggleJapanese -> setMode(!japanese)
        else -> if (japanese) handleJapanese(input) else pass()
    }

    /** 並べ替えの結果。古い要求の結果なら何もしない */
    fun onReranked(id: Long, ranked: List<String>): List<ImeCommand> {
        if (id != requestId || ranked.isEmpty()) return emptyList()
        val current = candidates.getOrNull(selected)
        candidates = ranked
        if (!converting) return listOf(ImeCommand.SetComposing(composingText))
        // 変換中に候補を選び直していたら、その候補を選んだままにする
        selected = if (selected == 0) 0 else ranked.indexOf(current).coerceAtLeast(0)
        return listOf(ImeCommand.SetComposing(composingText), ImeCommand.ShowCandidates(candidates, selected))
    }

    /** アプリ側の事情（入力欄の切り替えなど）や Enter で、いま出しているものを確定させる */
    fun commitAll(): List<ImeCommand> {
        if (!isComposing) return emptyList()
        flushPending()
        // 末尾の「n」が「ん」になるなどで読みが変わったら、辞書の1位を取り直す（並べ替えの依頼は捨てる）
        if (!converting && !showReading && settings.liveConversion) refreshCandidates()
        val text = composingText
        val wasConverting = converting
        reset()
        leftContext = (leftContext + text).takeLast(MAX_CONTEXT)
        return listOfNotNull(ImeCommand.Commit(text), ImeCommand.HideCandidates.takeIf { wasConverting })
    }

    /** 未確定を捨てる（入力欄が閉じられたとき） */
    fun reset() {
        units.clear()
        romaji.clear()
        candidates = emptyList()
        candidatesFor = null
        converting = false
        selected = 0
        showReading = false
        requestId++
    }

    private fun setMode(on: Boolean): EngineResult {
        val commands = mutableListOf<ImeCommand>()
        if (!on) commands += commitAll()
        if (japanese != on) {
            japanese = on
            commands += ImeCommand.ModeChanged(on)
        }
        return EngineResult(commands, consumed = true)
    }

    private fun handleJapanese(input: KeyInput): EngineResult {
        if (!isComposing) {
            // 未確定がないときは、文字以外はアプリの通常動作に任せる
            if (input !is KeyInput.Text) return pass()
        }
        val commands = mutableListOf<ImeCommand>()
        when (input) {
            is KeyInput.Text -> {
                if (converting) {
                    // 1〜9 は、いま見えている頁（9件ずつ）の中の番号
                    val digit = input.char - '1'
                    val index = selected / PAGE * PAGE + digit
                    if (digit in 0 until PAGE && index < candidates.size) {
                        selected = index
                        return EngineResult(commitAll(), consumed = true)
                    }
                    // 変換中に文字を打ったら、選んでいる候補を確定して次の入力を始める
                    commands += commitAll()
                }
                showReading = false
                typeChar(input.char)
            }
            KeyInput.Backspace -> {
                if (converting) stopConverting(commands) else {
                    showReading = false
                    deleteLast()
                }
            }
            KeyInput.Enter -> return EngineResult(commitAll(), consumed = true)
            KeyInput.Escape -> {
                if (converting) {
                    stopConverting(commands)
                } else {
                    reset()
                    return EngineResult(listOf(ImeCommand.SetComposing("")), consumed = true)
                }
            }
            KeyInput.Space, KeyInput.Down -> moveSelection(+1, commands)
            KeyInput.ShiftSpace, KeyInput.Up -> moveSelection(-1, commands)
            is KeyInput.ConvertWidth -> {
                if (converting) stopConverting(commands)
                convertWidth(input.target)
            }
            KeyInput.Other -> return EngineResult(commitAll(), consumed = false)
            is KeyInput.SetJapanese, KeyInput.ToggleJapanese -> error("handled above")
        }
        if (!isComposing) {
            commands += ImeCommand.SetComposing("")
            return EngineResult(commands, consumed = true)
        }
        if (!converting && !showReading) commands += refreshCandidates()
        commands += ImeCommand.SetComposing(composingText)
        return EngineResult(commands, consumed = true)
    }

    /** 変換を始める、または候補を進める・戻す */
    private fun moveSelection(delta: Int, commands: MutableList<ImeCommand>) {
        if (!converting) {
            flushPending()
            commands += refreshCandidates()
            if (candidates.isEmpty()) return
            converting = true
            showReading = false
            // 1回目の Space は、ライブ変換で見えている1位をそのまま候補の一覧にする
            selected = if (delta > 0) 0 else candidates.lastIndex
        } else {
            selected = (selected + delta).mod(candidates.size)
        }
        commands += ImeCommand.ShowCandidates(candidates, selected)
    }

    private fun stopConverting(commands: MutableList<ImeCommand>) {
        converting = false
        selected = 0
        showReading = true
        commands += ImeCommand.HideCandidates
    }

    /** 読みが変わっていたら辞書で候補を取り直し、並べ替えを頼む */
    private fun refreshCandidates(): List<ImeCommand> {
        val source = candidateSource ?: return emptyList()
        val r = reading
        if (r == candidatesFor) return emptyList()
        candidatesFor = r
        candidates = if (r.isEmpty()) emptyList() else source.candidates(r, settings.candidateCount).ifEmpty { listOf(r) }
        requestId++
        if (candidates.size < 2) return emptyList()
        return listOf(ImeCommand.RequestRerank(requestId, leftContext, r, candidates))
    }

    private fun bestFor(r: String): String? = if (r == candidatesFor) candidates.firstOrNull() else null

    private fun flushPending() {
        units += romaji.flush()
    }

    private fun typeChar(c: Char) {
        val romajiInput = c in 'a'..'z' || (c == '\'' && romaji.pending == "n")
        if (romajiInput) {
            units += romaji.feed(c)
            return
        }
        flushPending()
        val text = PUNCTUATION[c]
            ?: if (settings.fullWidthSymbols) CharWidth.toFullWidthAscii(c.toString()) else c.toString()
        units += KanaUnit(text, c.toString())
    }

    private fun deleteLast() {
        if (romaji.deleteLast()) return
        val last = units.removeLastOrNull() ?: return
        if (last.text.length > 1) {
            // 「きゃ」の「ゃ」だけ消したときなど。残りの綴りは逆引きで作り直す
            val rest = last.text.dropLast(1)
            units += KanaUnit(rest, rest.map { RomajiTable.reverse[it.toString()] ?: it.toString() }.joinToString(""))
        }
    }

    private fun convertWidth(target: WidthTarget) {
        flushPending()
        val kana = reading
        val raw = units.joinToString("") { it.raw }
        val text = when (target) {
            WidthTarget.HIRAGANA -> CharWidth.toHiragana(kana)
            WidthTarget.KATAKANA -> CharWidth.toKatakana(kana)
            WidthTarget.HALF_KATAKANA -> CharWidth.toHalfWidthKatakana(kana)
            WidthTarget.FULL_ALNUM -> CharWidth.toFullWidthAscii(raw)
            WidthTarget.HALF_ALNUM -> raw
        }
        units.clear()
        units += KanaUnit(text, raw)
        showReading = true
    }

    private fun pass() = EngineResult(emptyList(), consumed = false)

    companion object {
        /** 言語モデルに渡す左文脈の長さ（azooKey と同じ） */
        const val MAX_CONTEXT = 40

        /** 候補を数字で選ぶときの1頁の件数 */
        const val PAGE = 9

        private val PUNCTUATION = mapOf(
            '-' to "ー", ',' to "、", '.' to "。", '[' to "「", ']' to "」", '/' to "・", '~' to "〜",
        )
    }
}
