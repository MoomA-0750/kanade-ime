package dev.mooma.kanade.core

/** Android のキーイベントから独立した入力。どの物理キーがどれになるかは IME 側が決める */
sealed interface KeyInput {
    /** 印字できる文字（Shift などの修飾はキー配列側で反映済み） */
    data class Text(val char: Char) : KeyInput
    data object Backspace : KeyInput
    data object Enter : KeyInput
    data object Space : KeyInput
    data object Escape : KeyInput
    data class SetJapanese(val on: Boolean) : KeyInput
    data object ToggleJapanese : KeyInput
    data class ConvertWidth(val target: WidthTarget) : KeyInput
    /** 上記以外（矢印、Ctrl 併用など）。未確定があれば確定してからアプリへ渡す */
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
}

/** [consumed] が false なら、キーを IME で止めずにアプリへ渡す */
data class EngineResult(val commands: List<ImeCommand>, val consumed: Boolean)

data class EngineSettings(
    /** 日本語入力中の数字・記号・大文字を全角にする（MS-IME・Google 日本語入力の既定） */
    val fullWidthSymbols: Boolean = true,
)

/**
 * 段階1の入力エンジン。ローマ字→かなと未確定文字列の管理だけを持ち、かな漢字変換はまだしない。
 */
class InputEngine(var settings: EngineSettings = EngineSettings()) {
    var japanese: Boolean = false
        private set

    private val romaji = RomajiConverter()
    private val units = mutableListOf<KanaUnit>()

    /** 未確定文字列の表示（確定済みのかな＋綴りの途中） */
    val composingText: String
        get() = units.joinToString("") { it.text } + romaji.pending

    val isComposing: Boolean get() = units.isNotEmpty() || romaji.pending.isNotEmpty()

    fun handle(input: KeyInput): EngineResult = when (input) {
        is KeyInput.SetJapanese -> setMode(input.on)
        KeyInput.ToggleJapanese -> setMode(!japanese)
        else -> if (japanese) handleJapanese(input) else pass()
    }

    /** アプリ側の事情（入力欄の切り替えなど）で未確定を確定させる */
    fun commitAll(): List<ImeCommand> {
        if (!isComposing) return emptyList()
        units += romaji.flush()
        val text = units.joinToString("") { it.text }
        units.clear()
        return listOf(ImeCommand.Commit(text))
    }

    /** 未確定を捨てる（入力欄が閉じられたとき） */
    fun reset() {
        units.clear()
        romaji.clear()
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
        when (input) {
            is KeyInput.Text -> typeChar(input.char)
            KeyInput.Backspace -> deleteLast()
            KeyInput.Enter -> return EngineResult(commitAll(), consumed = true)
            KeyInput.Escape -> {
                reset()
                return EngineResult(listOf(ImeCommand.SetComposing("")), consumed = true)
            }
            // 段階1では変換がないので何もしない（段階3で変換キーになる）
            KeyInput.Space -> return EngineResult(emptyList(), consumed = true)
            is KeyInput.ConvertWidth -> convertWidth(input.target)
            KeyInput.Other -> return EngineResult(commitAll(), consumed = false)
            is KeyInput.SetJapanese, KeyInput.ToggleJapanese -> error("handled above")
        }
        return EngineResult(listOf(ImeCommand.SetComposing(composingText)), consumed = true)
    }

    private fun typeChar(c: Char) {
        val romajiInput = c in 'a'..'z' || (c == '\'' && romaji.pending == "n")
        if (romajiInput) {
            units += romaji.feed(c)
            return
        }
        units += romaji.flush()
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
        units += romaji.flush()
        val kana = units.joinToString("") { it.text }
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
    }

    private fun pass() = EngineResult(emptyList(), consumed = false)

    private companion object {
        val PUNCTUATION = mapOf(
            '-' to "ー", ',' to "、", '.' to "。", '[' to "「", ']' to "」", '/' to "・", '~' to "〜",
        )
    }
}
