package dev.mooma.kanade.core

import dev.mooma.kanade.core.dict.Snippets

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
    /** 左右の矢印。入力中は未確定文字列の中でカーソルを動かし、変換中は文節を移る */
    data object Left : KeyInput
    data object Right : KeyInput
    /** Home・End。入力中は未確定文字列の先頭・末尾へ */
    data object Home : KeyInput
    data object End : KeyInput
    /** Delete（カーソルの後ろの1文字を消す） */
    data object DeleteForward : KeyInput
    /** Shift+左右。文節の区切りを縮める・伸ばす（入力中なら変換を始めてから） */
    data object ShiftLeft : KeyInput
    data object ShiftRight : KeyInput
    /** 候補の帯で指で選んだ（変換中）。数字キーと同じ動き */
    data class SelectCandidate(val index: Int) : KeyInput
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
    data class SetComposing(
        val text: String,
        val segmentLengths: List<Int> = emptyList(),
        val focused: Int = -1,
        /** 未確定文字列の中のカーソルの位置（先頭からの文字数）。-1 なら末尾 */
        val cursor: Int = -1,
    ) : ImeCommand
    data class Commit(val text: String) : ImeCommand
    data class ModeChanged(val japanese: Boolean) : ImeCommand
    /** 候補の一覧を出す（変換中）。[selected] は選択中の位置、[anchorOffset] は選んでいる文節が未確定文字列の何文字目からか */
    data class ShowCandidates(
        val candidates: List<String>,
        val selected: Int,
        val anchorOffset: Int = 0,
        /** 候補ごとの説明（「ユーザー辞書」「全角」など）。[candidates] と同じ長さか空 */
        val labels: List<String?> = emptyList(),
    ) : ImeCommand
    /**
     * 直接入力中のスニペット: カーソルの前が [expected] なら消して [replacement] を入れる。
     * 違っていたら（カーソルを動かしたなど）、打った文字 [typed] をそのまま入れる
     */
    data class ReplaceBeforeCursor(val expected: String, val typed: Char, val replacement: String) : ImeCommand
    data object HideCandidates : ImeCommand
    /**
     * 言語モデルでの並べ替えを頼む。IME は裏で計算し、終わったら [InputEngine.onReranked] に [id] とともに返す。
     * 新しい要求が出たら古いものは捨ててよい（止めてよい）
     */
    data class RequestRerank(
        val id: Long,
        val leftContext: String,
        val reading: String,
        val candidates: List<String>,
        /** カーソルの後ろの文（文の途中で打っているとき） */
        val rightContext: String = "",
    ) : ImeCommand
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

    /** [reading] に登録されたユーザー辞書の語。候補の先頭に固定し、言語モデルの並べ替えで下げない */
    fun pinned(reading: String): List<String> = emptyList()

    /** 候補の窓で候補の右に出す説明（「ユーザー辞書」「半角」など）。なければ null */
    fun labelFor(reading: String, candidate: String): String? = null

    /** 文節で、最初に出した候補と違うものを選んで確定した（学習する） */
    fun learn(reading: String, surface: String) {}
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

    /** 未確定の直後にある文（文の途中で打っているとき）。IME が入力の始まりに入れる */
    var rightContext: String = ""

    /** スニペット（打った文字の末尾がキーになった瞬間に置き換える） */
    var snippets: Snippets = Snippets.EMPTY

    /** 直接入力（日本語オフ）で続けて打った文字。スニペットのキーを見るため。文字以外のキーで捨てる */
    private val directTyped = StringBuilder()

    private val romaji = RomajiConverter()
    private val units = mutableListOf<KanaUnit>()

    /** 未確定文字列の中のカーソル（その前にある単位の数）。綴りの途中（romaji.pending）はここにある */
    private var caret = 0

    /** カーソルが未確定文字列の途中にある（そのあいだはライブ変換せず、かなで出す） */
    private val caretInside: Boolean get() = caret < units.size

    /** ライブ変換の候補（文全体。[candidatesFor] の読みに対するもの）。並べ替えが届いたら差し替わる */
    private var conversions: List<Conversion> = emptyList()

    /** ユーザー辞書・スニペットで固定する候補（[candidates] の先頭にいる） */
    private var pinned: List<String> = emptyList()
    private var candidates: List<String> = emptyList()
    private var candidatesFor: String? = null
    private var requestId = 0L

    /** 変換中の文節。空なら変換中ではない */
    private class Seg(val reading: String, var candidates: List<String>, val pinned: List<String> = emptyList()) {
        /** 最初に出した候補（これと違うものを選んで確定したら学習する） */
        val initial: String = candidates.firstOrNull().orEmpty()
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

    /**
     * F6〜F10 の元（最初に押したときのかなと、打った文字）。続けて押したときも、変換した結果ではなくここから作り直す
     * （半角カナや英字にしたあとでも、ひらがな・カタカナ・英字に行き来できるように）。入力を変えたら捨てる
     */
    private var widthBase: Pair<String, String>? = null

    /** 直前の F6〜F10 の変換先（英字のときの F8 を半角英字にするため） */
    private var lastWidth: WidthTarget? = null

    val isComposing: Boolean get() = units.isNotEmpty() || romaji.pending.isNotEmpty()
    val isConverting: Boolean get() = segs.isNotEmpty()

    /** 確定済みのかな（読み）。綴りの途中は含まない */
    private val reading: String get() = units.joinToString("") { it.text }

    /** 未確定文字列として出すもの */
    val composingText: String
        get() {
            if (isConverting) return segs.joinToString("") { it.surface }
            if (caretInside) return textBeforeCaret() + units.drop(caret).joinToString("") { it.text }
            if (showReading || !settings.liveConversion) return reading + romaji.pending
            return (bestFor(reading) ?: reading) + romaji.pending
        }

    /** 未確定文字列の中のカーソルの位置（文字数）。末尾なら -1 */
    private val composingCursor: Int get() = if (caretInside && !isConverting) textBeforeCaret().length else -1

    private fun textBeforeCaret() = units.take(caret).joinToString("") { it.text } + romaji.pending

    fun handle(input: KeyInput): EngineResult = when (input) {
        is KeyInput.SetJapanese -> setMode(input.on)
        KeyInput.ToggleJapanese -> setMode(!japanese)
        else -> if (japanese) handleJapanese(input) else handleDirect(input)
    }

    /** 入力欄が変わったなど、直接入力の打鍵の並びが途切れたとき */
    fun resetDirectTyped() {
        directTyped.clear()
    }

    /** 日本語オフ: キーはアプリへそのまま渡し、スニペットのキーが打ち終わったときだけ置き換える */
    private fun handleDirect(input: KeyInput): EngineResult {
        if (input !is KeyInput.Text) {
            directTyped.clear()
            return pass()
        }
        directTyped.append(input.char)
        if (directTyped.length > MAX_TYPED) directTyped.delete(0, directTyped.length - MAX_TYPED)
        val (key, output) = snippets.matchSuffix(directTyped) ?: return pass()
        directTyped.clear()
        return EngineResult(listOf(ImeCommand.ReplaceBeforeCursor(key.dropLast(1), input.char, output)), consumed = true)
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
            seg.candidates = (seg.pinned + mapped + seg.candidates).distinct()
            seg.selected = if (moved) seg.candidates.indexOf(current).coerceAtLeast(0) else 0
            seg.reranked = true
            return conversionView()
        }
        if (isConverting) return emptyList()
        candidates = (pinned + ranked).distinct()
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
        if (wasConverting) {
            val source = candidateSource
            for (seg in segs) if (seg.surface != seg.initial) source?.learn(seg.reading, seg.surface)
        }
        reset()
        leftContext = (leftContext + text).takeLast(MAX_CONTEXT)
        return listOfNotNull(ImeCommand.Commit(text), ImeCommand.HideCandidates.takeIf { wasConverting })
    }

    /** 未確定を捨てる（入力欄が閉じられたとき） */
    fun reset() {
        units.clear()
        caret = 0
        widthBase = null
        romaji.clear()
        conversions = emptyList()
        pinned = emptyList()
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
                widthBase = null
                typeChar(input.char)
                if (!caretInside) expandSnippet()?.let { return EngineResult(it, consumed = true) }
            }
            KeyInput.Backspace -> {
                showReading = false
                widthBase = null
                deleteLast()
            }
            KeyInput.DeleteForward -> {
                widthBase = null
                deleteForward()
            }
            KeyInput.Left, KeyInput.Right, KeyInput.Home, KeyInput.End -> {
                widthBase = null
                moveCaret(input)
                showReading = false
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
            is KeyInput.SelectCandidate -> return EngineResult(emptyList(), consumed = true)
            KeyInput.Other -> return EngineResult(commitAll(), consumed = false)
            is KeyInput.SetJapanese, KeyInput.ToggleJapanese -> error("handled above")
        }
        if (!isComposing) {
            commands += ImeCommand.SetComposing("")
            return EngineResult(commands, consumed = true)
        }
        if (!showReading && !caretInside) commands += refreshCandidates()
        commands += ImeCommand.SetComposing(composingText, cursor = composingCursor)
        return EngineResult(commands, consumed = true)
    }

    /** 入力中のカーソル移動。綴りの途中は確定させ、単位を1文字ずつに分けて文字単位で動かす */
    private fun moveCaret(input: KeyInput) {
        flushPending()
        splitUnits()
        caret = when (input) {
            KeyInput.Left -> caret - 1
            KeyInput.Right -> caret + 1
            KeyInput.Home -> 0
            else -> units.size
        }.coerceIn(0, units.size)
    }

    /** 「きゃ」のような複数文字の単位を1文字ずつに分ける（綴りは逆引きで作り直す） */
    private fun splitUnits() {
        if (units.all { it.text.length <= 1 }) return
        val before = units.take(caret).sumOf { it.text.length }
        val split = units.flatMap { u ->
            if (u.text.length <= 1) listOf(u) else u.text.map { KanaUnit(it.toString(), RomajiTable.reverse[it.toString()] ?: it.toString()) }
        }
        units.clear()
        units += split
        caret = before
    }

    private fun deleteForward() {
        if (romaji.pending.isNotEmpty() || caret >= units.size) return
        val u = units[caret]
        if (u.text.length > 1) {
            val rest = u.text.drop(1)
            units[caret] = KanaUnit(rest, rest.map { RomajiTable.reverse[it.toString()] ?: it.toString() }.joinToString(""))
        } else {
            units.removeAt(caret)
        }
    }

    private fun handleConverting(input: KeyInput): EngineResult {
        val commands = mutableListOf<ImeCommand>()
        val seg = segs[focus]
        /** 候補を選んで次の文節へ進む（文節が1つなら確定）。数字キーと指で選んだとき */
        fun choose(index: Int): EngineResult? {
            seg.selected = index
            if (segs.size == 1) return EngineResult(commitAll(), consumed = true)
            if (focus < segs.lastIndex) {
                focus++
                commands += requestSegmentRerank()
            }
            return null
        }
        when (input) {
            is KeyInput.SelectCandidate -> {
                if (input.index in seg.candidates.indices) choose(input.index)?.let { return it }
            }
            is KeyInput.Text -> {
                // 1〜9 は、いま見えている頁（9件ずつ）の中の番号
                val digit = input.char - '1'
                val index = seg.selected / PAGE * PAGE + digit
                if (digit in 0 until PAGE && index < seg.candidates.size) {
                    choose(index)?.let { return it }
                } else {
                    // 変換中に文字を打ったら、いまの変換を確定して次の入力を始める
                    commands += commitAll()
                    typeChar(input.char)
                    expandSnippet()?.let { return EngineResult(commands + it, consumed = true) }
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
            // 変換中の Home・End・Delete は何もしない
            KeyInput.Home, KeyInput.End, KeyInput.DeleteForward -> {}
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
            ImeCommand.ShowCandidates(
                seg.candidates, seg.selected, segs.take(focus).sumOf { it.surface.length },
                candidateSource?.let { src -> seg.candidates.map { src.labelFor(seg.reading, it) } }
                    ?.takeIf { labels -> labels.any { it != null } }.orEmpty(),
            ),
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
        // 変換をやめて戻ったときは末尾から
        caret = units.size
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
        val headPinned = source.pinned(head)
        segs += Seg(head, (headPinned + source.segmentCandidates(head, settings.candidateCount)).distinct().ifEmpty { listOf(head) }, headPinned)
        if (rest.isNotEmpty()) {
            val conv = source.convert(rest, 1).firstOrNull() ?: Conversion(listOf(Segment(rest, rest)))
            for (s in conv.segments) segs += newSeg(s.reading, s.surface, source)
        }
        return requestSegmentRerank()
    }

    private fun newSeg(reading: String, surface: String, source: CandidateSource): Seg {
        val pinned = source.pinned(reading)
        return Seg(reading, (pinned + surface + source.segmentCandidates(reading, settings.candidateCount)).distinct(), pinned)
    }

    /** 選んでいる文節の候補を、前後の文節を付けた文として言語モデルに並べ替えてもらう */
    private fun requestSegmentRerank(): List<ImeCommand> {
        val seg = segs[focus]
        if (seg.reranked || seg.candidates.size < 2) return emptyList()
        val prefix = segs.take(focus).joinToString("") { it.surface }
        val suffix = segs.drop(focus + 1).joinToString("") { it.surface }
        requestId++
        segRerank = SegRerank(requestId, focus, prefix, suffix)
        val sentences = seg.candidates.take(settings.candidateCount).map { prefix + it + suffix }
        return listOf(ImeCommand.RequestRerank(requestId, leftContext, reading, sentences, rightContext))
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
        pinned = if (r.isEmpty()) emptyList() else source.pinned(r)
        val fromDictionary = conversions.map { it.text }.ifEmpty { if (r.isEmpty()) emptyList() else listOf(r) }
        candidates = (pinned + fromDictionary).distinct()
        requestId++
        if (fromDictionary.size < 2) return emptyList()
        return listOf(ImeCommand.RequestRerank(requestId, leftContext, r, fromDictionary, rightContext))
    }

    /**
     * 打った文字（綴りの途中を含む）の末尾がスニペットのキーなら、キーの分を未確定から外し、
     * それより前の未確定を（いま見えている形で）確定してから出力を確定する。置き換えたら操作の列を返す
     */
    private fun expandSnippet(): List<ImeCommand>? {
        if (snippets.size == 0) return null
        val typed = units.joinToString("") { it.raw } + romaji.pending
        val (key, output) = snippets.matchSuffix(typed) ?: return null
        // キーの文字が、綴りの途中と末尾の単位にちょうど収まるか確かめる（単位の途中で切れるなら置き換えない）
        var rest = key.length
        val fromPending = minOf(rest, romaji.pending.length)
        rest -= fromPending
        var unitCount = 0
        while (rest > 0) {
            val u = units.getOrNull(units.size - 1 - unitCount) ?: return null
            if (u.raw.length > rest) return null
            rest -= u.raw.length
            unitCount++
        }
        romaji.dropLast(fromPending)
        repeat(unitCount) { units.removeAt(units.lastIndex) }
        caret = units.size
        val commands = mutableListOf<ImeCommand>()
        candidatesFor = null
        if (isComposing) commands += commitAll() else reset()
        commands += ImeCommand.Commit(output)
        leftContext = (leftContext + output).takeLast(MAX_CONTEXT)
        return commands
    }

    private fun bestFor(r: String): String? = if (r == candidatesFor) candidates.firstOrNull() else null

    /** 単位をカーソルの位置に入れ、カーソルをその後ろへ */
    private fun insertUnits(list: List<KanaUnit>) {
        units.addAll(caret, list)
        caret += list.size
    }

    private fun flushPending() {
        insertUnits(romaji.flush())
    }

    private fun typeChar(c: Char) {
        val romajiInput = c in 'a'..'z' || (c == '\'' && romaji.pending == "n")
        if (romajiInput) {
            insertUnits(romaji.feed(c))
            return
        }
        flushPending()
        val text = PUNCTUATION[c]
            ?: if (settings.fullWidthSymbols) CharWidth.toFullWidthAscii(c.toString()) else c.toString()
        insertUnits(listOf(KanaUnit(text, c.toString())))
    }

    /** カーソルの前の1文字（綴りの途中なら1字）を消す */
    private fun deleteLast() {
        if (romaji.deleteLast()) return
        if (caret == 0) return
        val last = units.removeAt(caret - 1)
        caret--
        if (last.text.length > 1) {
            // 「きゃ」の「ゃ」だけ消したときなど。残りの綴りは逆引きで作り直す
            val rest = last.text.dropLast(1)
            insertUnits(listOf(KanaUnit(rest, rest.map { RomajiTable.reverse[it.toString()] ?: it.toString() }.joinToString(""))))
        }
    }

    private fun convertWidth(target: WidthTarget) {
        flushPending()
        val base = widthBase ?: (reading to units.joinToString("") { it.raw })
        if (widthBase == null) lastWidth = null
        widthBase = base
        val (kana, raw) = base
        // MS-IME と同じく、英字（全角・半角）にしたあとの F8（Ctrl+O）は半角カナではなく半角英字
        val target = if (target == WidthTarget.HALF_KATAKANA && (lastWidth == WidthTarget.FULL_ALNUM || lastWidth == WidthTarget.HALF_ALNUM)) {
            WidthTarget.HALF_ALNUM
        } else {
            target
        }
        lastWidth = target
        val text = when (target) {
            WidthTarget.HIRAGANA -> CharWidth.toHiragana(kana)
            WidthTarget.KATAKANA -> CharWidth.toKatakana(kana)
            WidthTarget.HALF_KATAKANA -> CharWidth.toHalfWidthKatakana(kana)
            WidthTarget.FULL_ALNUM -> CharWidth.toFullWidthAscii(raw)
            WidthTarget.HALF_ALNUM -> raw
        }
        units.clear()
        units += KanaUnit(text, raw)
        caret = units.size
        showReading = true
    }

    private fun pass() = EngineResult(emptyList(), consumed = false)

    companion object {
        /** 言語モデルに渡す左文脈の長さ（azooKey と同じ） */
        const val MAX_CONTEXT = 40

        /** 候補を数字で選ぶときの1頁の件数 */
        const val PAGE = 9

        /** 直接入力で覚えておく打鍵の数（スニペットのキーの長さの上限） */
        private const val MAX_TYPED = 64

        private val PUNCTUATION = mapOf(
            '-' to "ー", ',' to "、", '.' to "。", '[' to "「", ']' to "」", '/' to "・", '~' to "〜",
        )
    }
}
