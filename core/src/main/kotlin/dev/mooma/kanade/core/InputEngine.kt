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
    /** 左右の矢印。変換中は文節を移る */
    data object Left : KeyInput
    data object Right : KeyInput
    /** Shift+左右。文節の区切りを縮める・伸ばす（入力中なら変換を始めてから） */
    data object ShiftLeft : KeyInput
    data object ShiftRight : KeyInput
    data class SetJapanese(val on: Boolean) : KeyInput
    data object ToggleJapanese : KeyInput
    data class ConvertWidth(val target: WidthTarget) : KeyInput
    /** 上記以外（Ctrl 併用など）。未確定があれば確定してからアプリへ渡す */
    data object Other : KeyInput
}

/** F6〜F10 に相当する変換先 */
enum class WidthTarget { HIRAGANA, KATAKANA, HALF_KATAKANA, FULL_ALNUM, HALF_ALNUM }

/** IME がアプリへ行う操作 */
sealed interface ImeCommand {
    /**
     * 未確定文字列を置き換える（空文字なら未確定を消す）。
     * 変換中は [segmentLengths] に文節ごとの長さ、[focused] に選んでいる文節が入る（見た目の下線用）
     */
    data class SetComposing(val text: String, val segmentLengths: List<Int> = emptyList(), val focused: Int = -1) : ImeCommand
    data class Commit(val text: String) : ImeCommand
    data class ModeChanged(val japanese: Boolean) : ImeCommand
    /** 候補の一覧を出す（変換中）。[selected] は選択中の位置、[anchorOffset] は選んでいる文節が未確定文字列の何文字目からか */
    data class ShowCandidates(val candidates: List<String>, val selected: Int, val anchorOffset: Int = 0) : ImeCommand
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

/** 文節（読みと、いま選んでいる表記） */
data class Segment(val reading: String, val surface: String)

/** 文全体の変換結果。文節に分けてある */
data class Conversion(val segments: List<Segment>) {
    val text: String get() = segments.joinToString("") { it.surface }
}

/** かな漢字変換の候補を出すもの（辞書とラティス）。速いので入力のたびに同期で呼ぶ */
interface CandidateSource {
    /** 文全体の変換の N-best（よい順） */
    fun convert(reading: String, n: Int): List<Conversion>

    /** [reading] を1文節としたときの候補（よい順。ひらがな・カタカナも含める） */
    fun segmentCandidates(reading: String, n: Int): List<String>
}

/**
 * 入力エンジン。ローマ字→かな、未確定文字列、ライブ変換、文節に分けた変換と候補選択を持つ。
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

    /** ライブ変換の候補（文全体。[candidatesFor] の読みに対するもの）。並べ替えが届いたら差し替わる */
    private var conversions: List<Conversion> = emptyList()
    private var candidates: List<String> = emptyList()
    private var candidatesFor: String? = null
    private var requestId = 0L

    /** 変換中の文節。空なら変換中ではない */
    private class Seg(val reading: String, var candidates: List<String>) {
        var selected = 0
        var reranked = false
        val surface: String get() = candidates[selected]
    }

    private val segs = mutableListOf<Seg>()
    private var focus = 0

    /** 文節の候補の並べ替えの依頼（文全体として採点し、前後を外して戻す） */
    private class SegRerank(val id: Long, val index: Int, val prefix: String, val suffix: String)

    private var segRerank: SegRerank? = null

    /** Esc・Backspace で変換をやめたあとや F6〜F10 のあとは、次に打つまでかなのまま出す */
    private var showReading = false

    val isComposing: Boolean get() = units.isNotEmpty() || romaji.pending.isNotEmpty()
    val isConverting: Boolean get() = segs.isNotEmpty()

    /** 確定済みのかな（読み）。綴りの途中は含まない */
    private val reading: String get() = units.joinToString("") { it.text }

    /** 未確定文字列として出すもの */
    val composingText: String
        get() {
            if (isConverting) return segs.joinToString("") { it.surface }
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
        val sr = segRerank
        if (isConverting && sr != null && sr.id == id) {
            val seg = segs.getOrNull(sr.index) ?: return emptyList()
            val mapped = ranked.filter { it.startsWith(sr.prefix) && it.endsWith(sr.suffix) && it.length > sr.prefix.length + sr.suffix.length }
                .map { it.substring(sr.prefix.length, it.length - sr.suffix.length) }
            if (mapped.isEmpty()) return emptyList()
            val current = seg.surface
            val moved = seg.selected != 0
            seg.candidates = (mapped + seg.candidates).distinct()
            seg.selected = if (moved) seg.candidates.indexOf(current).coerceAtLeast(0) else 0
            seg.reranked = true
            return conversionView()
        }
        if (isConverting) return emptyList()
        candidates = ranked
        return listOf(ImeCommand.SetComposing(composingText))
    }

    /** アプリ側の事情（入力欄の切り替えなど）や Enter で、いま出しているものを確定させる */
    fun commitAll(): List<ImeCommand> {
        if (!isComposing) return emptyList()
        flushPending()
        // 末尾の「n」が「ん」になるなどで読みが変わったら、辞書の1位を取り直す（並べ替えの依頼は捨てる）
        if (!isConverting && !showReading && settings.liveConversion) refreshCandidates()
        val text = composingText
        val wasConverting = isConverting
        reset()
        leftContext = (leftContext + text).takeLast(MAX_CONTEXT)
        return listOfNotNull(ImeCommand.Commit(text), ImeCommand.HideCandidates.takeIf { wasConverting })
    }

    /** 未確定を捨てる（入力欄が閉じられたとき） */
    fun reset() {
        units.clear()
        romaji.clear()
        conversions = emptyList()
        candidates = emptyList()
        candidatesFor = null
        segs.clear()
        focus = 0
        segRerank = null
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
        if (isConverting) return handleConverting(input)

        val commands = mutableListOf<ImeCommand>()
        when (input) {
            is KeyInput.Text -> {
                showReading = false
                typeChar(input.char)
            }
            KeyInput.Backspace -> {
                showReading = false
                deleteLast()
            }
            KeyInput.Enter -> return EngineResult(commitAll(), consumed = true)
            KeyInput.Escape -> {
                reset()
                return EngineResult(listOf(ImeCommand.SetComposing("")), consumed = true)
            }
            KeyInput.Space, KeyInput.Down, KeyInput.ShiftSpace, KeyInput.Up -> {
                commands += startConversion()
                if (input == KeyInput.ShiftSpace || input == KeyInput.Up) segs.firstOrNull()?.let { it.selected = it.candidates.lastIndex }
                return EngineResult(commands + conversionView(), consumed = true)
            }
            KeyInput.ShiftLeft, KeyInput.ShiftRight -> {
                commands += startConversion()
                if (isConverting) commands += resize(if (input == KeyInput.ShiftLeft) -1 else +1)
                return EngineResult(commands + conversionView(), consumed = true)
            }
            is KeyInput.ConvertWidth -> convertWidth(input.target)
            // 入力中の左右（未確定文字列の中でのカーソル移動）はまだ持たないので、確定してアプリへ渡す
            KeyInput.Left, KeyInput.Right, KeyInput.Other -> return EngineResult(commitAll(), consumed = false)
            is KeyInput.SetJapanese, KeyInput.ToggleJapanese -> error("handled above")
        }
        if (!isComposing) {
            commands += ImeCommand.SetComposing("")
            return EngineResult(commands, consumed = true)
        }
        if (!showReading) commands += refreshCandidates()
        commands += ImeCommand.SetComposing(composingText)
        return EngineResult(commands, consumed = true)
    }

    private fun handleConverting(input: KeyInput): EngineResult {
        val commands = mutableListOf<ImeCommand>()
        val seg = segs[focus]
        when (input) {
            is KeyInput.Text -> {
                // 1〜9 は、いま見えている頁（9件ずつ）の中の番号
                val digit = input.char - '1'
                val index = seg.selected / PAGE * PAGE + digit
                if (digit in 0 until PAGE && index < seg.candidates.size) {
                    seg.selected = index
                    if (segs.size == 1) return EngineResult(commitAll(), consumed = true)
                    if (focus < segs.lastIndex) {
                        focus++
                        commands += requestSegmentRerank()
                    }
                } else {
                    // 変換中に文字を打ったら、いまの変換を確定して次の入力を始める
                    commands += commitAll()
                    typeChar(input.char)
                    commands += refreshCandidates()
                    commands += ImeCommand.SetComposing(composingText)
                    return EngineResult(commands, consumed = true)
                }
            }
            KeyInput.Space, KeyInput.Down -> seg.selected = (seg.selected + 1).mod(seg.candidates.size)
            KeyInput.ShiftSpace, KeyInput.Up -> seg.selected = (seg.selected - 1).mod(seg.candidates.size)
            KeyInput.Left, KeyInput.Right -> {
                val next = (focus + if (input == KeyInput.Left) -1 else 1).coerceIn(0, segs.lastIndex)
                if (next != focus) {
                    focus = next
                    commands += requestSegmentRerank()
                }
            }
            KeyInput.ShiftLeft -> commands += resize(-1)
            KeyInput.ShiftRight -> commands += resize(+1)
            KeyInput.Enter -> return EngineResult(commitAll(), consumed = true)
            KeyInput.Escape, KeyInput.Backspace -> {
                stopConverting(commands)
                commands += ImeCommand.SetComposing(composingText)
                return EngineResult(commands, consumed = true)
            }
            is KeyInput.ConvertWidth -> {
                stopConverting(commands)
                convertWidth(input.target)
                commands += ImeCommand.SetComposing(composingText)
                return EngineResult(commands, consumed = true)
            }
            KeyInput.Other -> return EngineResult(commitAll(), consumed = false)
            is KeyInput.SetJapanese, KeyInput.ToggleJapanese -> error("handled above")
        }
        return EngineResult(commands + conversionView(), consumed = true)
    }

    /** 変換中の未確定文字列と候補の帯 */
    private fun conversionView(): List<ImeCommand> {
        if (!isConverting) return emptyList()
        val seg = segs[focus]
        return listOf(
            ImeCommand.SetComposing(composingText, segs.map { it.surface.length }, focus),
            ImeCommand.ShowCandidates(seg.candidates, seg.selected, segs.take(focus).sumOf { it.surface.length }),
        )
    }

    /** 変換を始める。ライブ変換の1位（並べ替え済みならその結果）の文節分けをそのまま使う */
    private fun startConversion(): List<ImeCommand> {
        flushPending()
        val commands = refreshCandidates().toMutableList()
        val source = candidateSource ?: return commands
        val top = candidates.firstOrNull() ?: return commands
        val conv = conversions.firstOrNull { it.text == top } ?: Conversion(listOf(Segment(reading, top)))
        segs.clear()
        for (s in conv.segments) segs += newSeg(s.reading, s.surface, source)
        focus = 0
        showReading = false
        commands += requestSegmentRerank()
        return commands
    }

    /** 選んでいる文節を縮める・伸ばす。後ろの文節は引き直す */
    private fun resize(delta: Int): List<ImeCommand> {
        val source = candidateSource ?: return emptyList()
        val all = reading
        val start = segs.take(focus).sumOf { it.reading.length }
        val oldLen = segs[focus].reading.length
        val newLen = (oldLen + delta).coerceIn(1, all.length - start)
        if (newLen == oldLen) return emptyList()
        val head = all.substring(start, start + newLen)
        val rest = all.substring(start + newLen)
        while (segs.size > focus) segs.removeAt(segs.lastIndex)
        segs += Seg(head, source.segmentCandidates(head, settings.candidateCount).ifEmpty { listOf(head) })
        if (rest.isNotEmpty()) {
            val conv = source.convert(rest, 1).firstOrNull() ?: Conversion(listOf(Segment(rest, rest)))
            for (s in conv.segments) segs += newSeg(s.reading, s.surface, source)
        }
        return requestSegmentRerank()
    }

    private fun newSeg(reading: String, surface: String, source: CandidateSource): Seg =
        Seg(reading, (listOf(surface) + source.segmentCandidates(reading, settings.candidateCount)).distinct())

    /** 選んでいる文節の候補を、前後の文節を付けた文として言語モデルに並べ替えてもらう */
    private fun requestSegmentRerank(): List<ImeCommand> {
        val seg = segs[focus]
        if (seg.reranked || seg.candidates.size < 2) return emptyList()
        val prefix = segs.take(focus).joinToString("") { it.surface }
        val suffix = segs.drop(focus + 1).joinToString("") { it.surface }
        requestId++
        segRerank = SegRerank(requestId, focus, prefix, suffix)
        val sentences = seg.candidates.take(settings.candidateCount).map { prefix + it + suffix }
        return listOf(ImeCommand.RequestRerank(requestId, leftContext, reading, sentences))
    }

    private fun stopConverting(commands: MutableList<ImeCommand>) {
        segs.clear()
        focus = 0
        segRerank = null
        showReading = true
        requestId++
        commands += ImeCommand.HideCandidates
    }

    /** 読みが変わっていたら辞書で候補を取り直し、並べ替えを頼む */
    private fun refreshCandidates(): List<ImeCommand> {
        val source = candidateSource ?: return emptyList()
        val r = reading
        if (r == candidatesFor) return emptyList()
        candidatesFor = r
        conversions = if (r.isEmpty()) emptyList() else source.convert(r, settings.candidateCount)
        candidates = conversions.map { it.text }.ifEmpty { if (r.isEmpty()) emptyList() else listOf(r) }
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
