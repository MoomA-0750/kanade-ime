package dev.mooma.kanade

import android.content.Context
import dev.mooma.kanade.core.EngineSettings

/** US 配列で日本語オン/オフを切り替えるキー。JIS の変換・無変換・かなキーは常に有効 */
enum class ToggleTrigger(val label: String) {
    RIGHT_ALT_TAP("右 Alt の単押し"),
    CTRL_SPACE("Ctrl+Space"),
    F13("F13（QMK などから送る専用キー）"),
}

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

    /** 話しながら文字を出す（ReazonSpeech）。切ると、離してから whisper で文字にするだけ */
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

    /** 画面キーボードの高さ（%、60〜150） */
    var softKeyboardHeight: Int
        get() = prefs.getInt(KEY_SOFT_KEYBOARD_HEIGHT, 100)
        set(value) = prefs.edit().putInt(KEY_SOFT_KEYBOARD_HEIGHT, value).apply()

    /** 画面キーボードの下に足す余白（dp、0〜64） */
    var softKeyboardBottom: Int
        get() = prefs.getInt(KEY_SOFT_KEYBOARD_BOTTOM, 0)
        set(value) = prefs.edit().putInt(KEY_SOFT_KEYBOARD_BOTTOM, value).apply()

    /** 話して句読点を入れる（「くてん」→「。」など） */
    var spokenPunctuation: Boolean
        get() = prefs.getBoolean(KEY_SPOKEN_PUNCT, true)
        set(value) = prefs.edit().putBoolean(KEY_SPOKEN_PUNCT, value).apply()

    var lastJapanese: Boolean
        get() = prefs.getBoolean(KEY_LAST_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_LAST_MODE, value).apply()

    /** 句読点の組み合わせ（「、。」「，．」「、．」「，。」） */
    var punctuation: String
        get() = prefs.getString(KEY_PUNCTUATION, "、。") ?: "、。"
        set(value) = prefs.edit().putString(KEY_PUNCTUATION, value).apply()

    fun engineSettings() = EngineSettings(fullWidthSymbols = fullWidthSymbols, liveConversion = liveConversion, punctuation = punctuation)

    private companion object {
        const val KEY_TRIGGERS = "toggle_triggers"
        const val KEY_FULL_WIDTH = "full_width_symbols"
        const val KEY_KEYLOG = "key_log"
        const val KEY_LAST_MODE = "last_japanese"
        const val KEY_LIVE = "live_conversion"
        const val KEY_SOFT_KEYBOARD = "soft_keyboard"
        const val KEY_SOFT_KEYBOARD_HEIGHT = "soft_keyboard_height"
        const val KEY_SOFT_KEYBOARD_BOTTOM = "soft_keyboard_bottom"
        const val KEY_SPOKEN_PUNCT = "spoken_punctuation"
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
