package dev.mooma.kanade

import android.content.Context
import dev.mooma.kanade.core.EngineSettings

/** US 配列で日本語オン/オフを切り替えるキー。JIS の変換・無変換・かなキーは常に有効 */
enum class ToggleTrigger(val labelRes: Int) {
    RIGHT_ALT_TAP(R.string.input_trigger_right_alt_tap),
    CTRL_SPACE(R.string.input_trigger_ctrl_space),
    F13(R.string.input_trigger_f13),
}

/** 画面キーボードの配列（切り替える順） */
val LAYOUTS = listOf("GLIDE", "QWERTY", "FLICK")

/** SharedPreferences の読み書き。IME とアクティビティの両方から使う */
class KanadeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("kanade", Context.MODE_PRIVATE)

    var toggleTriggers: Set<ToggleTrigger>
        get() = prefs.getStringSet(KEY_TRIGGERS, null)
            ?.mapNotNull { name -> ToggleTrigger.entries.find { it.name == name } }?.toSet()
            ?: ToggleTrigger.entries.toSet()
        set(value) = prefs.edit().putStringSet(KEY_TRIGGERS, value.map { it.name }.toSet()).apply()

    var fullWidthSymbols: Boolean
        get() = prefs.getBoolean(KEY_FULL_WIDTH, true)
        set(value) = prefs.edit().putBoolean(KEY_FULL_WIDTH, value).apply()

    /** 段階1では DeX で届くキーを洗い出すため既定で記録する */
    var keyLogEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEYLOG, true)
        set(value) = prefs.edit().putBoolean(KEY_KEYLOG, value).apply()

    var liveConversion: Boolean
        get() = prefs.getBoolean(KEY_LIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_LIVE, value).apply()

    /** 入力中に、読みの先を予測した候補を候補の窓に出す（既定は出さない） */
    var prediction: Boolean
        get() = prefs.getBoolean(KEY_PREDICTION, false)
        set(value) = prefs.edit().putBoolean(KEY_PREDICTION, value).apply()

    /**
     * zenz の使い方。"CORRECT"（既定）は Zenzai の方式で、辞書の1位を確かめ、ずれた所から辞書を探し直す。
     * "RERANK" は辞書の上位10件を採点して並べ替える。Fold 7 の評価セット 202 文で正解の数は同じ（187）で、
     * 時間は中央値 5.8ms と 24.7ms だった
     */
    var zenzMode: String
        get() = prefs.getString(KEY_ZENZ_MODE, "CORRECT") ?: "CORRECT"
        set(value) = prefs.edit().putString(KEY_ZENZ_MODE, value).apply()

    /** 変換のプロフィール（zenz に渡す。25文字まで。空なら渡さない） */
    var zenzProfile: String
        get() = prefs.getString(KEY_ZENZ_PROFILE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ZENZ_PROFILE, value).apply()

    /** zenz の推論のスレッド数 */
    var zenzThreads: Int
        get() = prefs.getInt(KEY_ZENZ_THREADS, DEFAULT_ZENZ_THREADS)
        set(value) = prefs.edit().putInt(KEY_ZENZ_THREADS, value).apply()

    /** 未確定文字列をうまく出せないアプリ（端末など）。ここに入れたアプリでは、入力中の文字を候補欄に出す */
    var previewInBarPackages: Set<String>
        get() = prefs.getString(KEY_PREVIEW_PACKAGES, null)?.split(',', ' ', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: DEFAULT_PREVIEW_PACKAGES
        set(value) = prefs.edit().putString(KEY_PREVIEW_PACKAGES, value.joinToString(",")).apply()

    /** 音声入力のモデル */
    var voiceModel: VoiceModel
        get() = VoiceModel.entries.find { it.name == prefs.getString(KEY_VOICE_MODEL, null) } ?: VoiceModel.SMALL
        set(value) = prefs.edit().putString(KEY_VOICE_MODEL, value.name).apply()

    /** 押している間だけ録音するキー（KeyEvent のキーコード）。既定は F12 */
    var voiceKey: Int
        get() = prefs.getInt(KEY_VOICE_KEY, android.view.KeyEvent.KEYCODE_F12)
        set(value) = prefs.edit().putInt(KEY_VOICE_KEY, value).apply()

    /**
     * 確定の文を作るモデル（設定画面の「モデル」で適用したもの。[VoiceCatalog] の id）。
     * 前の版の値（WHISPER・REAZON・SENSEVOICE・PARAKEET）は読み替える
     */
    var voiceFinal: String
        get() = when (val v = prefs.getString(KEY_VOICE_FINAL, null)) {
            null -> if (prefs.contains(KEY_VOICE_MODEL)) "whisper-" + voiceModel.name.lowercase() else "parakeet"
            "WHISPER" -> "whisper-" + voiceModel.name.lowercase()
            "REAZON", "SENSEVOICE", "PARAKEET" -> v.lowercase()
            else -> v
        }
        set(value) = prefs.edit().putString(KEY_VOICE_FINAL, value).apply()

    /** 話しながら文字を出す（ReazonSpeech）。切ると、離してから確定の認識で文字にするだけ */
    var voiceStreaming: Boolean
        get() = prefs.getBoolean(KEY_VOICE_STREAMING, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_STREAMING, value).apply()

    var voiceThreads: Int
        get() = prefs.getInt(KEY_VOICE_THREADS, 4)
        set(value) = prefs.edit().putInt(KEY_VOICE_THREADS, value).apply()

    /** よく選ぶ候補を上げる（前に選んだ候補を覚える） */
    var learningEnabled: Boolean
        get() = prefs.getBoolean(KEY_LEARNING, true)
        set(value) = prefs.edit().putBoolean(KEY_LEARNING, value).apply()

    /** 設定画面で学習を消した時刻。IME はこれが変わったら覚えたものを捨てる */
    var learningResetAt: Long
        get() = prefs.getLong(KEY_LEARNING_RESET, 0L)
        set(value) = prefs.edit().putLong(KEY_LEARNING_RESET, value).apply()

    /** 画面キーボード: AUTO（物理キーボードがないときだけ）・ALWAYS・NEVER */
    var softKeyboard: String
        get() = prefs.getString(KEY_SOFT_KEYBOARD, "AUTO") ?: "AUTO"
        set(value) = prefs.edit().putString(KEY_SOFT_KEYBOARD, value).apply()

    /** 画面キーボードの高さ（%、60〜300） */
    var softKeyboardHeight: Int
        get() = prefs.getInt(KEY_SOFT_KEYBOARD_HEIGHT, 100)
        set(value) = prefs.edit().putInt(KEY_SOFT_KEYBOARD_HEIGHT, value).apply()

    /** 画面キーボードの下に足す余白（dp、0〜64） */
    var softKeyboardBottom: Int
        get() = prefs.getInt(KEY_SOFT_KEYBOARD_BOTTOM, 0)
        set(value) = prefs.edit().putInt(KEY_SOFT_KEYBOARD_BOTTOM, value).apply()

    /** 長押しで繰り返すキー（Backspace・矢印など）の、繰り返し始めるまでの待ち（ms） */
    var repeatDelayMs: Int
        get() = prefs.getInt(KEY_REPEAT_DELAY, KeyRepeat.DEFAULT_DELAY_MS).coerceIn(KeyRepeat.DELAY_RANGE)
        set(value) = prefs.edit().putInt(KEY_REPEAT_DELAY, value).apply()

    /** 長押しで繰り返す間隔（ms。短いほど速い） */
    var repeatIntervalMs: Int
        get() = prefs.getInt(KEY_REPEAT_INTERVAL, KeyRepeat.DEFAULT_INTERVAL_MS).coerceIn(KeyRepeat.INTERVAL_RANGE)
        set(value) = prefs.edit().putInt(KEY_REPEAT_INTERVAL, value).apply()

    /** 縦の折り目で画面キーボードを左右に分ける: ALWAYS（平らでも）・FOLDED（折り曲げたときだけ）・NEVER */
    var foldSplit: FoldSplit
        get() = FoldSplit.of(prefs.getString(KEY_FOLD_SPLIT, null))
        set(value) = prefs.edit().putString(KEY_FOLD_SPLIT, value.name).apply()

    /** 話して句読点を入れる（「くてん」→「。」など） */
    var spokenPunctuation: Boolean
        get() = prefs.getBoolean(KEY_SPOKEN_PUNCT, true)
        set(value) = prefs.edit().putBoolean(KEY_SPOKEN_PUNCT, value).apply()

    /** 音声認識が自分で付ける句読点（、。）を残す。切ると、話して入れた句読点だけになる */
    var voiceAutoPunctuation: Boolean
        get() = prefs.getBoolean(KEY_VOICE_AUTO_PUNCT, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_AUTO_PUNCT, value).apply()

    /** 「えー」「えーと」などの言いよどみを消す（既定は切る。整えすぎを嫌う人が多いため） */
    var voiceFillers: Boolean
        get() = prefs.getBoolean(KEY_VOICE_FILLERS, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_FILLERS, value).apply()

    /** 声のコマンド（前後に間を置いて「送信して」「取り消して」など） */
    var voiceCommands: Boolean
        get() = prefs.getBoolean(KEY_VOICE_COMMANDS, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_COMMANDS, value).apply()

    /** 音声の文で、ユーザー辞書の読みに当たる所をその語にする */
    var voiceUserDictionary: Boolean
        get() = prefs.getBoolean(KEY_VOICE_USER_DICT, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_USER_DICT, value).apply()

    /** 聞き続けるモードで、これだけ話さなければ終える（秒。0 なら終えない） */
    var continuousIdleSeconds: Int
        get() = prefs.getInt(KEY_CONTINUOUS_IDLE, 60)
        set(value) = prefs.edit().putInt(KEY_CONTINUOUS_IDLE, value).apply()

    /**
     * 画面キーボードの配列: GLIDE（グライドの QWERTY）・QWERTY（払いで記号を出すフルキー）・FLICK（テンキー）。
     * 使う配列（[enabledLayouts]）に入っていなければ、使う配列の最初のものにする
     */
    var softKeyboardLayout: String
        get() {
            val enabled = enabledLayouts
            val saved = prefs.getString(KEY_SOFT_KEYBOARD_LAYOUT, null)
            return if (saved != null && saved in enabled) saved else LAYOUTS.first { it in enabled }
        }
        set(value) = prefs.edit().putString(KEY_SOFT_KEYBOARD_LAYOUT, value).apply()

    /** 日本語のグライドの候補を zenz で並べ直す（辞書の1位を先に入れ、zenz が別の語を選べば入れ替える） */
    var glideZenz: Boolean
        get() = prefs.getBoolean(KEY_GLIDE_ZENZ, true)
        set(value) = prefs.edit().putBoolean(KEY_GLIDE_ZENZ, value).apply()

    /** 使う配列（左下のキーの上払いで、この中を順に替える）。既定はグライドとテンキー。空にはしない */
    var enabledLayouts: Set<String>
        get() = prefs.getStringSet(KEY_ENABLED_LAYOUTS, null)?.filter { it in LAYOUTS }?.toSet()?.ifEmpty { null } ?: setOf("GLIDE", "FLICK")
        set(value) = prefs.edit().putStringSet(KEY_ENABLED_LAYOUTS, value.filter { it in LAYOUTS }.toSet().ifEmpty { setOf("GLIDE") }).apply()

    /** 画面キーボードを浮かせる（好きな位置・幅に動かせる） */
    var softKeyboardFloating: Boolean
        get() = prefs.getBoolean(KEY_FLOAT, false)
        set(value) = prefs.edit().putBoolean(KEY_FLOAT, value).apply()

    /** 浮かせたキーボードの位置（空いている範囲の中の割合）と幅（画面の幅に対する割合。0 なら自動） */
    var floatX: Float
        get() = prefs.getFloat(KEY_FLOAT_X, 0.5f)
        set(value) = prefs.edit().putFloat(KEY_FLOAT_X, value).apply()
    var floatY: Float
        get() = prefs.getFloat(KEY_FLOAT_Y, 1f)
        set(value) = prefs.edit().putFloat(KEY_FLOAT_Y, value).apply()
    var floatWidth: Float
        get() = prefs.getFloat(KEY_FLOAT_WIDTH, 0f)
        set(value) = prefs.edit().putFloat(KEY_FLOAT_WIDTH, value).apply()

    /** フリック配列でトグル入力（同じキーを続けて押すと次の文字）も使う。切るとフリックだけ */
    var flickToggle: Boolean
        get() = prefs.getBoolean(KEY_FLICK_TOGGLE, true)
        set(value) = prefs.edit().putBoolean(KEY_FLICK_TOGGLE, value).apply()

    /** コピーした文字・画像を履歴に残す（画面キーボードのクリップボード） */
    var clipboardHistory: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD_HISTORY, true)
        set(value) = prefs.edit().putBoolean(KEY_CLIPBOARD_HISTORY, value).apply()

    /** クリップボードの履歴を残す時間（時間。0 なら期限なし）。ピン留めしたものは消さない */
    var clipboardExpiryHours: Int
        get() = prefs.getInt(KEY_CLIPBOARD_EXPIRY, 0)
        set(value) = prefs.edit().putInt(KEY_CLIPBOARD_EXPIRY, value).apply()

    /** 物理キーボードでクリップボードを開くキー。既定は Ctrl+Shift+V。null なら使わない（空の文字で残す） */
    var clipboardShortcut: KeyShortcut?
        get() = when (val s = prefs.getString(KEY_CLIPBOARD_SHORTCUT, null)) {
            null -> KeyShortcut.CLIPBOARD_DEFAULT
            "" -> null
            else -> KeyShortcut.decode(s) ?: KeyShortcut.CLIPBOARD_DEFAULT
        }
        set(value) = prefs.edit().putString(KEY_CLIPBOARD_SHORTCUT, value?.encode() ?: "").apply()

    /**
     * 物理キーボードのクリップボードのパネルの位置と大きさ（画面の座標、px）。位置が -1 ならカーソルの近くに出す、
     * 大きさが 0 なら自動（幅は画面に合わせ、高さは中身に合わせる）。パネルの下のつまみで動かす・変えると残る
     */
    var clipboardOverlayX: Int
        get() = prefs.getInt(KEY_CLIP_OVERLAY_X, -1)
        set(value) = prefs.edit().putInt(KEY_CLIP_OVERLAY_X, value).apply()
    var clipboardOverlayY: Int
        get() = prefs.getInt(KEY_CLIP_OVERLAY_Y, -1)
        set(value) = prefs.edit().putInt(KEY_CLIP_OVERLAY_Y, value).apply()
    var clipboardOverlayWidth: Int
        get() = prefs.getInt(KEY_CLIP_OVERLAY_W, 0)
        set(value) = prefs.edit().putInt(KEY_CLIP_OVERLAY_W, value).apply()
    var clipboardOverlayHeight: Int
        get() = prefs.getInt(KEY_CLIP_OVERLAY_H, 0)
        set(value) = prefs.edit().putInt(KEY_CLIP_OVERLAY_H, value).apply()

    var lastJapanese: Boolean
        get() = prefs.getBoolean(KEY_LAST_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_MODE, value).apply()

    /** 句読点の組み合わせ（「、。」「，．」「、．」「，。」） */
    var punctuation: String
        get() = prefs.getString(KEY_PUNCTUATION, "、。") ?: "、。"
        set(value) = prefs.edit().putString(KEY_PUNCTUATION, value).apply()

    /** 画面キーボードで払いと見なす指の距離（キーの幅に対する %、25〜80） */
    var softKeyboardSwipe: Int
        get() = prefs.getInt(KEY_SWIPE, SoftKeyboardView.DEFAULT_SWIPE_PERCENT)
        set(value) = prefs.edit().putInt(KEY_SWIPE, value).apply()

    /** 配列ごとに使う言語（JA・EN・NUM）。既定はすべて */
    fun layoutModes(layout: String): Set<String> =
        prefs.getStringSet(KEY_LAYOUT_MODES + layout, null)?.filter { it in KeyboardSources.choosableModes(layout) }?.toSet()?.ifEmpty { null }
            ?: KeyboardSources.choosableModes(layout).toSet()

    fun setLayoutModes(layout: String, modes: Set<String>) {
        val valid = modes.filter { it in KeyboardSources.choosableModes(layout) }.toSet()
        if (valid.isNotEmpty()) prefs.edit().putStringSet(KEY_LAYOUT_MODES + layout, valid).apply()
    }

    /** グライド・テンキーで、タップの位置がずれても入力中の語から推測して文字を選ぶ */
    var tapCorrection: Boolean
        get() = prefs.getBoolean(KEY_TAP_CORRECTION, true)
        set(value) = prefs.edit().putBoolean(KEY_TAP_CORRECTION, value).apply()

    /** 開き括弧を打ったら閉じ括弧も入れ、カーソルを中に置く */
    var pairBrackets: Boolean
        get() = prefs.getBoolean(KEY_PAIR_BRACKETS, true)
        set(value) = prefs.edit().putBoolean(KEY_PAIR_BRACKETS, value).apply()

    fun engineSettings() = EngineSettings(
        fullWidthSymbols = fullWidthSymbols, liveConversion = liveConversion, punctuation = punctuation, pairBrackets = pairBrackets,
        prediction = prediction,
    )

    private companion object {
        /**
         * Fold 7 で測って決めた（[ConversionBench.runEval] がスレッド数ごとの時間も出す）。並べ替え10件の中央値は
         * 2本 57.9ms、3本 51.5ms、4本 45.3ms、6本 39.4ms、8本 66.5ms。CPU の数より多ければ CPU の数にする
         */
        const val DEFAULT_ZENZ_THREADS = 6
        const val KEY_TRIGGERS = "toggle_triggers"
        const val KEY_FULL_WIDTH = "full_width_symbols"
        const val KEY_KEYLOG = "key_log"
        const val KEY_LAST_MODE = "last_japanese"
        const val KEY_LIVE = "live_conversion"
        const val KEY_PREDICTION = "prediction"
        const val KEY_ZENZ_MODE = "zenz_mode"
        const val KEY_ZENZ_PROFILE = "zenz_profile"
        const val KEY_ZENZ_THREADS = "zenz_threads"
        const val KEY_SOFT_KEYBOARD = "soft_keyboard"
        const val KEY_SOFT_KEYBOARD_HEIGHT = "soft_keyboard_height"
        const val KEY_SOFT_KEYBOARD_BOTTOM = "soft_keyboard_bottom"
        const val KEY_FOLD_SPLIT = "fold_split"
        const val KEY_REPEAT_DELAY = "key_repeat_delay"
        const val KEY_REPEAT_INTERVAL = "key_repeat_interval"
        const val KEY_SOFT_KEYBOARD_LAYOUT = "soft_keyboard_layout"
        const val KEY_FLICK_TOGGLE = "flick_toggle"
        const val KEY_ENABLED_LAYOUTS = "enabled_layouts"
        const val KEY_GLIDE_ZENZ = "glide_zenz"
        const val KEY_PAIR_BRACKETS = "pair_brackets"
        const val KEY_SWIPE = "soft_keyboard_swipe"
        const val KEY_TAP_CORRECTION = "tap_correction"
        const val KEY_LAYOUT_MODES = "layout_modes_"
        const val KEY_FLOAT = "soft_keyboard_floating"
        const val KEY_FLOAT_X = "float_x"
        const val KEY_FLOAT_Y = "float_y"
        const val KEY_FLOAT_WIDTH = "float_width"
        const val KEY_CLIPBOARD_HISTORY = "clipboard_history"
        const val KEY_CLIPBOARD_EXPIRY = "clipboard_expiry_hours"
        const val KEY_CLIPBOARD_SHORTCUT = "clipboard_shortcut"
        const val KEY_CLIP_OVERLAY_X = "clipboard_overlay_x"
        const val KEY_CLIP_OVERLAY_Y = "clipboard_overlay_y"
        const val KEY_CLIP_OVERLAY_W = "clipboard_overlay_width"
        const val KEY_CLIP_OVERLAY_H = "clipboard_overlay_height"
        const val KEY_SPOKEN_PUNCT = "spoken_punctuation"
        const val KEY_VOICE_AUTO_PUNCT = "voice_auto_punctuation"
        const val KEY_VOICE_FILLERS = "voice_fillers"
        const val KEY_VOICE_COMMANDS = "voice_commands"
        const val KEY_VOICE_USER_DICT = "voice_user_dictionary"
        const val KEY_CONTINUOUS_IDLE = "continuous_idle_seconds"
        const val KEY_VOICE_FINAL = "voice_final"
        const val KEY_PUNCTUATION = "punctuation"
        const val KEY_VOICE_MODEL = "voice_model"
        const val KEY_LEARNING = "learning"
        const val KEY_LEARNING_RESET = "learning_reset_at"
        const val KEY_VOICE_KEY = "voice_key"
        const val KEY_VOICE_THREADS = "voice_threads"
        const val KEY_VOICE_STREAMING = "voice_streaming"
        const val KEY_PREVIEW_PACKAGES = "preview_in_bar_packages"
        val DEFAULT_PREVIEW_PACKAGES = setOf("com.termux")
    }
}
