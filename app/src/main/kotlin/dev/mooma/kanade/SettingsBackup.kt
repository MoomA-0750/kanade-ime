package dev.mooma.kanade

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 設定の書き出し・読み込み。設定（SharedPreferences の「kanade」）と、ユーザー辞書・スニペットを1つの JSON にまとめる。
 * 学習した変換・クリップボードの履歴・キー記録・音声モデルは入れない（大きい・その端末での記録のため）。
 * 読み込むと、今の設定・辞書・スニペットはファイルの中身に置き換わる
 */
object SettingsBackup {
    private const val FORMAT = "kanade-settings"
    private const val VERSION = 1
    private const val PREFS = "kanade"

    /** まとめる文字のファイル（ユーザー辞書・スニペット） */
    private val FILES = listOf("user_dictionary.txt", "snippets.txt")

    /** 端末ごとの印で、書き出さず・読み込みでも今の値を残すもの（学習を消した時刻。変わると IME が学習を消すため） */
    private val LOCAL_KEYS = setOf("learning_reset_at")

    fun suggestedName(): String {
        val date = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.ROOT).format(java.util.Date())
        return "kanade-settings-$date.json"
    }

    fun export(context: Context, uri: Uri) {
        val prefs = JSONObject()
        for ((key, value) in context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all) {
            if (key in LOCAL_KEYS) continue
            val entry = when (value) {
                is Boolean -> JSONObject().put("type", "boolean").put("value", value)
                is Int -> JSONObject().put("type", "int").put("value", value)
                is Long -> JSONObject().put("type", "long").put("value", value)
                is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
                is String -> JSONObject().put("type", "string").put("value", value)
                is Set<*> -> JSONObject().put("type", "stringSet").put("value", JSONArray(value.map { it.toString() }.sorted()))
                else -> continue
            }
            prefs.put(key, entry)
        }
        val files = JSONObject()
        for (name in FILES) {
            val f = java.io.File(context.filesDir, name)
            if (f.exists()) files.put(name, f.readText())
        }
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("prefs", prefs)
            .put("files", files)
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error(context.getString(R.string.app_backup_error_open_file))
        out.use { it.write(root.toString(2).toByteArray()) }
    }

    /** 読み込んだ設定の数（設定の項目数, ファイルの数） */
    data class Result(val prefs: Int, val files: Int)

    fun import(context: Context, uri: Uri): Result {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: error(context.getString(R.string.app_backup_error_open_file))
        val root = runCatching { JSONObject(text) }.getOrElse { error(context.getString(R.string.app_backup_error_not_kanade)) }
        if (root.optString("format") != FORMAT) error(context.getString(R.string.app_backup_error_not_kanade))
        if (root.optInt("version") > VERSION) error(context.getString(R.string.app_backup_error_newer_version))

        // 先にすべて読んでから書く（途中で壊れたファイルだったときに、半分だけ置き換わらないように）
        val prefs = root.optJSONObject("prefs") ?: JSONObject()
        val shared = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keep = shared.all.filterKeys { it in LOCAL_KEYS }
        val editor = shared.edit().clear()
        for ((key, value) in keep) if (value is Long) editor.putLong(key, value)
        var count = 0
        for (key in prefs.keys()) {
            if (key in LOCAL_KEYS) continue
            val e = prefs.getJSONObject(key)
            when (e.getString("type")) {
                "boolean" -> editor.putBoolean(key, e.getBoolean("value"))
                "int" -> editor.putInt(key, e.getInt("value"))
                "long" -> editor.putLong(key, e.getLong("value"))
                "float" -> editor.putFloat(key, e.getDouble("value").toFloat())
                "string" -> editor.putString(key, e.getString("value"))
                "stringSet" -> {
                    val arr = e.getJSONArray("value")
                    editor.putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet())
                }
                else -> continue
            }
            count++
        }
        val files = root.optJSONObject("files") ?: JSONObject()
        val contents = FILES.mapNotNull { name -> files.optString(name, null)?.let { name to it } }

        editor.commit()
        for ((name, content) in contents) {
            val f = java.io.File(context.filesDir, name)
            val tmp = java.io.File(f.path + ".tmp")
            tmp.writeText(content)
            tmp.renameTo(f)
        }
        return Result(count, contents.size)
    }
}
