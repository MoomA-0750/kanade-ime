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
import dev.mooma.kanade.core.voice.SpokenPunctuation
import dev.mooma.kanade.core.voice.VoiceEndpointer
import dev.mooma.kanade.whisper.WhisperModel
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
    /** 学習（前に選んだ候補）の保存先 */
    private lateinit var learningFile: java.io.File
    private var learningResetSeen = -1L

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

    /** パスワード欄など。キーに一切手を出さず（記録もせず）アプリへ渡す */
    private var secureField = false

    /** 入力欄が替わるたびに進める番号。裏で動いていた音声認識の結果を、別の入力欄に入れないため */
    private var inputGeneration = 0L

    /** 最後に確定した時刻。自分の確定に対する遅れた選択範囲の通知で、次の入力を捨てないため */
    private var lastCommitAt = 0L

    /** アプリから最後に知らされた未確定文字列の先頭の位置（カーソルを未確定文字列の途中に置くのに使う） */
    private var composingStart = -1

    /** 未確定文字列をアプリに出さず、帯に出すアプリを使っている */
    private var previewInBar = false

    override fun onCreate() {
        super.onCreate()
        settings = KanadeSettings(this)
        keyLog = KeyLog(this)
        clipboardHistory = ClipboardHistory(this)
        clipboardManager.addPrimaryClipChangedListener(clipListener)
        candidateSource = ConversionResources.candidateSource(this)
        engine.candidateSource = candidateSource
        learningFile = java.io.File(filesDir, "learning.tsv")
        userDictionaryStore = TextStore.userDictionary(this)
        snippetStore = TextStore.snippets(this)
        zenzThread.execute { zenz = ConversionResources.zenz(this) }
        // 前回のモードを引き継ぐ
        if (settings.lastJapanese) engine.handle(KeyInput.SetJapanese(true))
    }

    override fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        overlay?.hide()
        modeIndicator?.dismiss()
        zenzThread.shutdownNow()
        super.onDestroy()
    }

    /**
     * 画面キーボードを出すか。既定では物理キーボードがつながっていないときだけ出す
     * （つながっていれば、物理キーボード専用の IME として何も出さない）
     */
    override fun onEvaluateInputViewShown(): Boolean = if (!::settings.isInitialized) false else when (settings.softKeyboard) {
        "ALWAYS" -> true
        "NEVER" -> false
        else -> !hasHardwareKeyboard()
    }

    private fun hasHardwareKeyboard(): Boolean {
        val c = resources.configuration
        return c.keyboard != android.content.res.Configuration.KEYBOARD_NOKEYS &&
            c.hardKeyboardHidden != android.content.res.Configuration.HARDKEYBOARDHIDDEN_YES
    }

    /** 画面キーボード（QWERTY） */
    private var keyboardView: SoftKeyboardView? = null

    /** 絵文字パネル */
    private var emojiPanel: EmojiPanelView? = null

    /** IME の画面の入れ物（ツールバー・キーボード・パネルを出し分ける） */
    private var host: InputViewHost? = null

    /** フリック配列の画面キーボード */
    private var flickView: FlickKeyboardView? = null

    override fun onCreateInputView(): View {
        val keyboard = SoftKeyboardView(
            this, ::softKey, { handle(KeyInput.ToggleJapanese) }, ::softVoice, ::showEmojiPanel,
            onClipboard = ::showClipboard, onSwitchLayout = ::switchLayout, onFloat = ::toggleFloat,
            onGlide = ::onGlide, onText = { flickActions.text(it) },
        )
        keyboardView = keyboard
        val panel = EmojiPanelView(
            this,
            onEmoji = ::commitEmoji,
            onBackspace = { softKey(KeyEvent.KEYCODE_DEL, 0) },
            onSpace = { softKey(KeyEvent.KEYCODE_SPACE, 0) },
            onClose = ::hideEmojiPanel,
            onSearchMode = ::emojiSearchMode,
            onSearchCandidate = ::onCandidateTapped,
        )
        emojiPanel = panel
        val flick = FlickKeyboardView(this, flickActions)
        flickView = flick
        val h = InputViewHost(this, keyboard, flick, panel, createClipboardView())
        host = h
        h.flickLayout = settings.softKeyboardLayout == "FLICK"
        keyboard.glideLayout = settings.softKeyboardLayout == "GLIDE"
        val f = FloatingFrame(
            this, h,
            onMoved = { x, y, w ->
                settings.floatX = x
                settings.floatY = y
                settings.floatWidth = w
            },
            onDock = ::toggleFloat,
        )
        frame = f
        applyKeyboardSettings()
        return f
    }

    /** いちばん外側（フロートのときはキーボードを浮かせる） */
    private var frame: FloatingFrame? = null

    /** 画面キーボードを浮かせる・固定に戻す */
    private fun toggleFloat() {
        settings.softKeyboardFloating = !settings.softKeyboardFloating
        applyKeyboardSettings()
    }

    /**
     * フロートのときは、アプリの画面を縮めず（キーボードの分を空けない）、浮いているキーボードの上だけを IME の当たり判定にする
     * （外を触ると下のアプリに届く）
     */
    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        val f = frame ?: return
        if (!f.floating || !isInputViewShown) return
        val h = window?.window?.decorView?.height ?: return
        outInsets.contentTopInsets = h
        outInsets.visibleTopInsets = h
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(f.touchableRect)
    }

    /** フリック配列のキーでしてもらうこと */
    private val flickActions = object : FlickKeyboardView.Actions {
        override fun text(s: String) {
            for (c in s) {
                if (handle(KeyInput.Text(c))) continue
                // 直接入力（英字・数字）では IME が使わないので、そのまま入れる（絵文字の検索中は検索へ）
                if (emojiSearching) emojiPanel?.typeSearch(c) else currentInputConnection?.commitText(c.toString(), 1)
            }
        }

        override fun replaceLast(s: String) {
            // 入力中なら読みの中で置き換える（消してから打つと、入力中の文字が一瞬空になってアプリとずれる）
            if (engine.isComposing && handle(KeyInput.ReplaceLast(s))) return
            when {
                emojiSearching -> emojiPanel?.backspaceSearch()
                else -> currentInputConnection?.deleteSurroundingTextInCodePoints(1, 0)
            }
            text(s)
        }

        override fun lastChar(): Char? = when {
            engine.isComposing -> engine.lastTypedChar
            emojiSearching -> null
            else -> currentInputConnection?.getTextBeforeCursor(1, 0)?.lastOrNull()
        }

        override fun key(keyCode: Int) = softKey(keyCode, 0)
        override fun setJapanese(on: Boolean) {
            handle(KeyInput.SetJapanese(on))
        }
        override fun emoji() = showEmojiPanel()
        override fun clipboard() = showClipboard()
        override fun voice(down: Boolean) = softVoice(down)
        override fun switchToQwerty() = switchLayout()
        override fun toggleFloat() = this@KanadeInputMethodService.toggleFloat()
    }

    /** クリップボードの履歴（コピーされるたびに足す。設定で切れる） */
    private lateinit var clipboardHistory: ClipboardHistory
    private val clipboardManager by lazy { getSystemService(android.content.ClipboardManager::class.java) }
    /** Kanade 自身がクリップボードに入れたもの（画像の貼り付けの代わり）は、履歴に足さない */
    private var ignoreNextClip = false
    private val clipListener = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        if (ignoreNextClip) {
            ignoreNextClip = false
            return@OnPrimaryClipChangedListener
        }
        if (!settings.clipboardHistory) return@OnPrimaryClipChangedListener
        // 画像を写すことがあるので、裏のスレッドで
        saveThread.execute { runCatching { clipboardHistory.capture(clipboardManager) } }
    }
    private var clipboardPanel: ClipboardPanelView? = null

    private fun createClipboardView(): View =
        ClipboardPanelView(this, clipboardHistory, onPaste = ::pasteClip, onClose = ::hideEmojiPanel).also { clipboardPanel = it }

    /** クリップボードの項目を貼り付ける。画像は、入力欄が受け付けるなら画像として、受け付けなければクリップボードに入れて知らせる */
    private fun pasteClip(item: ClipboardHistory.Item) {
        apply(engine.commitAll())
        val ic = currentInputConnection ?: return
        if (item.text != null) {
            ic.commitText(item.text, 1)
            lastCommitAt = android.os.SystemClock.uptimeMillis()
            return
        }
        val uri = clipboardHistory.imageUri(item) ?: return
        val mime = item.mime ?: "image/png"
        val accepted = currentInputEditorInfo?.contentMimeTypes.orEmpty().any { android.content.ClipDescription.compareMimeTypes(mime, it) }
        val committed = accepted && runCatching {
            val info = android.view.inputmethod.InputContentInfo(uri, android.content.ClipDescription("Kanade", arrayOf(mime)), null)
            ic.commitContent(info, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        }.getOrDefault(false)
        if (!committed) {
            ignoreNextClip = true
            clipboardManager.setPrimaryClip(android.content.ClipData.newUri(contentResolver, "Kanade", uri))
            clipboardPanel?.showMessage("この入力欄は画像を直接受け付けないので、クリップボードに入れました。入力欄を長押しして貼り付けてください。")
        }
    }

    private fun showClipboard() {
        apply(engine.commitAll())
        clipboardPanel?.showMessage(if (settings.clipboardHistory) null else "クリップボードの履歴は、設定でオフになっています。")
        clipboardPanel?.refresh()
        host?.show(InputViewHost.Mode.CLIPBOARD)
    }

    /** ツールバーの配列の切り替え（QWERTY ⇄ フリック）。次から使う配列として覚える */
    /** 配列を順に替える（使う配列の中で、グライド → フルキー → テンキー → グライド） */
    private fun switchLayout() {
        // 使う配列の中だけを、グライド → フルキー → テンキーの順に回る
        val enabled = LAYOUTS.filter { it in settings.enabledLayouts }
        val now = settings.softKeyboardLayout
        settings.softKeyboardLayout = enabled[(enabled.indexOf(now) + 1) % enabled.size]
        applyKeyboardSettings()
    }

    /** グライドの読み取り（語の一覧は、グライドの配列を初めて出したときに裏で読む） */
    private val glideThread = Executors.newSingleThreadExecutor { Thread(it, "kanade-glide") }
    @Volatile private var glideJapanese: dev.mooma.kanade.core.glide.GlideDecoder? = null
    @Volatile private var glideEnglish: dev.mooma.kanade.core.glide.GlideDecoder? = null
    private var glideLoading = false
    /** グライドで入れて残した語（よく使う語を先に出す） */
    private var glideLearning = dev.mooma.kanade.core.glide.GlideLearning()
    private val glideLearningFile by lazy { java.io.File(filesDir, "glide-learning.tsv") }

    private fun saveGlideLearning() {
        val text = glideLearning.write()
        saveThread.execute { runCatching { glideLearningFile.writeText(text) } }
    }

    private fun loadGlide() {
        if (glideLoading) return
        glideLoading = true
        glideThread.execute {
            runCatching { glideLearningFile.takeIf { it.exists() }?.let { glideLearning = dev.mooma.kanade.core.glide.GlideLearning.parse(it.readText()) } }
            runCatching {
                val ja = assets.open("glide-ja.tsv").bufferedReader().useLines { dev.mooma.kanade.core.glide.GlideLexicon.japanese(it) }
                glideJapanese = dev.mooma.kanade.core.glide.GlideDecoder(ja, dev.mooma.kanade.core.glide.GlideParams.JAPANESE)
                val en = assets.open("glide-en.txt").bufferedReader().useLines { dev.mooma.kanade.core.glide.GlideLexicon.english(it) }
                glideEnglish = dev.mooma.kanade.core.glide.GlideDecoder(en, dev.mooma.kanade.core.glide.GlideParams.ENGLISH)
                log("glide loaded ja=${ja.size} en=${en.size}")
            }.onFailure { log("glide load failed: $it") }
        }
    }

    /**
     * 直前にグライドで入れたもの。候補の帯で別の語を選ぶと、これを置き換える。
     * [text] は入れた文字（英語は前に足した空白も含む）、[japanese] は入力中の読みに入れたか
     */
    private class GlideResult(val candidates: List<String>, var text: String, val japanese: Boolean, val prefix: String)
    private var lastGlide: GlideResult? = null
    /** グライドの語を置き換えている途中（そのあいだの入力では、グライドの候補を消さない） */
    private var replacingGlide = false

    /** グライドの配列で文字のキーの上をなぞった。裏で読み取って、いちばん合う語を入れる */
    private fun onGlide(input: SoftKeyboardView.GlideInput) {
        val japanese = engine.japanese
        val decoder = (if (japanese) glideJapanese else glideEnglish) ?: run {
            loadGlide()
            showIndicatorText("グライドの準備をしています。少し待ってからもう一度なぞってください", 2000)
            return
        }
        // 日本語は、入力中の読みのあとにつなげたときの自然さで並べ直す（読みの終わりの方だけを使う）
        val context = if (japanese && engine.isComposing && !engine.isConverting) engine.composingReading.takeLast(GLIDE_CONTEXT) else ""
        val learning = glideLearning
        glideThread.execute {
            val result = runCatching {
                val found = decoder.decode(input.xs, input.ys, input.keys, input.keyWidth, limit = GLIDE_CANDIDATES, bonus = learning::bonus, keyHeight = input.keyHeight)
                if (!japanese) found
                else {
                    val converter = ConversionResources.converter(this)
                    val costs = HashMap<String, Int?>()
                    fun cost(reading: String) = costs.getOrPut(reading) { converter.convert(reading, n = 1).firstOrNull()?.cost }
                    decoder.rerank(found) { word ->
                        dev.mooma.kanade.core.glide.GlideLexicon.japanesePrior(context, word, ::cost)
                    }
                }
            }.getOrDefault(emptyList())
            main.post {
                val words = result.map { c ->
                    if (!japanese && input.shift) c.output.replaceFirstChar { it.uppercaseChar() } else c.output
                }.distinct()
                if (words.isNotEmpty()) insertGlide(words, japanese)
            }
        }
    }

    private fun insertGlide(words: List<String>, japanese: Boolean) {
        val word = words.first()
        if (japanese) {
            // 読みとして入れる（そのあとはふつうに Space で変換する）
            for (c in word) handle(KeyInput.Text(c))
            lastGlide = GlideResult(words, word, true, "")
            glideLearning.record(word)
            saveGlideLearning()
        } else if (emojiSearching) {
            for (c in word) emojiPanel?.typeSearch(c)
            lastGlide = null
            return
        } else {
            // 英語: 前が文字・数字・句読点なら、間に空白を入れる
            val before = currentInputConnection?.getTextBeforeCursor(1, 0)?.lastOrNull()
            val prefix = if (before != null && (before.isLetterOrDigit() || before in ".,!?;:)")) " " else ""
            currentInputConnection?.commitText(prefix + word, 1)
            lastCommitAt = android.os.SystemClock.uptimeMillis()
            lastGlide = GlideResult(words, prefix + word, false, prefix)
            glideLearning.record(word)
            saveGlideLearning()
        }
        if (words.size > 1) showBar { it.showCandidates(words, 0) }
    }

    /** 直前にグライドで入れた語を丸ごと消す。消せなければ（カーソルを動かしたなど）false で、ふつうの Backspace にする */
    private fun deleteGlide(): Boolean {
        val g = lastGlide ?: return false
        lastGlide = null
        hideBar()
        if (g.japanese) {
            if (!engine.isComposing || !engine.composingReading.endsWith(g.text)) return false
            replacingGlide = true
            try {
                // 読みがグライドの語だけなら、入力中の文字を取り消す
                if (engine.composingReading == g.text) handle(KeyInput.Escape) else handle(KeyInput.ReplaceLast("", g.text.length))
            } finally {
                replacingGlide = false
            }
        } else {
            val ic = currentInputConnection ?: return false
            if (ic.getTextBeforeCursor(g.text.length, 0)?.toString() != g.text) return false
            ic.deleteSurroundingText(g.text.length, 0)
        }
        glideLearning.forget(g.text.trim())
        saveGlideLearning()
        return true
    }

    /** グライドのあとの候補の帯で、別の語を選んだ */
    private fun replaceGlide(index: Int): Boolean {
        val g = lastGlide ?: return false
        val word = g.candidates.getOrNull(index) ?: return false
        val old = g.text.removePrefix(g.prefix)
        if (g.japanese) {
            // 入力中の読みの終わりが、グライドで入れたかなのままなら置き換える
            if (!engine.isComposing || !engine.composingReading.endsWith(g.text)) return false.also { lastGlide = null }
            replacingGlide = true
            try {
                handle(KeyInput.ReplaceLast(word, g.text.length))
            } finally {
                replacingGlide = false
            }
            g.text = word
        } else {
            val ic = currentInputConnection ?: return false
            if (ic.getTextBeforeCursor(g.text.length, 0)?.toString() != g.text) return false.also { lastGlide = null }
            ic.deleteSurroundingText(g.text.length, 0)
            ic.commitText(g.prefix + word, 1)
            g.text = g.prefix + word
        }
        glideLearning.forget(old)
        glideLearning.record(word)
        saveGlideLearning()
        showBar { it.showCandidates(g.candidates, index) }
        return true
    }

    /** 絵文字を検索しているか（このあいだ、IME の変換の結果はアプリではなく検索の欄へ回す） */
    private val emojiSearching: Boolean get() = host?.mode == InputViewHost.Mode.EMOJI_SEARCH

    /** 検索中の IME の出力を、検索の欄に回す（変換・候補はふつうの入力と同じに効く） */
    private fun applyToSearch(commands: List<ImeCommand>) {
        val panel = emojiPanel ?: return
        for (command in commands) {
            when (command) {
                is ImeCommand.SetComposing -> panel.setSearchComposing(command.text)
                is ImeCommand.Commit -> panel.commitSearch(command.text)
                is ImeCommand.ShowCandidates -> panel.showSearchCandidates(command.candidates, command.selected)
                ImeCommand.HideCandidates -> panel.hideSearchCandidates()
                is ImeCommand.RequestRerank -> requestRerank(command)
                is ImeCommand.ModeChanged -> {
                    settings.lastJapanese = command.japanese
                    updateStatusIcon()
                }
                is ImeCommand.ReplaceBeforeCursor -> panel.commitSearch(command.typed.toString())
            }
        }
    }

    /** 絵文字の検索モード: パネルを検索の欄と結果だけにし、その下にキーボードを出す（打った文字は検索に回す） */
    private fun emojiSearchMode(on: Boolean) {
        // 検索の欄で入力中だった文字は捨てる（アプリへは入れない）
        if (!on && engine.isComposing) engine.reset()
        host?.show(if (on) InputViewHost.Mode.EMOJI_SEARCH else InputViewHost.Mode.EMOJI)
    }

    /** 絵文字パネルを開く（ツールバー＋キーボードと同じ高さ） */
    private fun showEmojiPanel() {
        val panel = emojiPanel ?: return
        // 入力中の文字があれば、アプリに確定してから開く
        apply(engine.commitAll())
        panel.japanese = engine.japanese
        host?.show(InputViewHost.Mode.EMOJI)
    }

    /** パネルを閉じてキーボードに戻る */
    private fun hideEmojiPanel() {
        emojiPanel?.let { if (it.searching) it.endSearch() }
        host?.show(InputViewHost.Mode.KEYBOARD)
    }

    /**
     * 絵文字の検索中に、IME が使わなかったキー（直接入力の文字・入力中でないときの Backspace など）を検索の言葉へ回す
     */
    private fun searchFallback(e: KeyEvent) {
        val panel = emojiPanel ?: return
        if (e.action != KeyEvent.ACTION_DOWN || KeyEvent.isModifierKey(e.keyCode)) return
        when (e.keyCode) {
            KeyEvent.KEYCODE_DEL -> panel.backspaceSearch()
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_ENTER -> panel.endSearch()
            else -> {
                val c = e.unicodeChar
                if (c > 0 && !e.isCtrlPressed && !e.isAltPressed && !e.isMetaPressed) panel.typeSearch(c.toChar())
            }
        }
    }

    /** 絵文字を入れる。入力中の文字があれば先に確定する */
    private fun commitEmoji(emoji: String) {
        apply(engine.commitAll())
        currentInputConnection?.commitText(emoji, 1)
        lastCommitAt = android.os.SystemClock.uptimeMillis()
    }

    private fun applyKeyboardSettings() {
        // 設定画面で配列を変えていたら、それにする
        host?.let { h ->
            val flick = settings.softKeyboardLayout == "FLICK"
            if (h.flickLayout != flick) h.flickLayout = flick
        }
        val floating = settings.softKeyboardFloating
        frame?.let { f ->
            f.posX = settings.floatX
            f.posY = settings.floatY
            f.widthFraction = settings.floatWidth
            f.floating = floating
            f.requestLayout()
        }
        keyboardView?.floating = floating
        flickView?.floating = floating
        keyboardView?.let { k ->
            k.glideLayout = settings.softKeyboardLayout == "GLIDE"
            if (k.glideLayout) loadGlide()
            k.japanese = engine.japanese
            k.heightPercent = settings.softKeyboardHeight
            k.extraBottomDp = settings.softKeyboardBottom
        }
        flickView?.let { f ->
            f.syncJapanese(engine.japanese)
            f.toggleInput = settings.flickToggle
            f.heightPercent = settings.softKeyboardHeight
            f.extraBottomDp = settings.softKeyboardBottom
        }
    }

    /** 画面キーボードの 🎤（押している間だけ録音。物理キーボードの録音キーと同じ） */
    private fun softVoice(down: Boolean) {
        if (secureField) return
        voiceKey(down)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 物理キーボードをつないだ・外したら、画面キーボードを出し直す
        updateInputViewShown()
        if (!onEvaluateInputViewShown() && !barShown) requestHideSelf(0)
    }

    /**
     * 画面キーボードのキー。物理キーボードと同じキーイベントの並びにして、同じ流れで処理する。
     * 修飾（Shift・Ctrl・Alt・Meta）は、物理キーボードと同じく修飾キー自体の押す・離すも前後に送る
     * （Android の入力欄は Shift＋矢印の選択などを修飾キーの押下で見る。アプリのショートカットもこれで物理キーボードと同じに効く）。
     * IME で使わなかったキーは、そのままアプリへ送る
     */
    private fun softKey(keyCode: Int, meta: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        fun event(action: Int, code: Int, m: Int) = KeyEvent(
            now, now, action, code, 0, m, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
            KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, android.view.InputDevice.SOURCE_KEYBOARD,
        )
        val ic = currentInputConnection
        fun send(e: KeyEvent) {
            val handled = if (e.action == KeyEvent.ACTION_DOWN) handleKeyDown(e) else handleKeyUp(e)
            // 絵文字の検索中は、IME が使わなかったキーもアプリへは送らず検索に回す
            if (!handled) if (emojiSearching) searchFallback(e) else ic?.sendKeyEvent(e)
        }
        // 押した修飾を順に押し、キーを押して離し、修飾を逆順に離す
        val held = SOFT_MODIFIERS.filter { (_, bits) -> meta and bits != 0 }
        var m = 0
        for ((code, bits) in held) {
            m = m or bits
            send(event(KeyEvent.ACTION_DOWN, code, m))
        }
        send(event(KeyEvent.ACTION_DOWN, keyCode, meta))
        send(event(KeyEvent.ACTION_UP, keyCode, meta))
        for ((code, bits) in held.asReversed()) {
            m = m and bits.inv()
            send(event(KeyEvent.ACTION_UP, code, m))
        }
    }

    /**
     * Android 11 以降、IME の窓を見せるかはシステムが決め、IME が自分で showWindow しても出ない。
     * 帯を出したいときだけ requestShowSelf し、その要求をここで通す（画面キーボードは出さないので、帯だけが出る）
     */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean = onEvaluateInputViewShown() || barShown

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateCandidatesView(): View = CandidateBar(this, ::onCandidateTapped).also { bar = it }

    /** 候補の窓で候補を指で選んだ */
    private fun onCandidateTapped(index: Int) {
        if (lastGlide != null) {
            // 帯を押した処理の途中で帯を消して出し直すと出なくなるので、押した処理が終わってから置き換える
            main.post { if (!replaceGlide(index)) handle(KeyInput.SelectCandidate(index)) }
            return
        }
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
        syncLearning()
        previewInBar = attribute?.packageName in settings.previewInBarPackages
        inputGeneration++
        secureField = isSecure(attribute)
        // 学習しないよう求める入力欄（シークレットモードなど）では学習を止める
        candidateSource?.learningPaused = secureField ||
            ((attribute?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        // アプリごとの挙動（カーソルが出ないなど）を調べるため、入力欄の情報を記録する
        log(
            "start-input pkg=${attribute?.packageName} restarting=$restarting type=0x${Integer.toHexString(attribute?.inputType ?: 0)} " +
                "ime=0x${Integer.toHexString(attribute?.imeOptions ?: 0)} sel=${attribute?.initialSelStart},${attribute?.initialSelEnd}",
        )
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
    private val anchor = CursorAnchor().apply {
        keyboardTop = {
            // 浮かせているときは、キーボードが画面の下半分にあるときだけ、その上端を下の端として扱う
            val f = frame
            if (f != null && f.floating) f.floatingTopOnScreen()?.takeIf { it > resources.displayMetrics.heightPixels / 2 }
            else host?.takeIf { isInputViewShown && it.isShown && it.height > 0 }?.let {
                val loc = IntArray(2)
                it.getLocationOnScreen(loc)
                loc[1]
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applyKeyboardSettings()
        hideEmojiPanel()
        log("start-input-view pkg=${info?.packageName} restarting=$restarting")
    }

    override fun onFinishInput() {
        log("finish-input")
        inputGeneration++
        saveLearning()
        if (VoiceInput.isRecording) {
            VoiceInput.cancel()
            main.removeCallbacks(partialTick)
            main.removeCallbacks(continuousTick)
            modeIndicator?.dismiss()
            partialText = null
        }
        // 入力欄が替わったら聞き続けるモードも終える（別の欄に入れない）
        continuous = false
        keyboardView?.voiceActive = false
        flickView?.voiceActive = false
        endpointer.reset()
        pendingVoice.clear()
        voiceSegments.clear()
        voiceInField = false
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
        log("selection old=$oldSelStart,$oldSelEnd new=$newSelStart,$newSelEnd composing=$candidatesStart,$candidatesEnd")
        // 自分が確定した直後の通知は、確定より前の状態を報告していることがあるので見ない（遅れて届くと次の入力を捨ててしまう）
        val justCommitted = android.os.SystemClock.uptimeMillis() - lastCommitAt < STALE_SELECTION_MS
        if (!previewInBar && !justCommitted && engine.isComposing && candidatesStart == -1 && candidatesEnd == -1) {
            engine.reset()
            hideBar()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleKeyDown(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handleKeyUp(event) || super.onKeyUp(keyCode, event)

    /** キーを IME で使ったら true（物理キーボードと画面キーボードで共通） */
    private fun handleKeyDown(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (secureField) return false
        if (keyCode == settings.voiceKey) {
            // 押している間だけ録音する（押しっぱなしの繰り返しは無視）。短く押すと聞き続ける
            if (event.repeatCount == 0) voiceKey(true)
            log(event, "voice")
            return true
        }
        // 聞き続けるモードの途中の文字を入力欄に出していたら、キーがそれに効かないよう先に消す（話している間は出し直す）
        clearVoiceInField()
        val input = mapper.onKeyDown(event)
        if (input == null) {
            val consume = keyCode == KeyEvent.KEYCODE_ALT_RIGHT && mapper.consumesRightAlt()
            log(event, if (consume) "hold" else "pass")
            if (consume) {
                consumedDown += keyCode
                return true
            }
            return false
        }
        val consumed = handle(input)
        log(event, "$input consumed=$consumed")
        if (consumed) {
            consumedDown += keyCode
            return true
        }
        return false
    }

    private fun handleKeyUp(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (secureField) return false
        if (keyCode == settings.voiceKey) {
            voiceKey(false)
            log(event, "voice")
            return true
        }
        val input = mapper.onKeyUp(event)
        if (input != null) handle(input)
        val consumed = consumedDown.remove(keyCode)
        log(event, if (input != null) "$input" else if (consumed) "consumed" else "pass")
        return consumed
    }

    private fun handle(input: KeyInput): Boolean {
        // グライドの直後の Backspace は、入れた語を丸ごと消す（Gboard と同じ）
        if (input == KeyInput.Backspace && lastGlide != null && !replacingGlide && deleteGlide()) return true
        // グライドの候補を出しているあいだにほかのキーを打ったら、グライドの候補はおしまい
        if (lastGlide != null && input !is KeyInput.SelectCandidate && !replacingGlide) {
            lastGlide = null
            hideBar()
        }
        if (input is KeyInput.Text && engine.japanese && !engine.isComposing) {
            // 入力の始まりに、カーソルの前後の文を言語モデルの文脈として取る（端末アプリなど帯に出すアプリでは取らない）
            engine.leftContext = if (previewInBar || emojiSearching) "" else currentInputConnection?.getTextBeforeCursor(InputEngine.MAX_CONTEXT, 0)?.toString().orEmpty()
            engine.rightContext = if (previewInBar || emojiSearching) "" else currentInputConnection?.getTextAfterCursor(InputEngine.MAX_CONTEXT, 0)?.toString().orEmpty()
        }
        val result = engine.handle(input)
        apply(result.commands)
        return result.consumed
    }

    private fun apply(commands: List<ImeCommand>) {
        if (commands.isEmpty()) return
        if (emojiSearching) {
            applyToSearch(commands)
            return
        }
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
                    lastCommitAt = android.os.SystemClock.uptimeMillis()
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
        // 聞き続けるモードで、入力中の文字の確定を待っていた音声の文を入れ、途中の文字を出し直す
        if (continuous || voiceSegments.isNotEmpty() || pendingVoice.isNotEmpty()) {
            if (!engine.isComposing) {
                flushPendingVoice()
                showVoiceDisplay()
            }
        }
    }

    /** 並べ替えを裏で回す。新しい要求が来たら、走っている古い推論は止める */
    private fun requestRerank(request: ImeCommand.RequestRerank) {
        latestRerank = request.id
        zenz?.cancel()
        zenzThread.execute {
            if (request.id != latestRerank) return@execute
            val model = zenz ?: return@execute
            val ranked = runCatching {
                Reranker(model).rerank(request.leftContext, request.reading, request.candidates, rightContext = request.rightContext)
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

    /** 学習を設定に合わせる: 切られていれば使わず、設定画面で消されていれば捨てる。初めは保存したものを読む */
    private fun syncLearning() {
        val source = candidateSource ?: return
        if (!settings.learningEnabled) {
            source.learning = null
            return
        }
        val resetAt = settings.learningResetAt
        if (source.learning == null || resetAt != learningResetSeen) {
            if (resetAt != learningResetSeen && learningResetSeen != -1L) learningFile.delete()
            learningResetSeen = resetAt
            source.learning = dev.mooma.kanade.core.dict.LearningHistory.parse(
                runCatching { learningFile.readText() }.getOrDefault(""),
            )
        }
    }

    /** 学習が変わっていたら保存する（入力欄を離れたとき） */
    private fun saveLearning() {
        val source = candidateSource ?: return
        val h = source.learning ?: return
        if (!source.learningChanged) return
        source.learningChanged = false
        val text = h.write()
        // 1本のスレッドで順に書く（続けて保存したとき、同じ一時ファイルに同時に書かないように）
        saveThread.execute {
            val tmp = java.io.File(learningFile.path + ".tmp")
            runCatching {
                tmp.writeText(text)
                tmp.renameTo(learningFile)
            }
        }
    }

    /** 話しながら途中の文字を出しているか（ReazonSpeech が使えるとき） */
    private var voiceStreaming = false
    private var partialBusy = false
    private var partialText: String? = null
    private val partialTick = object : Runnable {
        override fun run() {
            if (!VoiceInput.isRecording || !voiceStreaming) return
            // 聞き続けるモードでは、話している間だけ途中の文字を出す
            if (!partialBusy && (!continuous || endpointer.inSpeech)) {
                partialBusy = true
                SherpaRecognizer.reazon.recognize(this@KanadeInputMethodService, VoiceInput.snapshot(), settings.voiceThreads) { text ->
                    main.post {
                        partialBusy = false
                        if (VoiceInput.isRecording && text != null && (!continuous || endpointer.inSpeech)) showPartial(text)
                    }
                }
            }
            main.postDelayed(this, PARTIAL_INTERVAL_MS)
        }
    }

    /**
     * 聞き続けるモード（録音キー・🎤 を短く押したとき。iPhone や Pixel の音声入力と同じ）。
     * 録音を続け、話し終わり（無音）ごとに区切って文字にし、カーソルの位置に入れる。キーボードでの入力と並べて使える
     * （入力中の文字があれば、それが確定してから入れる）。もう一度押すか、1分話さなければ終わる
     */
    private var continuous = false
    private val endpointer = VoiceEndpointer()
    private var vadIndex = 0
    private var lastSpeechAt = 0L
    private var voiceKeyDownAt = 0L
    private var voiceKeyStopped = false
    /** 入力中の文字があって入れられずに待っている、認識した文 */
    private val pendingVoice = StringBuilder()
    /** 話し終わって確定の認識を待っている区切りの、途中の文字（古い順） */
    private val voiceSegments = ArrayDeque<String>()
    /** 入力欄の未確定文字列が、聞き続けるモードの途中の文字か */
    private var voiceInField = false
    private val continuousTick = object : Runnable {
        override fun run() {
            if (!continuous || !VoiceInput.isRecording) return
            val n = VoiceInput.sampleCount()
            if (n > vadIndex) {
                val ms = (n - vadIndex) * 1000 / WhisperModel.SAMPLE_RATE
                val event = endpointer.feed(VoiceInput.rms(vadIndex, n), ms)
                vadIndex = n
                val now = android.os.SystemClock.uptimeMillis()
                when (event) {
                    VoiceEndpointer.Event.START -> lastSpeechAt = now
                    VoiceEndpointer.Event.END -> {
                        lastSpeechAt = now
                        voiceSegments.addLast(partialText.orEmpty())
                        partialText = null
                        val pcm = VoiceInput.take()
                        vadIndex = 0
                        recognizeUtterance(pcm, continuousMode = true)
                    }
                    null -> if (!endpointer.inSpeech) {
                        // 話していない間の音は溜めない（話し始めの直前の 0.5 秒だけ残す）
                        val keep = WhisperModel.SAMPLE_RATE / 2
                        if (n > keep * 2) {
                            VoiceInput.trimTo(keep)
                            vadIndex = keep
                        }
                    }
                }
                if (!endpointer.inSpeech && now - lastSpeechAt > CONTINUOUS_IDLE_MS) {
                    stopContinuous("🎤 終わりました（1分話さなかったため）")
                    return
                }
            }
            main.postDelayed(this, VAD_INTERVAL_MS)
        }
    }

    /** 録音キー・🎤 を押した（[down]）・離した。長押しは押している間だけ録音、短く押すと聞き続けるモードの入り・切り */
    private fun voiceKey(down: Boolean) {
        val now = android.os.SystemClock.uptimeMillis()
        if (down) {
            if (continuous) {
                stopContinuous("🎤 終わりました")
                voiceKeyStopped = true
                return
            }
            voiceKeyStopped = false
            startVoice()
            // 録音の準備に時間がかかることがあるので、短く押したかは録音が始まってから数える
            voiceKeyDownAt = android.os.SystemClock.uptimeMillis()
            return
        }
        if (voiceKeyStopped) {
            voiceKeyStopped = false
            return
        }
        if (!VoiceInput.isRecording) return
        if (now - voiceKeyDownAt < TAP_MS) startContinuous() else stopVoice()
    }

    private fun startContinuous() {
        continuous = true
        endpointer.reset()
        VoiceInput.trimTo(0)
        vadIndex = 0
        lastSpeechAt = android.os.SystemClock.uptimeMillis()
        partialText = null
        voiceSegments.clear()
        keyboardView?.voiceActive = true
        flickView?.voiceActive = true
        showIndicatorText("🎤 聞いています（もう一度押すと終わります）", 1500)
        main.postDelayed(continuousTick, VAD_INTERVAL_MS)
    }

    /** 聞き続けるモードを終える。話している途中の音声があれば文字にして入れる */
    private fun stopContinuous(message: String) {
        continuous = false
        keyboardView?.voiceActive = false
        flickView?.voiceActive = false
        main.removeCallbacks(continuousTick)
        main.removeCallbacks(partialTick)
        val speaking = endpointer.inSpeech
        endpointer.reset()
        val pcm = VoiceInput.stopRecording()
        if (speaking && pcm != null) {
            voiceSegments.addLast(partialText.orEmpty())
            partialText = null
            recognizeUtterance(pcm, continuousMode = true)
        } else {
            partialText = null
            showVoiceDisplay()
        }
        showIndicatorText(message, 1200)
    }

    private fun startVoice() {
        if (VoiceInput.isRecording) return
        val streamingReady = settings.voiceStreaming && SherpaRecognizer.reazon.isReady(this)
        val message = when {
            !VoiceInput.hasPermission(this) -> "マイクが許可されていません（設定画面で許可してください）"
            VoiceCatalog.finalModel(this, settings) == null -> "音声認識のモデルがありません（設定画面でダウンロードしてください）"
            !VoiceInput.start(this) -> "録音を始められませんでした"
            else -> null
        }
        if (message != null) {
            showIndicatorText(message, 2000)
            log("voice: $message")
            return
        }
        // 入力中の文字があれば先に確定し、認識した文はその後ろに入れる
        apply(engine.commitAll())
        voiceStreaming = streamingReady
        partialText = null
        showIndicatorText("🎤", 0)
        if (voiceStreaming) main.postDelayed(partialTick, PARTIAL_INTERVAL_MS)
    }

    /**
     * 話している途中の文字を出す。押している間の録音では未確定文字列（Termux などでは帯）に、
     * 聞き続けるモードではキーボードの入力とぶつからないよう帯に出す（入力中の文字があれば出さない）
     */
    private fun showPartial(text0: String) {
        if (engine.isComposing) return
        val text = spoken(text0)
        partialText = text
        if (continuous) {
            showVoiceDisplay()
        } else if (previewInBar) {
            showBar { it.showPreview("🎤 $text") }
        } else {
            currentInputConnection?.setComposingText(text, 1)
        }
    }

    /**
     * 聞き続けるモードの途中の文字（確定を待っている区切り＋話している区切り）を、入力欄の未確定文字列に出す
     * （Termux など帯に出すアプリでは帯に）。キーボードで打っている間は出さない
     */
    private fun showVoiceDisplay() {
        if (engine.isComposing) return
        val text = voiceSegments.joinToString("") + (if (continuous && endpointer.inSpeech) partialText.orEmpty() else "")
        if (previewInBar) {
            if (text.isEmpty()) hideBar() else showBar { it.showPreview("🎤 $text") }
            return
        }
        val ic = currentInputConnection ?: return
        if (text.isEmpty()) {
            clearVoiceInField()
        } else {
            ic.setComposingText(text, 1)
            voiceInField = true
        }
    }

    /** 入力欄に出している途中の文字を消す（キーを押したとき。キーがその文字に効かないように） */
    private fun clearVoiceInField() {
        if (!voiceInField) return
        voiceInField = false
        currentInputConnection?.let {
            it.setComposingText("", 1)
            it.finishComposingText()
        }
    }

    /** 話して入れる句読点（「くてん」→「。」など）。設定で切れる */
    private fun spoken(text: String) = if (settings.spokenPunctuation) SpokenPunctuation.apply(text) else text

    private fun stopVoice() {
        val pcm = VoiceInput.stopRecording() ?: return
        main.removeCallbacks(partialTick)
        showIndicatorText("…", 0)
        recognizeUtterance(pcm, continuousMode = false)
    }

    /** ひと区切りの音声を文字にして入れる */
    private fun recognizeUtterance(pcm: FloatArray, continuousMode: Boolean) {
        val started = System.nanoTime()
        val generation = inputGeneration
        val partialAtStart = partialText
        val finish = { text: String?, engineName: String ->
            main.post {
                if (!continuousMode) modeIndicator?.dismiss()
                if (generation != inputGeneration) {
                    // 認識しているあいだに入力欄が替わった。別の入力欄には入れない
                    log("voice: 入力欄が替わったので捨てた")
                    if (!continuousMode) {
                        partialText = null
                        voiceStreaming = false
                    }
                    return@post
                }
                val ms = (System.nanoTime() - started) / 1_000_000
                // 確定の文が作れなければ、話している途中に出していた文を使う
                val final = text?.let { spoken(it) } ?: partialAtStart
                log("voice: $engineName ${ms}ms ${final ?: "（認識できなかった）"}")
                if (continuousMode) {
                    voiceSegments.removeFirstOrNull()
                    if (final != null) insertVoiceText(final)
                    showVoiceDisplay()
                    return@post
                }
                val ic = currentInputConnection
                // 認識しているあいだに打ち始めていたら、その文字を先に確定する（未確定を上書きしないため）
                val typing = engine.isComposing
                if (typing && final != null) apply(engine.commitAll())
                if (final != null) {
                    ic?.commitText(final, 1)
                    lastCommitAt = android.os.SystemClock.uptimeMillis()
                    engine.leftContext = (engine.leftContext + final).takeLast(InputEngine.MAX_CONTEXT)
                } else if (!typing && (partialText != null || !previewInBar)) {
                    ic?.setComposingText("", 1)
                    ic?.finishComposingText()
                }
                if (previewInBar) hideBar()
                partialText = null
                voiceStreaming = false
            }
        }
        val model = VoiceCatalog.finalModel(this, settings)
        if (model == null) {
            finish(null, "none")
            return
        }
        model.recognize(this, pcm, settings.voiceThreads) { text -> finish(text, model.id) }
    }

    /** 聞き続けるモードで認識した文を入れる。入力中の文字があれば、それが確定するまで待つ */
    private fun insertVoiceText(text: String) {
        pendingVoice.append(text)
        flushPendingVoice()
    }

    private fun flushPendingVoice() {
        if (pendingVoice.isEmpty() || engine.isComposing) return
        val text = pendingVoice.toString()
        pendingVoice.clear()
        // 途中の文字を未確定文字列に出していれば、それを置き換えて確定する（残りは showVoiceDisplay で出し直す）
        voiceInField = false
        currentInputConnection?.commitText(text, 1)
        lastCommitAt = android.os.SystemClock.uptimeMillis()
        engine.leftContext = (engine.leftContext + text).takeLast(InputEngine.MAX_CONTEXT)
    }
    private fun showIndicatorText(text: String, durationMs: Long) {
        if (!OverlayBar.permitted(this)) return
        val m = modeIndicator ?: ModeIndicator(window?.window?.context ?: this, anchor).also { modeIndicator = it }
        runCatching { m.showText(text, durationMs) }.onFailure { log("indicator failed: $it") }
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
        keyboardView?.japanese = engine.japanese
        flickView?.syncJapanese(engine.japanese)
        if (engine.japanese) showStatusIcon(R.drawable.ic_mode_ja) else hideStatusIcon()
    }

    private val saveThread = Executors.newSingleThreadExecutor { Thread(it, "kanade-save") }

    /** パスワードの入力欄か */
    private fun isSecure(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        val cls = type and android.text.InputType.TYPE_MASK_CLASS
        val variation = type and android.text.InputType.TYPE_MASK_VARIATION
        return when (cls) {
            android.text.InputType.TYPE_CLASS_TEXT -> variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            android.text.InputType.TYPE_CLASS_NUMBER -> variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun log(event: KeyEvent, note: String) {
        if (settings.keyLogEnabled) keyLog.record(event, note)
    }

    private companion object {
        /** グライドの候補の数（候補の窓に出す） */
        const val GLIDE_CANDIDATES = 9
        /** グライドの語を並べ直すときに使う、入力中の読みの終わりの文字数 */
        const val GLIDE_CONTEXT = 10

        /** 画面キーボードの修飾キーと、その meta（左側のキーとして送る） */
        val SOFT_MODIFIERS = listOf(
            KeyEvent.KEYCODE_CTRL_LEFT to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
            KeyEvent.KEYCODE_ALT_LEFT to (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
            KeyEvent.KEYCODE_META_LEFT to (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON),
            KeyEvent.KEYCODE_SHIFT_LEFT to (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
        )

        /** 自分が確定してから、選択範囲の通知を古いものとして扱う時間 */
        const val STALE_SELECTION_MS = 300L

        /** 話しながら途中の文字を出し直す間隔 */
        const val PARTIAL_INTERVAL_MS = 600L
        /** 録音キーをこれより短く押したら、聞き続けるモード */
        const val TAP_MS = 300L
        const val VAD_INTERVAL_MS = 100L
        /** 聞き続けるモードで、これだけ話さなければ終える */
        const val CONTINUOUS_IDLE_MS = 60_000L

        /** 変換中に選んでいる文節の背景（薄い青。明るい画面でも暗い画面でも字が読める程度に） */
        val FOCUS_COLOR = Color.argb(0x60, 0x40, 0x80, 0xE0)
    }

    private fun log(note: String) {
        if (settings.keyLogEnabled) keyLog.note(note)
    }
}
