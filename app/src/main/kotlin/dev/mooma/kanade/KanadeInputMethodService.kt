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
import dev.mooma.kanade.core.voice.VoiceCommand
import dev.mooma.kanade.core.voice.PauseSplitter
import dev.mooma.kanade.core.voice.Fillers
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

    /** 未確定文字列をアプリに出さず、帯に出すアプリを使っている */
    private var previewInBar = false

    override fun onCreate() {
        super.onCreate()
        settings = KanadeSettings(this)
        keyLog = KeyLog(this)
        NavBarInsets.onChanged = ::log
        clipboardHistory = ClipboardHistory(this).apply { expiryMillis = { settings.clipboardExpiryHours * 3_600_000L } }
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
        NavBarInsets.onChanged = null
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        overlay?.hide()
        clipOverlay?.hide()
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
            this, ::softKey, ::nextSource, ::softVoice, ::showEmojiPanel,
            onClipboard = ::showClipboard, onSwitchLayout = ::switchLayout, onFloat = ::toggleFloat,
            onGlide = ::onGlide, onText = { flickActions.text(it) }, onVoiceCancel = ::cancelVoice,
        )
        keyboard.tapCorrector = ::correctLetterTap
        keyboard.onSymbolsPage = ::symbolsElsewhere
        keyboard.onSourceMenu = ::showSourceMenu
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
        flick.tapCorrector = ::correctKanaTap
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

        override fun key(keyCode: Int, meta: Int) = softKey(keyCode, meta)
        override fun setJapanese(on: Boolean) {
            handle(KeyInput.SetJapanese(on))
        }
        override fun nextSource() = this@KanadeInputMethodService.nextSource()
        override fun sourceMenu() = showSourceMenu()
        override fun emoji() = showEmojiPanel()
        override fun clipboard() = showClipboard()
        override fun voice(down: Boolean) = softVoice(down)
        override fun voiceCancel() = cancelVoice()
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
        // コピー元は、そのときキーボードがつながっているアプリとみなす（Android はコピー元を教えてくれないので推測）
        val source = currentInputEditorInfo?.packageName ?: currentInputBinding?.uid?.let { packageManager.getNameForUid(it) }
        // 画像を写すことがあるので、裏のスレッドで
        saveThread.execute { runCatching { clipboardHistory.capture(clipboardManager, source) } }
    }
    private var clipboardPanel: ClipboardPanelView? = null

    private fun createClipboardView(): View =
        ClipboardPanelView(
            this, clipboardHistory, onPaste = ::pasteClip, onClose = ::hideEmojiPanel, currentSensitive = ::currentSensitiveClip,
            onEdit = ::editClip,
        )
            .also { clipboardPanel = it }

    /** いまのコピーが機密の印つき（パスワード管理アプリなど）の文字なら、その文字（IME はいまの入力方法なので読める） */
    private fun currentSensitiveClip(): String? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return null
        val clip = runCatching { clipboardManager.primaryClip }.getOrNull() ?: return null
        if (clip.description.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) != true) return null
        return clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.takeIf { it.isNotEmpty() }
    }

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
            val message = getString(R.string.ime_clipboard_image_not_accepted)
            if (host?.mode == InputViewHost.Mode.CLIPBOARD && isInputViewShown) clipboardPanel?.showMessage(message)
            else showIndicatorText(message, 4000)
        }
    }

    /**
     * 物理キーボードから開くクリップボード（既定は Ctrl+Shift+V。設定で変えられる）。画面キーボードを出していなければ、
     * 画面キーボードと同じパネルを重ねる窓に出す（[ClipboardOverlay]）。開いている間は [clipOverlayKey] がキーを受ける
     */
    private var clipOverlay: ClipboardOverlay? = null

    private val clipOverlayShown: Boolean get() = clipOverlay?.isShowing == true

    private fun isClipboardShortcut(event: KeyEvent): Boolean = settings.clipboardShortcut?.matches(event) == true

    private fun openClipOverlay() {
        if (isInputViewShown && host != null) {
            showClipboard()
            return
        }
        apply(engine.commitAll())
        hideBar()
        if (!ClipboardOverlay.permitted(this)) {
            android.widget.Toast.makeText(this, R.string.ime_clipboard_overlay_permission, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val o = clipOverlay ?: run {
            val ctx = window?.window?.context ?: this
            val panel = ClipboardPanelView(
                ctx, clipboardHistory,
                onPaste = { item ->
                    closeClipOverlay()
                    pasteClip(item)
                },
                onClose = ::closeClipOverlay,
                currentSensitive = ::currentSensitiveClip,
                onEdit = { item ->
                    closeClipOverlay()
                    editClip(item)
                },
            )
            ClipboardOverlay(ctx, anchor, panel, settings).also { clipOverlay = it }
        }
        o.panel.showMessage(if (settings.clipboardHistory) null else getString(R.string.ime_clipboard_history_off))
        runCatching { o.show() }.onFailure { log("clipboard overlay failed: $it") }
    }

    private fun closeClipOverlay() {
        clipOverlay?.hide()
    }

    /** パネルを開いている間のキー。使ったら true。使わないキー（文字など）はパネルを閉じて、ふだんどおりに処理させる */
    private fun clipOverlayKey(event: KeyEvent): Boolean {
        val panel = clipOverlay?.panel ?: return false
        fun paste(item: ClipboardHistory.Item?) {
            if (item == null) return
            closeClipOverlay()
            pasteClip(item)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> panel.moveSelection(-1, 0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> panel.moveSelection(1, 0)
            KeyEvent.KEYCODE_DPAD_UP -> panel.moveSelection(0, -1)
            KeyEvent.KEYCODE_DPAD_DOWN -> panel.moveSelection(0, 1)
            KeyEvent.KEYCODE_TAB -> panel.moveSelection(if (event.isShiftPressed) -1 else 1, 0)
            KeyEvent.KEYCODE_MOVE_HOME -> panel.selectEdge(last = false)
            KeyEvent.KEYCODE_MOVE_END -> panel.selectEdge(last = true)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> paste(panel.selectedItem())
            KeyEvent.KEYCODE_ESCAPE -> closeClipOverlay()
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> paste(panel.numberedItem(event.keyCode - KeyEvent.KEYCODE_0))
            in KeyEvent.KEYCODE_NUMPAD_1..KeyEvent.KEYCODE_NUMPAD_9 -> paste(panel.numberedItem(event.keyCode - KeyEvent.KEYCODE_NUMPAD_0))
            else -> {
                // 修飾キーだけ（開いたキーの Ctrl などを離す前）は閉じない
                if (KeyEvent.isModifierKey(event.keyCode)) return false
                closeClipOverlay()
                // もう一度開くキーを押したら閉じるだけ
                return isClipboardShortcut(event)
            }
        }
        return true
    }

    /** 文字の項目を編集の画面で開く（キーボードはその画面の入力欄で使う） */
    private fun editClip(item: ClipboardHistory.Item) {
        hideEmojiPanel()
        startActivity(ClipboardEditActivity.intent(this, item.id))
    }

    private fun showClipboard() {
        apply(engine.commitAll())
        clipboardPanel?.showMessage(if (settings.clipboardHistory) null else getString(R.string.ime_clipboard_history_off))
        clipboardPanel?.refresh()
        host?.show(InputViewHost.Mode.CLIPBOARD)
    }

    /** ツールバーの配列の切り替え（QWERTY ⇄ フリック）。次から使う配列として覚える */
    /** いまの「配列×言語」 */
    private fun currentSource(): KeyboardSource {
        val layout = settings.softKeyboardLayout
        val mode = if (layout == "FLICK") flickView?.mode ?: "JA" else if (engine.japanese) "JA" else "EN"
        return KeyboardSource(layout, mode)
    }

    /** 「配列×言語」にする（配列を替え、日本語のオン/オフとテンキーのかな・英字・数字を合わせる） */
    private fun applySource(source: KeyboardSource) {
        if (settings.softKeyboardLayout != source.layout) {
            settings.softKeyboardLayout = source.layout
            applyKeyboardSettings()
        }
        val japanese = source.mode == "JA"
        if (engine.japanese != japanese) handle(KeyInput.SetJapanese(japanese))
        if (source.layout == "FLICK") flickView?.showMode(source.mode)
    }

    /** あ/A（テンキーは「あa1」）: 次の「配列×言語」へ */
    private fun nextSource() {
        applySource(KeyboardSources.next(settings, currentSource()))
    }

    /**
     * 日本語のオン/オフが（物理キーボードなどで）替わったとき、いまの配列でその言語を使わない設定なら、使える配列へ移す
     */
    private fun ensureSourceFor(japanese: Boolean) {
        if (!isInputViewShown) return
        val mode = if (japanese) "JA" else "EN"
        val current = settings.softKeyboardLayout
        if (mode in KeyboardSources.cycleModes(settings, current)) return
        KeyboardSources.forMode(settings, current, mode)?.let { applySource(it) }
    }

    private var sourceMenu: android.widget.PopupWindow? = null

    /** 「配列×言語」の一覧（iOS の地球儀のキーの長押しと同じ）。キーボードの左下の上に出し、押したものにする */
    private fun showSourceMenu() {
        val anchor = host ?: return
        sourceMenu?.dismiss()
        val palette = KeyboardPalette.of(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val current = currentSource()
        val layoutName = mapOf(
            "GLIDE" to R.string.input_layout_glide, "QWERTY" to R.string.input_layout_qwerty, "FLICK" to R.string.input_layout_flick,
        )
        val modeName = mapOf("JA" to R.string.ime_mode_ja, "EN" to R.string.ime_mode_en, "NUM" to R.string.ime_mode_num)
        val list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(palette.functionKey)
            }
            elevation = dp(6).toFloat()
        }
        // フォーカスを取らない（取ると Android 12 などでは入力欄の側がフォーカスを失い、キーボードごと閉じる）。外を触ったら閉じる
        val popup = android.widget.PopupWindow(list, dp(240), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
        }
        // 配列ごとにまとめる（見出しに配列の名前、その下に言語）
        for ((layout, sources) in KeyboardSources.all(settings).groupBy { it.layout }) {
            list.addView(android.widget.TextView(this).apply {
                text = getString(layoutName.getValue(layout))
                setTextColor(palette.dim)
                textSize = 13f
                setPadding(dp(14), dp(if (list.childCount == 0) 4 else 10), dp(14), dp(2))
            })
            for (source in sources) {
                val selected = source == current
                list.addView(android.widget.TextView(this).apply {
                    text = getString(modeName.getValue(source.mode))
                    setTextColor(if (selected) palette.onAccent else palette.text)
                    textSize = 16f
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(24), 0, dp(16), 0)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(if (selected) palette.accent else android.graphics.Color.TRANSPARENT)
                    }
                    setOnClickListener {
                        popup.dismiss()
                        applySource(source)
                    }
                }, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))
            }
        }
        popup.setOnDismissListener { if (sourceMenu === popup) sourceMenu = null }
        sourceMenu = popup
        // キーボードの左下（切り替えのキー）の上あたり
        runCatching { popup.showAtLocation(anchor, android.view.Gravity.BOTTOM or android.view.Gravity.START, dp(8), dp(72)) }
    }

    /** グライドで数字/記号を使わない設定なら、数字/記号を使えるテンキーへ移す（移したら true） */
    private fun symbolsElsewhere(): Boolean {
        if ("NUM" in settings.layoutModes("GLIDE")) return false
        val target = KeyboardSources.all(settings).firstOrNull { it.mode == "NUM" } ?: return false
        applySource(target)
        return true
    }

    /** 配列を順に替える（使う配列の中で、グライド → フルキー → テンキー → グライド） */
    private fun switchLayout() {
        // 使う配列の中だけを、グライド → フルキー → テンキーの順に回る
        val enabled = LAYOUTS.filter { it in settings.enabledLayouts }
        val now = settings.softKeyboardLayout
        val next = enabled[(enabled.indexOf(now) + 1) % enabled.size]
        // 言語は、移った先の配列で使うものに合わせる（いまの言語を使うならそのまま、使わないならその配列の最初の言語）
        val modes = KeyboardSources.cycleModes(settings, next)
        val mode = currentSource().mode.takeIf { it in modes } ?: modes.firstOrNull() ?: "JA"
        applySource(KeyboardSource(next, mode))
    }

    /** いまの配列でいまの言語を使わない設定なら、その配列の最初の言語にする（キーボードを出したとき・設定を変えたあと） */
    private fun ensureModeAllowed() {
        val current = currentSource()
        val modes = KeyboardSources.cycleModes(settings, current.layout)
        if (modes.isNotEmpty() && current.mode !in modes) applySource(KeyboardSource(current.layout, modes.first()))
    }

    /**
     * タップの位置の補いに使う「次に来やすい文字」（グライドの語の一覧から作る。画面キーボードを初めて出したときに裏で読む）。
     * 日本語はローマ字・テンキーのかな、英語は英単語
     */
    @Volatile private var tapJapaneseRomaji: dev.mooma.kanade.core.touch.NextCharModel? = null
    @Volatile private var tapJapaneseKana: dev.mooma.kanade.core.touch.NextCharModel? = null
    @Volatile private var tapEnglish: dev.mooma.kanade.core.touch.NextCharModel? = null
    private var tapModelsLoading = false

    private fun loadTapModels() {
        if (tapModelsLoading) return
        tapModelsLoading = true
        glideThread.execute {
            runCatching {
                val ja = assets.open("glide-ja.tsv").bufferedReader().readLines()
                tapJapaneseRomaji = dev.mooma.kanade.core.touch.TapModels.japaneseRomaji(ja.asSequence())
                tapJapaneseKana = dev.mooma.kanade.core.touch.TapModels.japaneseKana(ja.asSequence())
                tapEnglish = assets.open("glide-en.txt").bufferedReader().useLines { dev.mooma.kanade.core.touch.TapModels.english(it) }
            }.onFailure { log("tap models failed: $it") }
        }
    }

    /** グライドの配列の文字のタップ: 入力中の語から、境目を触ったときにどちらのキーかを選ぶ */
    private fun correctLetterTap(options: List<dev.mooma.kanade.core.touch.TapCorrection.Option>): Int {
        if (!settings.tapCorrection) return 0
        val model = (if (engine.japanese) tapJapaneseRomaji else tapEnglish) ?: return 0
        val context = if (engine.japanese) {
            if (engine.isComposing && !engine.isConverting) engine.composingRomaji.lowercase().takeLast(6) else ""
        } else {
            // 英語: カーソルの前の語（英字が続くところ）
            currentInputConnection?.getTextBeforeCursor(16, 0)?.toString().orEmpty().takeLastWhile { it.isLetter() }.lowercase()
        }
        return dev.mooma.kanade.core.touch.TapCorrection.choose(options, prefixed(context), model)
    }

    /** テンキーのかなのタップ・払い: 入力中の読みのあとに来やすいかなを選ぶ */
    private fun correctKanaTap(options: List<dev.mooma.kanade.core.touch.TapCorrection.Option>): Int {
        if (!settings.tapCorrection || !engine.japanese) return 0
        val model = tapJapaneseKana ?: return 0
        val context = if (engine.isComposing && !engine.isConverting) engine.composingReading.takeLast(2) else ""
        return dev.mooma.kanade.core.touch.TapCorrection.choose(options, prefixed(context, 2), model)
    }

    /** 語の始まりの印を付ける（前の文字が少ないときは、語の頭であることも手がかりにする） */
    private fun prefixed(context: String, order: Int = 3) =
        if (context.length < order) dev.mooma.kanade.core.touch.NextCharModel.START + context else context

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
    private class GlideResult(var candidates: List<String>, var text: String, val japanese: Boolean, val prefix: String)
    private var lastGlide: GlideResult? = null
    /** グライドの語を置き換えている途中（そのあいだの入力では、グライドの候補を消さない） */
    private var replacingGlide = false

    /** グライドの配列で文字のキーの上をなぞった。裏で読み取って、いちばん合う語を入れる */
    private fun onGlide(input: SoftKeyboardView.GlideInput) {
        val japanese = engine.japanese
        val decoder = (if (japanese) glideJapanese else glideEnglish) ?: run {
            loadGlide()
            showIndicatorText(getString(R.string.ime_glide_preparing), 2000)
            return
        }
        // 日本語は、入力中の読みのあとにつなげたときの自然さで並べ直す（読みの終わりの方だけを使う）
        val context = if (japanese && engine.isComposing && !engine.isConverting) engine.composingReading.takeLast(GLIDE_CONTEXT) else ""
        // zenz に渡す左の文脈（確定済みの文）
        val committed = if (!japanese) "" else if (engine.isComposing) engine.leftContext
        else currentInputConnection?.getTextBeforeCursor(ZenzModel.MAX_LEFT_CONTEXT, 0)?.toString().orEmpty()
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
                if (words.isEmpty()) return@post
                insertGlide(words, japanese)
                if (japanese && settings.glideZenz && result.size > 1) lastGlide?.let { zenzGlide(it, result, decoder, context, committed) }
            }
        }
    }

    /**
     * 日本語のグライドの候補を zenz で並べ直す（読みごとに Mozc のいちばん良い変換を付け、文脈に続く「読み＋変換」の自然さを聞く）。
     * 先に辞書の1位を入れておき、zenz が終わったときにまだ次の入力がなく、1位が変わっていれば入れ替える
     */
    private fun zenzGlide(
        g: GlideResult,
        candidates: List<dev.mooma.kanade.core.glide.GlideCandidate>,
        decoder: dev.mooma.kanade.core.glide.GlideDecoder,
        context: String,
        committed: String,
    ) {
        zenzThread.execute {
            val model = zenz ?: return@execute
            if (lastGlide !== g) return@execute
            val ranked = runCatching {
                val converter = ConversionResources.converter(this)
                val items = candidates.map { c ->
                    val reading = context + c.output
                    reading to (converter.convert(reading, n = 1).firstOrNull()?.words?.joinToString("") { it.surface } ?: reading)
                }
                val lp = model.scoreReadings(committed, items) ?: return@execute
                decoder.rerankWithModel(candidates, lp)
            }.getOrNull() ?: return@execute
            main.post {
                if (lastGlide !== g) return@post
                val words = ranked.map { it.output }.distinct()
                if (words == g.candidates) return@post
                val top = words.first()
                if (top != g.text) {
                    if (!engine.isComposing || !engine.composingReading.endsWith(g.text)) return@post
                    replacingGlide = true
                    try {
                        handle(KeyInput.ReplaceLast(top, g.text.length))
                    } finally {
                        replacingGlide = false
                    }
                    glideLearning.forget(g.text)
                    glideLearning.record(top)
                    saveGlideLearning()
                    g.text = top
                }
                g.candidates = words
                showBar { it.showCandidates(words, 0) }
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
                // 絵文字の検索の欄では、予測の候補は出さない（検索の候補の行は変換の候補だけ）
                is ImeCommand.ShowCandidates -> if (!command.prediction) panel.showSearchCandidates(command.candidates, command.selected)
                ImeCommand.HideCandidates -> panel.hideSearchCandidates()
                is ImeCommand.RequestRerank -> requestRerank(command)
                is ImeCommand.RequestPredictionRank -> requestPredictionRank(command)
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
            if (settings.tapCorrection && settings.softKeyboardLayout != "QWERTY") loadTapModels()
            k.japanese = engine.japanese
            k.heightPercent = settings.softKeyboardHeight
            k.swipePercent = settings.softKeyboardSwipe
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

    override fun onWindowShown() {
        super.onWindowShown()
        // 隠している間に届いた余白（ナビゲーションバーが見えていない扱いなど）が残らないよう、出すたびに読み直す
        keyboardView?.refreshInsets()
        flickView?.refreshInsets()
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
        if (lastVoice != null) {
            main.post { if (!replaceVoice(index)) handle(KeyInput.SelectCandidate(index)) }
            return
        }
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
        applyZenzSettings()
        refreshSpokenTable()
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
        // 再変換のための読みも、学習しない入力欄では覚えない
        engine.rememberReadings = candidateSource?.learningPaused != true
        hasSelection = attribute != null && attribute.initialSelStart != attribute.initialSelEnd
        // アプリごとの挙動（カーソルが出ないなど）を調べるため、入力欄の情報を記録する
        log(
            "start-input pkg=${attribute?.packageName} restarting=$restarting type=0x${Integer.toHexString(attribute?.inputType ?: 0)} " +
                "ime=0x${Integer.toHexString(attribute?.imeOptions ?: 0)} sel=${attribute?.initialSelStart},${attribute?.initialSelEnd}",
        )
        // 入力欄が替わったら、入力中の状態を必ず捨てる。ブラウザ（WebView）などは欄を移っても onFinishInput を呼ばず
        // restarting=true で始め直すことがあり、前の欄の入力中の文字が残って新しい欄に入り、括弧の中のカーソルの位置もずれていた
        engine.reset()
        lastGlide = null
        hideBar()
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
        ensureModeAllowed()
        hideEmojiPanel()
        log("start-input-view pkg=${info?.packageName} restarting=$restarting")
    }

    override fun onFinishInput() {
        log("finish-input")
        closeClipOverlay()
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
        hasSelection = newSelStart != newSelEnd && candidatesStart == -1
        // クリックなどで未確定文字列が外から確定・消去されたら、こちらの状態も捨てる
        // （帯に出しているときはアプリに未確定文字列がないので、この判定はしない）
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
        if (clipOverlayShown && clipOverlayKey(event)) {
            consumedDown += keyCode
            log(event, "clipboard")
            return true
        }
        if (isClipboardShortcut(event)) {
            if (event.repeatCount == 0) openClipOverlay()
            consumedDown += keyCode
            log(event, "clipboard")
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && (VoiceInput.isRecording || continuous)) {
            // 録音中の Esc は、録った音声を入れずに捨てる
            cancelVoice()
            consumedDown += keyCode
            log(event, "voice cancel")
            return true
        }
        if (keyCode == settings.voiceKey) {
            // 押している間だけ録音する（押しっぱなしの繰り返しは無視）。短く押すと聞き続ける
            if (event.repeatCount == 0) voiceKey(true)
            log(event, "voice")
            return true
        }
        // 聞き続けるモードの途中の文字を入力欄に出していたら、キーがそれに効かないよう先に消す（話している間は出し直す）
        clearVoiceInField()
        if (reconvertKey(event)) {
            consumedDown += keyCode
            log(event, "reconvert")
            return true
        }
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

    /** アプリで文字を選んでいる（入力中の文字ではない）。再変換に使う */
    private var hasSelection = false

    /**
     * 再変換: 日本語入力中に、アプリで確定済みの文字を選んで Space（物理キーボードは変換キーも）を押すと、選んだ文字を変換し直す。
     * 読みは Kanade が確定したときに覚えたもの（とかな）から引く。読めなければ何もせず、キーはふだんどおり
     */
    private fun reconvertKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        val space = code == KeyEvent.KEYCODE_SPACE && !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
        if (!space && code != KeyEvent.KEYCODE_HENKAN) return false
        if (!hasSelection || engine.isComposing || previewInBar || emojiSearching || event.repeatCount > 0) return false
        if (!engine.japanese && code != KeyEvent.KEYCODE_HENKAN) return false
        val ic = currentInputConnection ?: return false
        val selected = ic.getSelectedText(0)?.toString() ?: return false
        if (engine.readingOf(selected) == null) return false
        if (!engine.japanese) handle(KeyInput.SetJapanese(true))
        engine.leftContext = ic.getTextBeforeCursor(InputEngine.MAX_CONTEXT, 0)?.toString().orEmpty()
        engine.rightContext = ic.getTextAfterCursor(InputEngine.MAX_CONTEXT, 0)?.toString().orEmpty()
        val commands = engine.reconvert(selected) ?: return false
        // 未確定文字列は、選んでいる範囲を置き換えて入る
        apply(commands)
        hasSelection = false
        return true
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
        // 音声で入れた直後の Backspace は、入れた文を丸ごと消す（グライドの語と同じ）
        if (input == KeyInput.Backspace && lastVoice != null && deleteLastVoice()) return true
        // 音声の文の候補（整える前の文字）を出しているあいだにほかのキーを打ったら、候補はおしまい
        lastVoice?.let { v ->
            if (input !is KeyInput.SelectCandidate) {
                lastVoice = null
                if (v.alternatives.size > 1) hideBar()
            }
        }
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
        // フリックの打ち間違いを直す候補は、画面のテンキーで打っているときだけ（物理キーボードやグライドでは起きない間違いのため）
        candidateSource?.typoCorrection = isInputViewShown && host?.flickLayout == true
        val result = engine.handle(input)
        apply(result.commands)
        return result.consumed
    }

    private fun apply(commands: List<ImeCommand>) {
        if (commands.isEmpty()) return
        // テンキーの Tab のキーを、変換中は文節を直すキーにする
        flickView?.converting = engine.isConverting
        if (emojiSearching) {
            applyToSearch(commands)
            return
        }
        val ic = currentInputConnection
        // 未確定文字列の途中に置くカーソル。まとめて送る区切りを閉じてから置く（下の placeCursorInComposing）
        var placeAfter: ImeCommand.SetComposing? = null
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
                        placeAfter = null
                    } else {
                        ic?.setComposingText(styled(command), 1)
                        placeAfter = if (command.cursor >= 0) command else null
                    }
                is ImeCommand.Commit -> {
                    ic?.commitText(command.text, 1)
                    placeAfter = null
                    lastCommitAt = android.os.SystemClock.uptimeMillis()
                    if (previewInBar) hideBar()
                }
                is ImeCommand.ModeChanged -> {
                    settings.lastJapanese = command.japanese
                    updateStatusIcon()
                    showModeIndicator(command.japanese)
                    ensureSourceFor(command.japanese)
                }
                is ImeCommand.ShowCandidates -> showBar(command.anchorOffset) {
                    it.showCandidates(
                        command.candidates, command.selected, if (previewInBar) engine.composingText else null, command.labels,
                        numbered = !command.prediction,
                    )
                }
                ImeCommand.HideCandidates -> if (previewInBar && engine.isComposing) {
                    showBar { it.showPreview(engine.composingText) }
                } else {
                    hideBar()
                }
                is ImeCommand.RequestRerank -> requestRerank(command)
                is ImeCommand.RequestPredictionRank -> requestPredictionRank(command)
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
        // Compose で作られた欄（連絡先など）は、区切りの中で送った文字を区切りを閉じるまで反映せず、
        // 区切りの中で問い合わせると前の中身が返る。そのため区切りの外で、反映された中身から位置を求める
        placeAfter?.let { if (ic != null) placeCursorInComposing(ic, it.text, it.cursor) }
        // 聞き続けるモードで、入力中の文字の確定を待っていた音声の文を入れ、途中の文字を出し直す
        if (continuous || voiceSegments.isNotEmpty() || pendingVoice.isNotEmpty()) {
            if (!engine.isComposing) {
                flushPendingVoice()
                showVoiceDisplay()
            }
        }
    }

    /** zenz の設定（プロフィール・スレッド数・使い方）を、読み込んだモデルと並べ替えに入れる */
    private fun applyZenzSettings() {
        val profile = settings.zenzProfile
        val threads = settings.zenzThreads.coerceIn(1, Runtime.getRuntime().availableProcessors())
        zenzCorrect = settings.zenzMode == "CORRECT"
        zenzThread.execute {
            val model = zenz ?: return@execute
            model.profile = profile
            if (threads != zenzThreadsApplied) {
                model.setThreads(threads)
                zenzThreadsApplied = threads
            }
        }
    }

    /** Zenzai の方式で1位を直す（設定の「確かめて直す」） */
    @Volatile private var zenzCorrect = false
    @Volatile private var latestPrediction = 0L
    private var zenzThreadsApplied = 0

    /** 並べ替えを裏で回す。新しい要求が来たら、走っている古い推論は止める */
    private fun requestRerank(request: ImeCommand.RequestRerank) {
        latestRerank = request.id
        zenz?.cancel()
        val source = candidateSource
        val correct = zenzCorrect
        zenzThread.execute {
            if (request.id != latestRerank) return@execute
            val model = zenz ?: return@execute
            val ranked = runCatching {
                val constrained = source?.let { s -> { prefix: String -> s.convertWithPrefix(request.reading, prefix)?.text } }
                Reranker(model).rerank(request, if (correct) Reranker.CORRECT_LIMIT else 0, constrained)
            }.getOrNull() ?: return@execute
            main.post { apply(engine.onReranked(request.id, ranked)) }
        }
    }

    /** 予測の候補を zenz で並べ替える。新しい予測が出たら、エンジンが古い結果を捨てる */
    private fun requestPredictionRank(request: ImeCommand.RequestPredictionRank) {
        latestPrediction = request.id
        zenzThread.execute {
            if (request.id != latestPrediction) return@execute
            val model = zenz ?: return@execute
            val ranked = runCatching { Reranker(model).rankPredictions(request.leftContext, request.items) }.getOrNull() ?: return@execute
            main.post { apply(engine.onPredictionsRanked(request.id, ranked)) }
        }
    }

    /**
     * 未確定文字列の途中にカーソルを置く。setComposingText はカーソルを未確定文字列の外にしか置けないので、
     * 未確定文字列の先頭の位置を知って setSelection する（先頭が分からなければ、アプリの文字列から求める）
     */
    private fun placeCursorInComposing(ic: android.view.inputmethod.InputConnection, text: String, cursor: Int) {
        val length = text.length
        // アプリの欄でカーソルの前が、いま入れた未確定文字列でなければ（アプリの処理が遅れている・状態がずれている）、動かさない
        if (ic.getTextBeforeCursor(length, 0)?.toString() != text) return
        // 未確定文字列の先頭は、いま入れた直後のカーソル（未確定文字列の末尾）から求める。
        // アプリから届く位置（onUpdateSelection）は遅れて届くので、確定してすぐ次を打つと前の未確定文字列の位置のままで、
        // カーソルが前に打った文字の中へ飛び、前の文字が未確定文字列に混ざっていた（括弧を打ったときに起きていた）
        val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        val start = if (extracted != null) extracted.startOffset + extracted.selectionEnd - length else return
        if (start < 0) return
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

    /** 話しながら途中の文字を出しているか（Nemotron か ReazonSpeech が使えるとき） */
    private var voiceStreaming = false
    /** 途中の文字を Nemotron の逐次認識で出すか（落としてあるとき）。でなければ ReazonSpeech に毎回全部をかけ直す */
    private var partialStreaming = false
    /** 逐次認識に流した音声の位置（VoiceInput.since に渡す） */
    private var streamEpoch = -1
    private var streamFed = 0
    private var partialBusy = false
    private var partialText: String? = null
    private val partialTick = object : Runnable {
        override fun run() {
            if (!VoiceInput.isRecording || !voiceStreaming) return
            // 聞き続けるモードでは、話している間だけ途中の文字を出す
            if (!partialBusy && (!continuous || endpointer.inSpeech)) {
                partialBusy = true
                val done = { text: String? ->
                    main.post {
                        partialBusy = false
                        if (VoiceInput.isRecording && !text.isNullOrEmpty() && (!continuous || endpointer.inSpeech)) showPartial(text)
                    }
                }
                if (partialStreaming) {
                    // 前回の続きの音声だけを流す（聞き続けるモードで区切って音声を取り出したら、流れを始め直す）
                    val (epoch, pcm, next) = VoiceInput.since(streamEpoch, streamFed)
                    val reset = epoch != streamEpoch
                    streamEpoch = epoch
                    streamFed = next
                    StreamingRecognizer.feed(this@KanadeInputMethodService, pcm, reset, settings.voiceThreads) { done(it) }
                } else {
                    SherpaRecognizer.reazon.recognize(this@KanadeInputMethodService, VoiceInput.snapshot(), settings.voiceThreads) { done(it) }
                }
            }
            main.postDelayed(this, if (partialStreaming) STREAM_INTERVAL_MS else PARTIAL_INTERVAL_MS)
        }
    }

    /**
     * 聞き続けるモード（録音キー・🎤 を短く押したとき。iPhone や Pixel の音声入力と同じ）。
     * 録音を続け、話し終わり（無音）ごとに区切って文字にし、カーソルの位置に入れる。キーボードでの入力と並べて使える
     * （入力中の文字があれば、それが確定してから入れる）。もう一度押すか、1分話さなければ終わる
     */
    private var continuous = false
    /** 聞き続けるモードで、これだけ話さなければ終える（ms。0 なら終えない） */
    private var continuousIdleMs = 60_000L
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
                if (!endpointer.inSpeech && continuousIdleMs > 0 && now - lastSpeechAt > continuousIdleMs) {
                    stopContinuous(getString(R.string.ime_voice_stopped_idle))
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
                stopContinuous(getString(R.string.ime_voice_stopped))
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
        continuousIdleMs = settings.continuousIdleSeconds * 1000L
        endpointer.reset()
        VoiceInput.trimTo(0)
        vadIndex = 0
        lastSpeechAt = android.os.SystemClock.uptimeMillis()
        partialText = null
        voiceSegments.clear()
        keyboardView?.voiceActive = true
        flickView?.voiceActive = true
        showIndicatorText(getString(R.string.ime_voice_listening), 1500)
        main.postDelayed(continuousTick, VAD_INTERVAL_MS)
    }

    /** 聞き続けるモードを終える。話している途中の音声があれば文字にして入れる */
    private fun stopContinuous(message: String, keepSpeech: Boolean = true) {
        continuous = false
        keyboardView?.voiceActive = false
        flickView?.voiceActive = false
        main.removeCallbacks(continuousTick)
        main.removeCallbacks(partialTick)
        if (partialStreaming) StreamingRecognizer.finishStream()
        val speaking = endpointer.inSpeech
        endpointer.reset()
        val pcm = VoiceInput.stopRecording()
        if (speaking && pcm != null && keepSpeech) {
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
        val nemotronReady = settings.voiceStreaming && StreamingRecognizer.isReady(this)
        val streamingReady = nemotronReady || (settings.voiceStreaming && SherpaRecognizer.reazon.isReady(this))
        val message = when {
            !VoiceInput.hasPermission(this) -> getString(R.string.ime_voice_no_permission)
            VoiceCatalog.finalModel(this, settings) == null -> getString(R.string.ime_voice_no_model)
            !VoiceInput.start(this) -> getString(R.string.ime_voice_record_failed)
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
        partialStreaming = nemotronReady
        streamEpoch = -1
        streamFed = 0
        // 逐次認識のモデルは大きく、初めの読み込みに数秒かかるので、録音を始めたらすぐ読み始める
        if (partialStreaming) StreamingRecognizer.preload(this, settings.voiceThreads)
        partialText = null
        showIndicatorText("🎤", 0)
        if (voiceStreaming) main.postDelayed(partialTick, if (partialStreaming) STREAM_INTERVAL_MS else PARTIAL_INTERVAL_MS)
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

    /**
     * 認識した文の後処理: 言いよどみを消す（設定）・話して入れる記号（「くてん」→「。」。表は設定で編集できる）・
     * 認識が付けた句読点を残すか（設定）・ユーザー辞書の読みに当たる所をその語に（設定）
     */
    private fun spoken(text: String): String {
        var t = text
        if (settings.voiceFillers) t = Fillers.remove(t)
        val auto = settings.voiceAutoPunctuation
        t = when {
            settings.spokenPunctuation -> spokenTable.apply(t, auto)
            !auto -> NO_SPOKEN_SYMBOLS.apply(t, autoPunctuation = false)
            else -> t
        }
        if (settings.voiceUserDictionary) t = candidateSource?.applyUserWords(t) ?: t
        return t
    }

    /** 話して入れる記号の表。設定画面で編集されていたら読み直す */
    private var spokenTable = SpokenPunctuation.DEFAULT
    private var spokenTableVersion = -1L

    private fun refreshSpokenTable() {
        val store = TextStore.spokenSymbols(this)
        val v = store.version()
        if (v == spokenTableVersion) return
        spokenTableVersion = v
        spokenTable = SpokenPunctuation(SpokenPunctuation.parse(store.read()))
    }

    /** 直前に音声で入れた文（直後の Backspace・「取り消して」で丸ごと消す）と、候補の窓に出す選び直しの候補 */
    private class VoiceResult(var text: String, val alternatives: List<String>)
    private var lastVoice: VoiceResult? = null

    /** 音声の文を確定して入れる。[alternatives]（確定の文と整える前の文）が2つ以上なら候補の窓に出して選び直せるようにする */
    private fun commitVoice(text: String, alternatives: List<String> = emptyList()) {
        currentInputConnection?.commitText(text, 1)
        lastCommitAt = android.os.SystemClock.uptimeMillis()
        engine.leftContext = (engine.leftContext + text).takeLast(InputEngine.MAX_CONTEXT)
        val alts = alternatives.filter { it.isNotEmpty() }.distinct()
        lastVoice = VoiceResult(text, alts)
        if (alts.size > 1) showBar { it.showCandidates(alts, 0, numbered = false) }
    }

    /** 直前に音声で入れた文を消す。カーソルの前がその文でなければ（動かした・打った）何もしない */
    private fun deleteLastVoice(): Boolean {
        val v = lastVoice ?: return false
        lastVoice = null
        if (v.alternatives.size > 1) hideBar()
        val ic = currentInputConnection ?: return false
        if (ic.getTextBeforeCursor(v.text.length, 0)?.toString() != v.text) return false
        ic.deleteSurroundingText(v.text.length, 0)
        engine.leftContext = engine.leftContext.removeSuffix(v.text)
        return true
    }

    /** 音声の文の候補の窓で、別の文（整える前の文字）を選んだ */
    private fun replaceVoice(index: Int): Boolean {
        val v = lastVoice ?: return false
        val next = v.alternatives.getOrNull(index) ?: return false
        val ic = currentInputConnection ?: return false
        if (ic.getTextBeforeCursor(v.text.length, 0)?.toString() != v.text) return false.also { lastVoice = null }
        ic.deleteSurroundingText(v.text.length, 0)
        ic.commitText(next, 1)
        engine.leftContext = (engine.leftContext.removeSuffix(v.text) + next).takeLast(InputEngine.MAX_CONTEXT)
        v.text = next
        showBar { it.showCandidates(v.alternatives, index, numbered = false) }
        return true
    }

    /** 録音を捨てる（Esc・🎤 を押したまま払って離す）。聞き続けるモードなら、話している途中の音声を捨てて終える */
    private fun cancelVoice() {
        if (continuous) {
            stopContinuous(getString(R.string.ime_voice_cancelled), keepSpeech = false)
            return
        }
        if (!VoiceInput.isRecording) return
        VoiceInput.cancel()
        main.removeCallbacks(partialTick)
        if (partialStreaming) StreamingRecognizer.finishStream()
        if (partialText != null || !previewInBar) {
            currentInputConnection?.let {
                it.setComposingText("", 1)
                it.finishComposingText()
            }
        }
        if (previewInBar) hideBar()
        partialText = null
        voiceStreaming = false
        showIndicatorText(getString(R.string.ime_voice_cancelled), 1200)
    }

    /** 声のコマンドを実行する。何として受け取ったかを少し出す */
    private fun runVoiceCommand(c: VoiceCommand) {
        showIndicatorText(getString(R.string.ime_voice_command, getString(voiceCommandLabel(c))), 1500)
        log("voice: command $c")
        val ic = currentInputConnection
        when (c) {
            VoiceCommand.SEND -> {
                val info = currentInputEditorInfo
                val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                val noEnterAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
                val sendLike = action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_GO ||
                    action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE
                if (sendLike && !noEnterAction) ic?.performEditorAction(action) else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            }
            VoiceCommand.NEXT -> {
                val action = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                if (action == EditorInfo.IME_ACTION_NEXT) ic?.performEditorAction(action) else sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
            }
            VoiceCommand.STOP -> if (continuous) stopContinuous(getString(R.string.ime_voice_stopped))
            VoiceCommand.UNDO -> deleteLastVoice()
            VoiceCommand.CLEAR -> {
                lastVoice = null
                ic?.performContextMenuAction(android.R.id.selectAll)
                ic?.commitText("", 1)
                engine.leftContext = ""
            }
        }
    }

    private fun voiceCommandLabel(c: VoiceCommand) = when (c) {
        VoiceCommand.SEND -> R.string.ime_voice_command_send
        VoiceCommand.NEXT -> R.string.ime_voice_command_next
        VoiceCommand.STOP -> R.string.ime_voice_command_stop
        VoiceCommand.UNDO -> R.string.ime_voice_command_undo
        VoiceCommand.CLEAR -> R.string.ime_voice_command_clear
    }

    /** 音声を順に文字にする（区切りごと）。[onDone] には同じ順の文字（認識できなければ null）を渡す */
    private fun recognizeEach(model: VoiceModelSpec, pcms: List<FloatArray>, onDone: (List<String?>) -> Unit) {
        val out = ArrayList<String?>()
        fun next() {
            if (out.size == pcms.size) {
                onDone(out)
                return
            }
            model.recognize(this, pcms[out.size], settings.voiceThreads) { text ->
                out += text
                next()
            }
        }
        next()
    }

    /**
     * 押している間の録音で、声のコマンドを見分けて文字にする。録音を間（無音）で区切り、前後に間がある短い区切りだけを
     * 別に文字にして、まるごとコマンドの言葉ならコマンドにする（「送信して」などは録音の最後の区切りだけ）。
     * コマンドがなければ、録音の全体をふつうに文字にする（[normal]）
     */
    private fun recognizeWithCommands(model: VoiceModelSpec, pcm: FloatArray, normal: () -> Unit, apply: (List<Any>) -> Unit) {
        val rate = WhisperModel.SAMPLE_RATE
        val spans = PauseSplitter.split(pcm, rate, VoiceCommand.PAUSE_MS, VoiceCommand.MIN_SPEECH_MS)
        val short = spans.indices.filter { spans[it].length * 1000L / rate <= VoiceCommand.MAX_SPEECH_MS }
        if (short.isEmpty()) {
            normal()
            return
        }
        val pad = rate * COMMAND_PAD_MS / 1000
        fun slice(from: Int, to: Int) = pcm.copyOfRange(maxOf(0, from - pad), minOf(pcm.size, to + pad))
        recognizeEach(model, short.map { slice(spans[it].start, spans[it].end) }) { texts ->
            val commands = HashMap<Int, VoiceCommand>()
            short.forEachIndexed { k, i ->
                val c = texts[k]?.let { VoiceCommand.match(it) } ?: return@forEachIndexed
                if (!c.lastOnly || i == spans.lastIndex) commands[i] = c
            }
            if (commands.isEmpty()) {
                normal()
                return@recognizeEach
            }
            // コマンドの前後の区切りは、つながった音声ごとに文字にする
            val parts = ArrayList<Any>()
            var groupStart = -1
            for (i in spans.indices) {
                val c = commands[i]
                if (c == null) {
                    if (groupStart < 0) groupStart = i
                    continue
                }
                if (groupStart >= 0) parts += slice(spans[groupStart].start, spans[i - 1].end)
                groupStart = -1
                parts += c
            }
            if (groupStart >= 0) parts += slice(spans[groupStart].start, spans.last().end)
            recognizeEach(model, parts.filterIsInstance<FloatArray>()) { groupTexts ->
                var t = 0
                apply(parts.map { if (it is FloatArray) (groupTexts[t++] ?: "") else it })
            }
        }
    }

    private fun stopVoice() {
        val pcm = VoiceInput.stopRecording() ?: return
        main.removeCallbacks(partialTick)
        if (partialStreaming) StreamingRecognizer.finishStream()
        showIndicatorText("…", 0)
        recognizeUtterance(pcm, continuousMode = false)
    }

    /** ひと区切りの音声を文字にして入れる */
    private fun recognizeUtterance(pcm: FloatArray, continuousMode: Boolean) {
        val started = System.nanoTime()
        val generation = inputGeneration
        val partialAtStart = partialText
        val rate = WhisperModel.SAMPLE_RATE
        // 聞き続けるモードのひと区切りは、もとから前後に間がある。短ければコマンドかもしれない
        val commandCandidate = continuousMode && settings.voiceCommands &&
            PauseSplitter.split(pcm, rate, VoiceCommand.PAUSE_MS, VoiceCommand.MIN_SPEECH_MS)
                .let { it.size == 1 && it[0].length * 1000L / rate <= VoiceCommand.MAX_SPEECH_MS }
        /** 入力欄が替わっていたら（認識しているあいだに）捨てる。捨てたら false */
        fun stillHere(): Boolean {
            if (generation == inputGeneration) return true
            log("voice: 入力欄が替わったので捨てた")
            if (!continuousMode) {
                partialText = null
                voiceStreaming = false
            }
            return false
        }
        /** 押している間の録音の、途中の文字（未確定文字列・帯）を片付ける */
        fun clearPartial(typing: Boolean) {
            if (!typing && (partialText != null || !previewInBar)) {
                currentInputConnection?.let {
                    it.setComposingText("", 1)
                    it.finishComposingText()
                }
            }
            if (previewInBar) hideBar()
            partialText = null
            voiceStreaming = false
        }
        val finish = { text: String?, engineName: String ->
            main.post {
                if (!continuousMode) modeIndicator?.dismiss()
                if (!stillHere()) return@post
                val ms = (System.nanoTime() - started) / 1_000_000
                // 確定の文が作れなければ、話している途中に出していた文を使う
                val final = text?.let { spoken(it) } ?: partialAtStart
                log("voice: $engineName ${ms}ms ${final ?: "（認識できなかった）"}（認識そのまま: $text）")
                if (continuousMode) {
                    voiceSegments.removeFirstOrNull()
                    val command = if (commandCandidate && text != null) VoiceCommand.match(text) else null
                    if (command != null) {
                        showVoiceDisplay()
                        runVoiceCommand(command)
                        return@post
                    }
                    if (final != null) insertVoiceText(final)
                    showVoiceDisplay()
                    return@post
                }
                // 認識しているあいだに打ち始めていたら、その文字を先に確定する（未確定を上書きしないため）
                val typing = engine.isComposing
                if (typing && final != null) apply(engine.commitAll())
                clearPartial(typing)
                // 整える前の文字（話している途中に出していた文）も候補に出し、選び直せるようにする
                if (final != null) commitVoice(final, listOfNotNull(final, partialAtStart))
            }
        }
        val model = VoiceCatalog.finalModel(this, settings)
        if (model == null) {
            finish(null, "none")
            return
        }
        if (!continuousMode && settings.voiceCommands) {
            recognizeWithCommands(model, pcm, normal = {
                model.recognize(this, pcm, settings.voiceThreads) { text -> finish(text, model.id) }
            }) { parts ->
                main.post {
                    modeIndicator?.dismiss()
                    if (!stillHere()) return@post
                    log("voice: ${model.id} ${(System.nanoTime() - started) / 1_000_000}ms $parts")
                    val typing = engine.isComposing
                    if (typing) apply(engine.commitAll())
                    clearPartial(typing)
                    for (p in parts) when (p) {
                        is String -> spoken(p).takeIf { it.isNotEmpty() }?.let { commitVoice(it) }
                        is VoiceCommand -> runVoiceCommand(p)
                    }
                }
            }
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
        commitVoice(text)
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
        /** 逐次認識に音声を流す間隔（モデルは 560ms ごとに文字を出すので、その半分ほど） */
        const val STREAM_INTERVAL_MS = 280L
        /** 録音キーをこれより短く押したら、聞き続けるモード */
        const val TAP_MS = 300L
        const val VAD_INTERVAL_MS = 100L
        /** 聞き続けるモードで、これだけ話さなければ終える */
        /** 声のコマンドの区切りを文字にするとき、前後に足す音声（話し始め・終わりを切らないため） */
        const val COMMAND_PAD_MS = 150

        /** 話して入れる記号を使わないときに、認識が付けた句読点だけを捨てるための空の表 */
        val NO_SPOKEN_SYMBOLS = SpokenPunctuation(emptyList())

        /** 変換中に選んでいる文節の背景（薄い青。明るい画面でも暗い画面でも字が読める程度に） */
        val FOCUS_COLOR = Color.argb(0x60, 0x40, 0x80, 0xE0)
    }

    private fun log(note: String) {
        if (settings.keyLogEnabled) keyLog.note(note)
    }
}
