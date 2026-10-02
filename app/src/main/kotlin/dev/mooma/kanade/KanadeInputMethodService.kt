package dev.mooma.kanade

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.UnderlineSpan
import dev.mooma.kanade.core.ImeCommand
import dev.mooma.kanade.core.InputEngine
import dev.mooma.kanade.core.KeyInput
import dev.mooma.kanade.core.convert.Reranker
import dev.mooma.kanade.core.dict.Snippets
import dev.mooma.kanade.core.dict.UserDictionary
import dev.mooma.kanade.zenz.ZenzModel
import java.util.concurrent.Executors

/**
 * 物理キーボード専用の IME。画面キーボードは出さず、キーをすべて onKeyDown/onKeyUp で受ける。
 * 入力の判断は [InputEngine]（core）に任せ、ここは Android との受け渡しと、zenz を裏で回すことだけをする。
 * 候補は変換中だけ画面下の帯（[CandidateBar]）に出す。
 */
class KanadeInputMethodService : InputMethodService() {
    private lateinit var settings: KanadeSettings
    private lateinit var keyLog: KeyLog
    private val engine = InputEngine()
    private val mapper = KeyMapper(emptySet())

    private var candidateSource: dev.mooma.kanade.core.convert.DictionaryCandidateSource? = null
    private lateinit var userDictionaryStore: TextStore
    private var userDictionaryVersion = -1L
    private lateinit var snippetStore: TextStore
    private var snippetVersion = -1L

    /** onKeyDown で止めたキーは、対応する onKeyUp も止める */
    private val consumedDown = HashSet<Int>()

    /** zenz は1本のスレッドで動かす（読み込みも並べ替えも） */
    private val zenzThread = Executors.newSingleThreadExecutor { Thread(it, "kanade-zenz") }
    @Volatile private var zenz: ZenzModel? = null
    @Volatile private var latestRerank = 0L
    private val main = Handler(Looper.getMainLooper())

    /** IME の窓の中の帯（重ねる権限がないとき用） */
    private var bar: CandidateBar? = null
    private var barShown = false

    /** 重ねる窓の帯（権限があればこちらを使う） */
    private var overlay: OverlayBar? = null

    /** 日本語オン/オフの「あ」「A」（重ねる窓） */
    private var modeIndicator: ModeIndicator? = null

    /** アプリから最後に知らされた未確定文字列の先頭の位置（カーソルを未確定文字列の途中に置くのに使う） */
    private var composingStart = -1

    /** 未確定文字列をアプリに出さず、帯に出すアプリを使っている */
    private var previewInBar = false

    override fun onCreate() {
        super.onCreate()
        settings = KanadeSettings(this)
        keyLog = KeyLog(this)
        candidateSource = ConversionResources.candidateSource(this)
        engine.candidateSource = candidateSource
        userDictionaryStore = TextStore.userDictionary(this)
        snippetStore = TextStore.snippets(this)
        zenzThread.execute { zenz = ConversionResources.zenz(this) }
        // 前回のモードを引き継ぐ
        if (settings.lastJapanese) engine.handle(KeyInput.SetJapanese(true))
    }

    override fun onDestroy() {
        overlay?.hide()
        modeIndicator?.dismiss()
        zenzThread.shutdownNow()
        super.onDestroy()
    }

    override fun onEvaluateInputViewShown(): Boolean = false

    /**
     * Android 11 以降、IME の窓を見せるかはシステムが決め、IME が自分で showWindow しても出ない。
     * 帯を出したいときだけ requestShowSelf し、その要求をここで通す（画面キーボードは出さないので、帯だけが出る）
     */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean = barShown

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateCandidatesView(): View = CandidateBar(this, ::onCandidateTapped).also { bar = it }

    /** 候補の窓で候補を指で選んだ */
    private fun onCandidateTapped(index: Int) {
        handle(KeyInput.SelectCandidate(index))
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // カーソルの位置を受け取り、重ねる帯をその近くに置く（対応しないアプリもある）
        anchor.clear()
        currentInputConnection?.requestCursorUpdates(
            InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR,
        )
        engine.settings = settings.engineSettings()
        mapper.triggers = settings.toggleTriggers
        // 設定画面でユーザー辞書・スニペットが変わっていたら読み直す
        val v = userDictionaryStore.version()
        if (v != userDictionaryVersion) {
            userDictionaryVersion = v
            candidateSource?.userDictionary = UserDictionary.parse(userDictionaryStore.read())
        }
        val sv = snippetStore.version()
        if (sv != snippetVersion) {
            snippetVersion = sv
            engine.snippets = Snippets.parse(snippetStore.read())
        }
        engine.resetDirectTyped()
        previewInBar = attribute?.packageName in settings.previewInBarPackages
        if (!restarting) {
            engine.reset()
            hideBar()
        }
        updateStatusIcon()
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        anchor.update(cursorAnchorInfo)
    }

    /** カーソルの位置（重ねる窓を置く場所） */
    private val anchor = CursorAnchor()

    override fun onFinishInput() {
        engine.reset()
        hideBar()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // クリックなどで未確定文字列が外から確定・消去されたら、こちらの状態も捨てる
        // （帯に出しているときはアプリに未確定文字列がないので、この判定はしない）
        composingStart = candidatesStart
        if (!previewInBar && engine.isComposing && candidatesStart == -1 && candidatesEnd == -1) {
            engine.reset()
            hideBar()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val input = mapper.onKeyDown(event)
        if (input == null) {
            val consume = keyCode == KeyEvent.KEYCODE_ALT_RIGHT && mapper.consumesRightAlt()
            log(event, if (consume) "hold" else "pass")
            if (consume) {
                consumedDown += keyCode
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        val consumed = handle(input)
        log(event, "$input consumed=$consumed")
        if (consumed) {
            consumedDown += keyCode
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val input = mapper.onKeyUp(event)
        if (input != null) handle(input)
        val consumed = consumedDown.remove(keyCode)
        log(event, if (input != null) "$input" else if (consumed) "consumed" else "pass")
        return consumed || super.onKeyUp(keyCode, event)
    }

    private fun handle(input: KeyInput): Boolean {
        if (input is KeyInput.Text && engine.japanese && !engine.isComposing && !previewInBar) {
            // 入力の始まりに、カーソルの前の文を言語モデルの文脈として取る
            engine.leftContext = currentInputConnection?.getTextBeforeCursor(InputEngine.MAX_CONTEXT, 0)?.toString().orEmpty()
        }
        val result = engine.handle(input)
        apply(result.commands)
        return result.consumed
    }

    private fun apply(commands: List<ImeCommand>) {
        if (commands.isEmpty()) return
        val ic = currentInputConnection
        ic?.beginBatchEdit()
        for (command in commands) {
            when (command) {
                is ImeCommand.SetComposing ->
                    if (previewInBar) {
                        if (!engine.isConverting) {
                            if (command.text.isEmpty()) hideBar() else showBar { it.showPreview(command.text, command.cursor) }
                        }
                    } else if (command.text.isEmpty()) {
                        ic?.setComposingText("", 1)
                        ic?.finishComposingText()
                    } else {
                        ic?.setComposingText(styled(command), 1)
                        if (command.cursor >= 0 && ic != null) placeCursorInComposing(ic, command.text.length, command.cursor)
                    }
                is ImeCommand.Commit -> {
                    ic?.commitText(command.text, 1)
                    if (previewInBar) hideBar()
                }
                is ImeCommand.ModeChanged -> {
                    settings.lastJapanese = command.japanese
                    updateStatusIcon()
                    showModeIndicator(command.japanese)
                }
                is ImeCommand.ShowCandidates -> showBar(command.anchorOffset) {
                    it.showCandidates(command.candidates, command.selected, if (previewInBar) engine.composingText else null, command.labels)
                }
                ImeCommand.HideCandidates -> if (previewInBar && engine.isComposing) {
                    showBar { it.showPreview(engine.composingText) }
                } else {
                    hideBar()
                }
                is ImeCommand.RequestRerank -> requestRerank(command)
                is ImeCommand.ReplaceBeforeCursor -> {
                    // 直接入力のスニペット。カーソルの前が本当にキーのときだけ消す（カーソルを動かしていたら、打った文字だけ入れる）
                    val before = ic?.getTextBeforeCursor(command.expected.length, 0)?.toString()
                    if (before == command.expected) {
                        ic?.deleteSurroundingText(command.expected.length, 0)
                        ic?.commitText(command.replacement, 1)
                    } else {
                        ic?.commitText(command.typed.toString(), 1)
                    }
                }
            }
        }
        ic?.endBatchEdit()
    }

    /** 並べ替えを裏で回す。新しい要求が来たら、走っている古い推論は止める */
    private fun requestRerank(request: ImeCommand.RequestRerank) {
        latestRerank = request.id
        zenz?.cancel()
        zenzThread.execute {
            if (request.id != latestRerank) return@execute
            val model = zenz ?: return@execute
            val ranked = runCatching {
                Reranker(model).rerank(request.leftContext, request.reading, request.candidates)
            }.getOrNull() ?: return@execute
            main.post { apply(engine.onReranked(request.id, ranked.candidates)) }
        }
    }

    /**
     * 未確定文字列の途中にカーソルを置く。setComposingText はカーソルを未確定文字列の外にしか置けないので、
     * 未確定文字列の先頭の位置を知って setSelection する（先頭が分からなければ、アプリの文字列から求める）
     */
    private fun placeCursorInComposing(ic: android.view.inputmethod.InputConnection, length: Int, cursor: Int) {
        var start = composingStart
        if (start < 0) {
            val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return
            start = extracted.startOffset + extracted.selectionEnd - length
        }
        ic.setSelection(start + cursor, start + cursor)
    }

    /** 変換中は文節ごとに下線を引き、選んでいる文節に色を付ける */
    private fun styled(command: ImeCommand.SetComposing): CharSequence {
        if (command.segmentLengths.isEmpty()) return command.text
        val text = SpannableString(command.text)
        var start = 0
        for ((i, len) in command.segmentLengths.withIndex()) {
            val end = (start + len).coerceAtMost(text.length)
            text.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING)
            if (i == command.focused) {
                text.setSpan(BackgroundColorSpan(FOCUS_COLOR), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or Spanned.SPAN_COMPOSING)
            }
            start = end
        }
        return text
    }

    private fun showModeIndicator(japanese: Boolean) {
        if (!OverlayBar.permitted(this)) return
        val m = modeIndicator ?: ModeIndicator(window?.window?.context ?: this, anchor).also { modeIndicator = it }
        runCatching { m.show(japanese) }.onFailure { log("mode indicator failed: $it") }
    }

    private fun showBar(anchorOffset: Int = 0, update: (CandidateBar) -> Unit) {
        if (OverlayBar.permitted(this)) {
            val o = overlay ?: OverlayBar(window?.window?.context ?: this, anchor, ::onCandidateTapped).also { overlay = it }
            runCatching { o.show(anchorOffset, update) }.onFailure { log("overlay failed: $it") }
            return
        }
        if (!barShown) {
            barShown = true
            setCandidatesViewShown(true)
            requestShowSelf(0)
            // 端末によっては IME の窓が出ない。出たかを記録し、設定画面のキー記録で確かめられるようにする
            main.postDelayed({
                val b = bar
                log("ime-window bar: created=${b != null} shown=${b?.isShown} height=${b?.height} window=${window?.window?.decorView?.isShown}")
            }, 500)
        }
        bar?.let(update)
    }

    private fun hideBar() {
        overlay?.hide()
        if (barShown) {
            barShown = false
            setCandidatesViewShown(false)
            requestHideSelf(0)
        }
    }

    private fun updateStatusIcon() {
        if (engine.japanese) showStatusIcon(R.drawable.ic_mode_ja) else hideStatusIcon()
    }

    private fun log(event: KeyEvent, note: String) {
        if (settings.keyLogEnabled) keyLog.record(event, note)
    }

    private companion object {
        /** 変換中に選んでいる文節の背景（薄い青。明るい画面でも暗い画面でも字が読める程度に） */
        val FOCUS_COLOR = Color.argb(0x60, 0x40, 0x80, 0xE0)
    }

    private fun log(note: String) {
        if (settings.keyLogEnabled) keyLog.note(note)
    }
}
