package dev.mooma.kanade

import android.content.Context
import android.view.KeyEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * IME に届いたキーを記録する。DeX が先に取って IME まで来ないキーを洗い出すためのもの。
 * 設定画面から読み、adb なしでも確かめられるようにアプリ内のファイルに置く。
 */
class KeyLog(context: Context) {
    private val file = File(context.filesDir, "keylog.txt")
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun record(event: KeyEvent, note: String) {
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> "down"
            KeyEvent.ACTION_UP -> "up"
            else -> "multi"
        }
        val line = buildString {
            append(time.format(Date()))
            append(' ').append(action)
            append(' ').append(KeyEvent.keyCodeToString(event.keyCode))
            append(" scan=").append(event.scanCode)
            append(" meta=0x").append(Integer.toHexString(event.metaState))
            if (event.repeatCount > 0) append(" repeat=").append(event.repeatCount)
            append(" dev=").append(event.device?.name ?: event.deviceId.toString())
            append(" -> ").append(note)
        }
        synchronized(this) {
            file.appendText(line + "\n")
            if (file.length() > MAX_BYTES) {
                val lines = file.readLines()
                file.writeText(lines.takeLast(lines.size / 2).joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** キー以外の出来事（帯が出たかなど、端末ごとの挙動を調べるため） */
    fun note(text: String) = synchronized(this) {
        file.appendText("${time.format(Date())} note $text\n")
    }

    fun read(): String = synchronized(this) { if (file.exists()) file.readText() else "" }

    fun clear() = synchronized(this) { file.delete() }

    private companion object {
        const val MAX_BYTES = 256 * 1024
    }
}
