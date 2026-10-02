package dev.mooma.kanade

import android.content.Context
import java.io.File

/**
 * ユーザー辞書・スニペットの保存先（アプリ内のテキストファイル）。設定画面で編集し、IME は入力の始まりに読み直す。
 * 書式は両方とも1行に「キー 値」。
 */
class TextStore(context: Context, name: String, private val default: String) {
    private val file = File(context.filesDir, name)

    fun read(): String = if (file.exists()) file.readText() else default

    fun write(text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }

    /** 変わっていなければ読み直さないための目印 */
    fun version(): Long = if (file.exists()) file.lastModified() else 0L

    companion object {
        /** 初めて開いたときの中身（例として数件。説明は設定画面と docs/USAGE.md に書く） */
        fun userDictionary(context: Context) = TextStore(
            context, "user_dictionary.txt",
            "かなで Kanade\nでっくす DeX\n",
        )

        fun snippets(context: Context) = TextStore(
            context, "snippets.txt",
            "-> →\n<- ←\n=> ⇒\n",
        )
    }
}
