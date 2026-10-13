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
    val toggles: Set<WinToggle> = WinToggle.entries.toSet(),
    val spokenPunctuation: Boolean = true,
    val learning: Boolean = true,
    val lastJapanese: Boolean = false,
) {
    fun engineSettings() = EngineSettings(
        fullWidthSymbols = fullWidthSymbols, liveConversion = liveConversion, punctuation = punctuation, pairBrackets = pairBrackets,
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
            toggles = p.getProperty(KEY_TRIGGERS)?.split(',')?.mapNotNull { name -> WinToggle.entries.find { it.name == name.trim() } }?.toSet()
                ?: d.toggles,
            spokenPunctuation = bool(KEY_SPOKEN_PUNCT, d.spokenPunctuation),
            learning = bool(KEY_LEARNING, d.learning),
            lastJapanese = bool(KEY_LAST_MODE, d.lastJapanese),
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
            appendLine("# 日本語のオン/オフに使うキー（RIGHT_ALT_TAP・CTRL_SPACE・F13 をカンマ区切りで。変換/無変換・半角/全角はいつでも使える）")
            appendLine("$KEY_TRIGGERS=${s.toggles.joinToString(",") { it.name }}")
            appendLine("# 音声入力で「まる」「てん」などを句読点にする")
            appendLine("$KEY_SPOKEN_PUNCT=${s.spokenPunctuation}")
            appendLine("# 選んだ変換を覚えて次から先に出す")
            appendLine("$KEY_LEARNING=${s.learning}")
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
        val root = runCatching { JSONObject(json.readText()) }.getOrElse { error("Kanade の設定のファイルではありません") }
        if (root.optString("format") != "kanade-settings") error("Kanade の設定のファイルではありません")
        if (root.optInt("version") > 1) error("新しい Kanade で書き出したファイルです")
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
            toggles = take(KEY_TRIGGERS, { v ->
                (v as? org.json.JSONArray)?.let { arr -> (0 until arr.length()).mapNotNull { i -> WinToggle.entries.find { it.name == arr.getString(i) } }.toSet() }
            }, now.toggles),
            spokenPunctuation = take(KEY_SPOKEN_PUNCT, { it as? Boolean }, now.spokenPunctuation),
            learning = take(KEY_LEARNING, { it as? Boolean }, now.learning),
        )
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
     * 学習・前回の日本語のオン/オフは入れない（Android 版の書き出しと同じく、その端末での記録のため）
     */
    fun exportAndroid(json: File) {
        val s = loadSettings()
        fun entry(type: String, value: Any) = JSONObject().put("type", type).put("value", value)
        val prefs = JSONObject()
            .put(KEY_FULL_WIDTH, entry("boolean", s.fullWidthSymbols))
            .put(KEY_LIVE, entry("boolean", s.liveConversion))
            .put(KEY_PUNCTUATION, entry("string", s.punctuation))
            .put(KEY_PAIR_BRACKETS, entry("boolean", s.pairBrackets))
            .put(KEY_TRIGGERS, entry("stringSet", org.json.JSONArray(s.toggles.map { it.name }.sorted())))
            .put(KEY_SPOKEN_PUNCT, entry("boolean", s.spokenPunctuation))
            .put(KEY_LEARNING, entry("boolean", s.learning))
        val files = JSONObject()
        if (userDictionaryFile.exists()) files.put("user_dictionary.txt", userDictionaryFile.readText())
        if (snippetsFile.exists()) files.put("snippets.txt", snippetsFile.readText())
        val root = JSONObject()
            .put("format", "kanade-settings")
            .put("version", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("prefs", prefs)
            .put("files", files)
        write(json, root.toString(2))
    }

    companion object {
        const val KEY_TRIGGERS = "toggle_triggers"
        const val KEY_FULL_WIDTH = "full_width_symbols"
        const val KEY_LIVE = "live_conversion"
        const val KEY_PUNCTUATION = "punctuation"
        const val KEY_PAIR_BRACKETS = "pair_brackets"
        const val KEY_SPOKEN_PUNCT = "spoken_punctuation"
        const val KEY_LEARNING = "learning"
        const val KEY_LAST_MODE = "last_japanese"

        /** Windows は %APPDATA%\Kanade、ほかは ~/.config/kanade-desktop */
        fun defaultDir(): File {
            System.getenv("APPDATA")?.let { return File(it, "Kanade") }
            return File(System.getProperty("user.home"), ".config/kanade-desktop")
        }
    }
}
