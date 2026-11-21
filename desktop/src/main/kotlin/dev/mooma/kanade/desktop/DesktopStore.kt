package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.EngineSettings
import org.json.JSONObject
import java.io.File
import java.util.Properties

/** Windows 版の設定（項目名は Android 版の KanadeSettings と同じにして、Android の書き出しファイルをそのまま読めるようにする） */
data class DesktopSettings(
    val fullWidthSymbols: Boolean = true,
    val liveConversion: Boolean = true,
    val punctuation: String = "、。",
    /** 開き括弧を打ったら閉じ括弧も入れ、カーソルを中に置く */
    val pairBrackets: Boolean = true,
    /** `:` に続けて絵文字の名前を打つと絵文字を候補に出す（`:rocket` → 🚀） */
    val emojiShortcodes: Boolean = true,
    /** 絵文字の名前の入り口の文字（1文字。EngineSettings.isEmojiPrefix で使えるもの） */
    val emojiPrefix: String = ":",
    /** 入り口の文字を2回続けたときだけ始める（`::rocket`） */
    val emojiPrefixTwice: Boolean = false,
    /** zenz の使い方（Android 版と同じ）。CORRECT は1位を確かめて直す（軽い）、RERANK は候補を全部並べ替える（打つたびに重い） */
    val zenzMode: String = "CORRECT",
    /** 入力欄に入ったとき（IME が働き始めたとき）の始めのモード: LAST（前のまま）・JAPANESE・ENGLISH */
    val startMode: String = "LAST",
    /** macOS の版: 日本語⇔英字の Control+Space・🌐 を Kanade が取る（KANADE）か、macOS の入力ソースの切り替えに任せる（MACOS） */
    val macSwitchKeys: String = "KANADE",
    /** macOS の版: キーを素通しにするアプリ（リモートデスクトップなど）の bundle id を足す（カンマ区切り）。標準の分に足される */
    val remoteApps: String = "",
    val toggles: Set<WinToggle> = WinToggle.entries.toSet(),
    /** 入力中の Ctrl のキーの割り当て（PC は Ctrl+U/I/O/P/T、MAC は Ctrl+J/K/L/;）。既定は macOS なら MAC */
    val keyStyle: KeyStyle = KeyStyle.DEFAULT,
    val spokenPunctuation: Boolean = true,
    /** 録音を始めた・終えたときに効果音を鳴らす */
    val voiceSounds: Boolean = true,
    /** 声のコマンド（送って・次へ・全部消して・止めて・取り消して）を受け取る */
    val voiceCommands: Boolean = true,
    /** 聞き続けるモードで、これだけ話さなければ終える（秒。0 なら終えない） */
    val continuousIdleSeconds: Int = 60,
    val learning: Boolean = true,
    val lastJapanese: Boolean = false,
    /** 確定の文を作る音声認識のモデル（Android 版と同じ id。VoiceFinal） */
    val voiceFinal: String = VoiceFinal.DEFAULT.id,
    /** 画面に出す文字の言語（auto・ja・en。Ui） */
    val uiLanguage: String = "auto",
    /** 英語の口述のモデル（Linux 版。Kanade 以外の入力方式で録音キーを押したとき。VoiceFinal.english） */
    val voiceFinalEn: String = VoiceFinal.DEFAULT_EN.id,
    /** Linux 版の、どの入力方式でも効く録音キー（fcitx5 のキーの名前をカンマ区切り）。押している間だけ録音する */
    val voiceHoldKeys: String = "F12,Alt_R",
    /** Linux 版の、押すたびに録音を始める・終える録音キー（fcitx5 のキーの名前。空なら使わない） */
    val voiceToggleKey: String = "Control+Alt+space",
) {
    fun engineSettings() = EngineSettings(
        fullWidthSymbols = fullWidthSymbols, liveConversion = liveConversion, punctuation = punctuation, pairBrackets = pairBrackets,
        emojiShortcodes = emojiShortcodes, emojiPrefix = emojiPrefix.single(), emojiPrefixTwice = emojiPrefixTwice,
    )
}

/**
 * 設定・ユーザー辞書・スニペット・学習の保存先（%APPDATA%\Kanade）。どれも人がメモ帳で編集できるテキストにして、
 * エンジンは変更を見て読み直す（設定の画面を作るまでは、タスクトレイのメニューからメモ帳で開く）。
 * ユーザー辞書・スニペット・学習の書式は Android 版と同じ
 */
class DesktopStore(val dir: File = defaultDir()) {
    val settingsFile = File(dir, "settings.properties")
    val userDictionaryFile = File(dir, "user_dictionary.txt")
    val snippetsFile = File(dir, "snippets.txt")
    val learningFile = File(dir, "learning.txt")

    /** 初めてのときに例を書いておく（Android 版の初めの中身と同じ） */
    fun ensureFiles() {
        dir.mkdirs()
        if (!userDictionaryFile.exists()) write(userDictionaryFile, "かなで Kanade\nでっくす DeX\n")
        if (!snippetsFile.exists()) write(snippetsFile, "-> →\n<- ←\n=> ⇒\n")
        if (!settingsFile.exists()) saveSettings(DesktopSettings())
    }

    fun loadSettings(): DesktopSettings {
        if (!settingsFile.exists()) return DesktopSettings()
        val p = Properties()
        settingsFile.reader(Charsets.UTF_8).use { p.load(it) }
        val d = DesktopSettings()
        fun bool(key: String, default: Boolean) = p.getProperty(key)?.trim()?.toBooleanStrictOrNull() ?: default
        return DesktopSettings(
            fullWidthSymbols = bool(KEY_FULL_WIDTH, d.fullWidthSymbols),
            liveConversion = bool(KEY_LIVE, d.liveConversion),
            punctuation = p.getProperty(KEY_PUNCTUATION)?.trim()?.takeIf { it.length == 2 } ?: d.punctuation,
            pairBrackets = bool(KEY_PAIR_BRACKETS, d.pairBrackets),
            emojiShortcodes = bool(KEY_EMOJI, d.emojiShortcodes),
            emojiPrefix = p.getProperty(KEY_EMOJI_PREFIX)?.trim()?.let(::emojiPrefixOrNull) ?: d.emojiPrefix,
            emojiPrefixTwice = bool(KEY_EMOJI_TWICE, d.emojiPrefixTwice),
            zenzMode = p.getProperty(KEY_ZENZ_MODE)?.trim()?.uppercase()?.takeIf { it in ZENZ_MODES } ?: d.zenzMode,
            startMode = p.getProperty(KEY_START_MODE)?.trim()?.uppercase()?.takeIf { it in START_MODES } ?: d.startMode,
            remoteApps = p.getProperty(KEY_REMOTE_APPS)?.trim() ?: d.remoteApps,
            macSwitchKeys = p.getProperty(KEY_MAC_SWITCH_KEYS)?.trim()?.uppercase()?.takeIf { it in MAC_SWITCH_KEYS } ?: d.macSwitchKeys,
            toggles = p.getProperty(KEY_TRIGGERS)?.split(',')?.mapNotNull { name -> WinToggle.entries.find { it.name == name.trim() } }?.toSet()
                ?: d.toggles,
            keyStyle = p.getProperty(KEY_KEY_STYLE)?.trim()?.uppercase()?.let { v -> KeyStyle.entries.find { it.name == v } } ?: d.keyStyle,
            spokenPunctuation = bool(KEY_SPOKEN_PUNCT, d.spokenPunctuation),
            voiceSounds = bool(KEY_VOICE_SOUNDS, d.voiceSounds),
            voiceCommands = bool(KEY_VOICE_COMMANDS, d.voiceCommands),
            continuousIdleSeconds = p.getProperty(KEY_CONTINUOUS_IDLE)?.trim()?.toIntOrNull()?.takeIf { it in CONTINUOUS_IDLES } ?: d.continuousIdleSeconds,
            learning = bool(KEY_LEARNING, d.learning),
            lastJapanese = bool(KEY_LAST_MODE, d.lastJapanese),
            voiceFinal = p.getProperty(KEY_VOICE_FINAL)?.trim()?.takeIf { id -> VoiceFinal.entries.any { it.id == id } } ?: d.voiceFinal,
            uiLanguage = p.getProperty(KEY_UI_LANGUAGE)?.trim()?.takeIf { it in UI_LANGUAGES } ?: d.uiLanguage,
            voiceFinalEn = p.getProperty(KEY_VOICE_FINAL_EN)?.trim()?.takeIf { id -> VoiceFinal.entries.any { it.id == id && it.english } } ?: d.voiceFinalEn,
            voiceHoldKeys = p.getProperty(KEY_VOICE_HOLD)?.let(::keyList) ?: d.voiceHoldKeys,
            voiceToggleKey = p.getProperty(KEY_VOICE_TOGGLE)?.let(::keyList) ?: d.voiceToggleKey,
        )
    }

    fun saveSettings(s: DesktopSettings) {
        val text = buildString {
            appendLine("# Kanade（Windows 版）の設定。保存するとすぐ反映されます")
            appendLine("# 日本語入力中の数字・記号・大文字を全角にする")
            appendLine("$KEY_FULL_WIDTH=${s.fullWidthSymbols}")
            appendLine("# 打鍵ごとに変換結果を出す（ライブ変換）")
            appendLine("$KEY_LIVE=${s.liveConversion}")
            appendLine("# 句読点（、。 ，． 、． ，。 のどれか）")
            appendLine("$KEY_PUNCTUATION=${s.punctuation}")
            appendLine("# 開き括弧を打ったら閉じ括弧も入れ、カーソルを中に置く")
            appendLine("$KEY_PAIR_BRACKETS=${s.pairBrackets}")
            appendLine("# : に続けて絵文字の名前を打つと絵文字を候補に出す（:rocket → 🚀）")
            appendLine("$KEY_EMOJI=${s.emojiShortcodes}")
            appendLine("# 絵文字の名前の入り口の文字（: ; / @ ! など、記号1文字）")
            appendLine("$KEY_EMOJI_PREFIX=${s.emojiPrefix}")
            appendLine("# 入り口の文字を2回続けて打ったときだけ絵文字の名前にする（::rocket）")
            appendLine("$KEY_EMOJI_TWICE=${s.emojiPrefixTwice}")
            appendLine("# zenz の使い方（CORRECT は1位を確かめて直す・軽い、RERANK は候補を全部並べ替える・打つたびに重い）")
            appendLine("$KEY_ZENZ_MODE=${s.zenzMode}")
            appendLine("$KEY_START_MODE=${s.startMode}")
            appendLine("$KEY_MAC_SWITCH_KEYS=${s.macSwitchKeys}")
            appendLine("$KEY_REMOTE_APPS=${s.remoteApps}")
            appendLine("# 日本語のオン/オフに使うキー（RIGHT_ALT_TAP・CTRL_SPACE・F13 をカンマ区切りで。変換/無変換・半角/全角はいつでも使える）")
            appendLine("$KEY_TRIGGERS=${s.toggles.joinToString(",") { it.name }}")
            appendLine("# 入力中の Ctrl のキー（PC は Ctrl+U/I/O/P/T でひらがな・カタカナ・半角カナ・全角英数・半角英数、MAC は macOS と同じ Ctrl+J/K/L/; でひらがな・カタカナ・全角英字・半角英字。F6〜F10 はどちらでも使える）")
            appendLine("$KEY_KEY_STYLE=${s.keyStyle.name}")
            appendLine("# 音声入力で「まる」「てん」などを句読点にする")
            appendLine("$KEY_SPOKEN_PUNCT=${s.spokenPunctuation}")
            appendLine("# 録音を始めた・終えたときに効果音を鳴らす")
            appendLine("$KEY_VOICE_SOUNDS=${s.voiceSounds}")
            appendLine("# 声のコマンド（送って・次へ・全部消して・止めて・取り消して）を受け取る")
            appendLine("$KEY_VOICE_COMMANDS=${s.voiceCommands}")
            appendLine("# 聞き続けるモード（録音キーを短く押す）で、これだけ話さなければ終える秒数（30・60・120。0 なら終えない）")
            appendLine("$KEY_CONTINUOUS_IDLE=${s.continuousIdleSeconds}")
            appendLine("# 音声入力の確定の文を作るモデル（parakeet・reazon・sensevoice・whisper-small・whisper-kotoba・whisper-turbo）")
            appendLine("$KEY_VOICE_FINAL=${s.voiceFinal}")
            appendLine("# 選んだ変換を覚えて次から先に出す")
            appendLine("$KEY_LEARNING=${s.learning}")
            appendLine("# 画面に出す文字の言語（auto は Windows の表示言語に合わせる・ja・en）")
            appendLine("$KEY_UI_LANGUAGE=${s.uiLanguage}")
            appendLine("# Linux 版: 英語の口述のモデル（Kanade 以外の入力方式で録音キーを押したとき。parakeet-en・whisper-small・whisper-turbo）")
            appendLine("$KEY_VOICE_FINAL_EN=${s.voiceFinalEn}")
            appendLine("# Linux 版: どの入力方式でも、押している間だけ録音するキー（fcitx5 のキーの名前をカンマ区切り。例 F12,Alt_R,F9）")
            appendLine("$KEY_VOICE_HOLD=${s.voiceHoldKeys}")
            appendLine("# Linux 版: 押すたびに録音を始める・終えるキー（例 Control+Alt+space。空なら使わない）。録音中の Esc で取り消す")
            appendLine("$KEY_VOICE_TOGGLE=${s.voiceToggleKey}")
            appendLine("# 前回の日本語のオン/オフ（エンジンが書く）")
            appendLine("$KEY_LAST_MODE=${s.lastJapanese}")
        }
        write(settingsFile, text)
    }

    fun read(file: File): String = if (file.exists()) file.readText() else ""

    /** 消えないように、一時ファイルに書いてから置き換える */
    fun write(file: File, text: String) {
        file.parentFile.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** 変わったかを見るための目印 */
    fun version(): List<Long> = listOf(settingsFile, userDictionaryFile, snippetsFile).map { if (it.exists()) it.lastModified() else 0L }

    data class ImportResult(val settings: Int, val files: Int)

    /**
     * Android 版の「設定の書き出し」のファイル（kanade-settings-*.json）を読む。Windows でも意味のある設定と、ユーザー辞書・スニペットを取り込む。
     * 今のユーザー辞書・スニペットは .bak に残してから置き換える
     */
    fun importAndroid(json: File): ImportResult {
        val root = runCatching { JSONObject(json.readText()) }.getOrElse { error(Ui.t("Kanade の設定のファイルではありません", "This is not a Kanade settings file")) }
        if (root.optString("format") != "kanade-settings") error(Ui.t("Kanade の設定のファイルではありません", "This is not a Kanade settings file"))
        if (root.optInt("version") > 1) error(Ui.t("新しい Kanade で書き出したファイルです", "This file was exported by a newer Kanade"))
        val prefs = root.optJSONObject("prefs") ?: JSONObject()
        fun value(key: String): Any? = prefs.optJSONObject(key)?.opt("value")
        val now = loadSettings()
        var count = 0
        fun <T> take(key: String, convert: (Any) -> T?, current: T): T {
            val v = value(key)?.let(convert) ?: return current
            count++
            return v
        }
        val imported = now.copy(
            fullWidthSymbols = take(KEY_FULL_WIDTH, { it as? Boolean }, now.fullWidthSymbols),
            liveConversion = take(KEY_LIVE, { it as? Boolean }, now.liveConversion),
            punctuation = take(KEY_PUNCTUATION, { (it as? String)?.takeIf { s -> s.length == 2 } }, now.punctuation),
            pairBrackets = take(KEY_PAIR_BRACKETS, { it as? Boolean }, now.pairBrackets),
            emojiShortcodes = take(KEY_EMOJI, { it as? Boolean }, now.emojiShortcodes),
            emojiPrefix = take(KEY_EMOJI_PREFIX, { (it as? String)?.let(::emojiPrefixOrNull) }, now.emojiPrefix),
            emojiPrefixTwice = take(KEY_EMOJI_TWICE, { it as? Boolean }, now.emojiPrefixTwice),
            zenzMode = take(KEY_ZENZ_MODE, { (it as? String)?.takeIf { m -> m in ZENZ_MODES } }, now.zenzMode),
            startMode = take(KEY_START_MODE, { (it as? String)?.takeIf { m -> m in START_MODES } }, now.startMode),
            remoteApps = take(KEY_REMOTE_APPS, { it as? String }, now.remoteApps),
            macSwitchKeys = take(KEY_MAC_SWITCH_KEYS, { (it as? String)?.takeIf { m -> m in MAC_SWITCH_KEYS } }, now.macSwitchKeys),
            toggles = take(KEY_TRIGGERS, { v ->
                (v as? org.json.JSONArray)?.let { arr -> (0 until arr.length()).mapNotNull { i -> WinToggle.entries.find { it.name == arr.getString(i) } }.toSet() }
            }, now.toggles),
            spokenPunctuation = take(KEY_SPOKEN_PUNCT, { it as? Boolean }, now.spokenPunctuation),
            voiceSounds = take(KEY_VOICE_SOUNDS, { it as? Boolean }, now.voiceSounds),
            voiceCommands = take(KEY_VOICE_COMMANDS, { it as? Boolean }, now.voiceCommands),
            continuousIdleSeconds = take(KEY_CONTINUOUS_IDLE, { (it as? Number)?.toInt()?.takeIf { v -> v in CONTINUOUS_IDLES } }, now.continuousIdleSeconds),
            learning = take(KEY_LEARNING, { it as? Boolean }, now.learning),
            voiceFinal = take(KEY_VOICE_FINAL, { v -> (v as? String)?.takeIf { id -> VoiceFinal.entries.any { it.id == id } } }, now.voiceFinal),
        ).let { base ->
            // Windows・Linux・macOS だけの項目（Android 版は持たないので、prefs ではなく "desktop" に入れる）
            val d = root.optJSONObject("desktop") ?: return@let base
            fun str(key: String) = if (d.has(key)) d.optString(key) else null
            base.copy(
                uiLanguage = str(KEY_UI_LANGUAGE)?.takeIf { it in UI_LANGUAGES }?.also { count++ } ?: base.uiLanguage,
                voiceFinalEn = str(KEY_VOICE_FINAL_EN)?.takeIf { id -> VoiceFinal.entries.any { it.id == id && it.english } }?.also { count++ } ?: base.voiceFinalEn,
                voiceHoldKeys = str(KEY_VOICE_HOLD)?.let(::keyList)?.also { count++ } ?: base.voiceHoldKeys,
                voiceToggleKey = str(KEY_VOICE_TOGGLE)?.let(::keyList)?.also { count++ } ?: base.voiceToggleKey,
            )
        }
        val files = root.optJSONObject("files") ?: JSONObject()
        var fileCount = 0
        for ((name, target) in listOf("user_dictionary.txt" to userDictionaryFile, "snippets.txt" to snippetsFile)) {
            val content = files.optString(name, null) ?: continue
            if (target.exists()) target.copyTo(File(target.path + ".bak"), overwrite = true)
            write(target, content)
            fileCount++
        }
        saveSettings(imported)
        return ImportResult(count, fileCount)
    }

    /**
     * Android 版の「設定の読み込み」で読める形（kanade-settings の JSON）で書き出す。項目名と型は Android 版と同じ。
     * Windows・Linux・macOS だけの項目（表示の言語・英語のモデル・録音キー）は "desktop" に入れ、ほかの PC 版で読み込める。
     * 学習・前回の日本語のオン/オフは入れない（Android 版の書き出しと同じく、その端末での記録のため）。
     * キーの割り当て（key_style）も入れない（Mac と Windows で既定が違い、機械ごとに選ぶもののため）
     */
    fun exportAndroid(json: File) {
        val s = loadSettings()
        fun entry(type: String, value: Any) = JSONObject().put("type", type).put("value", value)
        val prefs = JSONObject()
            .put(KEY_FULL_WIDTH, entry("boolean", s.fullWidthSymbols))
            .put(KEY_LIVE, entry("boolean", s.liveConversion))
            .put(KEY_PUNCTUATION, entry("string", s.punctuation))
            .put(KEY_PAIR_BRACKETS, entry("boolean", s.pairBrackets))
            .put(KEY_EMOJI, entry("boolean", s.emojiShortcodes))
            .put(KEY_EMOJI_PREFIX, entry("string", s.emojiPrefix))
            .put(KEY_EMOJI_TWICE, entry("boolean", s.emojiPrefixTwice))
            .put(KEY_ZENZ_MODE, entry("string", s.zenzMode))
            .put(KEY_START_MODE, entry("string", s.startMode))
            .put(KEY_MAC_SWITCH_KEYS, entry("string", s.macSwitchKeys))
            .put(KEY_REMOTE_APPS, entry("string", s.remoteApps))
            .put(KEY_TRIGGERS, entry("stringSet", org.json.JSONArray(s.toggles.map { it.name }.sorted())))
            .put(KEY_SPOKEN_PUNCT, entry("boolean", s.spokenPunctuation))
            .put(KEY_VOICE_SOUNDS, entry("boolean", s.voiceSounds))
            .put(KEY_VOICE_COMMANDS, entry("boolean", s.voiceCommands))
            .put(KEY_CONTINUOUS_IDLE, entry("int", s.continuousIdleSeconds))
            .put(KEY_LEARNING, entry("boolean", s.learning))
            .put(KEY_VOICE_FINAL, entry("string", s.voiceFinal))
        val files = JSONObject()
        if (userDictionaryFile.exists()) files.put("user_dictionary.txt", userDictionaryFile.readText())
        if (snippetsFile.exists()) files.put("snippets.txt", snippetsFile.readText())
        val desktop = JSONObject()
            .put(KEY_UI_LANGUAGE, s.uiLanguage)
            .put(KEY_VOICE_FINAL_EN, s.voiceFinalEn)
            .put(KEY_VOICE_HOLD, s.voiceHoldKeys)
            .put(KEY_VOICE_TOGGLE, s.voiceToggleKey)
        val root = JSONObject()
            .put("format", "kanade-settings")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            // Android 版はこれを見て、ファイルに無い項目を今のまま残す（Windows・Linux・macOS の書き出しは Android 版の項目の一部しか持たないため）
            .put("source", "desktop")
            .put("prefs", prefs)
            .put("desktop", desktop)
            .put("files", files)
        write(json, root.toString(2))
    }

    companion object {
        const val KEY_TRIGGERS = "toggle_triggers"
        const val KEY_KEY_STYLE = "key_style"
        const val KEY_FULL_WIDTH = "full_width_symbols"
        const val KEY_LIVE = "live_conversion"
        const val KEY_PUNCTUATION = "punctuation"
        const val KEY_PAIR_BRACKETS = "pair_brackets"
        const val KEY_EMOJI = "emoji_shortcodes"
        const val KEY_EMOJI_PREFIX = "emoji_prefix"
        const val KEY_EMOJI_TWICE = "emoji_prefix_twice"
        const val KEY_ZENZ_MODE = "zenz_mode"
        val ZENZ_MODES = setOf("CORRECT", "RERANK")
        const val KEY_START_MODE = "start_mode"
        val START_MODES = setOf("LAST", "JAPANESE", "ENGLISH")
        const val KEY_MAC_SWITCH_KEYS = "mac_switch_keys"
        const val KEY_REMOTE_APPS = "remote_apps"
        val MAC_SWITCH_KEYS = setOf("KANADE", "MACOS")

        /** 入り口の文字として使える1文字なら、その文字 */
        fun emojiPrefixOrNull(s: String): String? = s.takeIf { it.length == 1 && EngineSettings.isEmojiPrefix(it[0]) }
        const val KEY_SPOKEN_PUNCT = "spoken_punctuation"
        const val KEY_VOICE_SOUNDS = "voice_sounds"
        const val KEY_VOICE_COMMANDS = "voice_commands"
        const val KEY_CONTINUOUS_IDLE = "continuous_idle_seconds"
        /** 聞き続けるモードの、話さなければ終える秒数の選択肢（Android 版と同じ。0 は終えない） */
        val CONTINUOUS_IDLES = setOf(0, 30, 60, 120)
        const val KEY_LEARNING = "learning"
        const val KEY_LAST_MODE = "last_japanese"
        const val KEY_VOICE_FINAL = "voice_final"
        const val KEY_UI_LANGUAGE = "ui_language"
        const val KEY_VOICE_FINAL_EN = "voice_final_en"
        const val KEY_VOICE_HOLD = "voice_hold_keys"
        const val KEY_VOICE_TOGGLE = "voice_toggle_key"

        /** キーの名前の並びをそろえる（空白を除く。fcitx5 のキーの名前に使わない文字は捨てる） */
        fun keyList(value: String): String =
            value.split(',').map { k -> k.trim().filter { it.isLetterOrDigit() || it == '+' || it == '_' } }.filter { it.isNotEmpty() }.joinToString(",")
        val UI_LANGUAGES = listOf("auto", "ja", "en")

        /** Windows は %APPDATA%\Kanade、macOS は ~/Library/Application Support/Kanade、Linux は $XDG_CONFIG_HOME/kanade（ふつうは ~/.config/kanade） */
        fun defaultDir(): File {
            System.getenv("APPDATA")?.let { return File(it, "Kanade") }
            if (Os.isMac) return Os.macSupport()
            val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotEmpty() } ?: (System.getProperty("user.home") + "/.config")
            return File(config, "kanade")
        }
    }
}
