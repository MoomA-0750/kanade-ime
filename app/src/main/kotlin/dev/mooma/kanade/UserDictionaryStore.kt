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
        fun userDictionary(context: Context) = TextStore(
            context, "user_dictionary.txt",
            """
            # ユーザー辞書。1行に「よみ 語」（よみはひらがな、最初の空白で区切る）。# で始まる行は無視する
            # 登録した語は変換で優先され、候補の先頭に出る
            # かなで かなで
            """.trimIndent() + "\n",
        )

        fun snippets(context: Context) = TextStore(
            context, "snippets.txt",
            """
            # スニペット。1行に「キー 出力」（最初の空白で区切る）。# で始まる行は無視する
            # キーは打つ文字そのもの（日本語入力中でも、かなや全角になる前の文字で見る）。打ち終わった瞬間に置き換わる
            # 出力の中の \n は改行
            # @@ you@example.com
            -> →
            <- ←
            => ⇒
            """.trimIndent() + "\n",
        )
    }
}
