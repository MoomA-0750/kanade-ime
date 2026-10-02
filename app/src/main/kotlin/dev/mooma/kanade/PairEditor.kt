package dev.mooma.kanade

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * ユーザー辞書・スニペットの編集欄。「一覧」（ダイアログで足す・直す・消す）と「テキスト」（まとめて書く・貼り付けで書き出す）を切り替える。
 * どちらも同じファイル（1行に「キー 値」）を読み書きする。一覧で直したときは、その行だけを書き換え、コメントなど他の行は残す。
 */
class PairEditor(
    context: Context,
    private val store: TextStore,
    private val keyLabel: String,
    private val valueLabel: String,
    private val prefs: android.content.SharedPreferences,
    private val prefKey: String,
) : LinearLayout(context) {
    private val density = context.resources.displayMetrics.density
    private val listBox = LinearLayout(context).apply { orientation = VERTICAL }
    private val textBox = LinearLayout(context).apply { orientation = VERTICAL }
    private val textEdit = EditText(context).apply {
        typeface = Typeface.MONOSPACE
        textSize = 14f
        minLines = 5
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    private val status = TextView(context).apply { textSize = 13f }
    private val modeButton = Button(context)
    private var textMode = prefs.getBoolean(prefKey, false)

    init {
        orientation = VERTICAL
        modeButton.setOnClickListener {
            if (textMode) store.write(textEdit.text.toString())
            textMode = !textMode
            prefs.edit().putBoolean(prefKey, textMode).apply()
            refresh()
        }
        addView(modeButton)
        addView(listBox)
        textBox.addView(textEdit)
        textBox.addView(Button(context).apply {
            text = "保存"
            setOnClickListener {
                store.write(textEdit.text.toString())
                refresh()
                status.append("（保存した。次の入力から使われる）")
            }
        })
        addView(textBox)
        addView(status)
        refresh()
    }

    private fun refresh() {
        val text = store.read()
        val entries = entries(text)
        status.text = "${entries.size} 件"
        modeButton.text = if (textMode) "一覧で編集する" else "テキストで編集する（まとめて登録・書き出し）"
        textBox.visibility = if (textMode) View.VISIBLE else View.GONE
        listBox.visibility = if (textMode) View.GONE else View.VISIBLE
        if (textMode) {
            textEdit.setText(text)
            return
        }
        listBox.removeAllViews()
        val pad = (8 * density).toInt()
        for (e in entries) {
            // キーと値を2列に並べる（値に「→」のような記号があっても区切りと紛れないように）
            listBox.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                setPadding(pad, pad, pad, pad)
                setOnClickListener { edit(e) }
                addView(TextView(context).apply {
                    this.text = e.key
                    textSize = 16f
                    typeface = Typeface.MONOSPACE
                    isSingleLine = true
                }, LayoutParams((140 * density).toInt(), LayoutParams.WRAP_CONTENT))
                addView(TextView(context).apply {
                    this.text = e.value.replace("\n", " ⏎ ")
                    textSize = 16f
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        listBox.addView(Button(context).apply {
            this.text = "追加"
            setOnClickListener { edit(null) }
        })
    }

    /** ファイルの中の1件。[line] は何行目か */
    private data class Entry(val line: Int, val key: String, val value: String)

    private fun entries(text: String): List<Entry> = text.lines().mapIndexedNotNull { i, line ->
        dev.mooma.kanade.core.dict.UserDictionary.parsePairs(line).singleOrNull()?.let { (k, v) -> Entry(i, k, v) }
    }

    /** 足す（[entry] が null）・直す・消すダイアログ */
    private fun edit(entry: Entry?) {
        val pad = (16 * density).toInt()
        val keyEdit = EditText(context).apply {
            hint = keyLabel
            isSingleLine = true
            setText(entry?.key.orEmpty())
        }
        val valueEdit = EditText(context).apply {
            hint = valueLabel
            setText(entry?.value.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply { text = keyLabel })
            addView(keyEdit)
            addView(TextView(context).apply { text = valueLabel })
            addView(valueEdit)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (entry == null) "追加" else "編集")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val key = keyEdit.text.toString().trim()
                val value = valueEdit.text.toString()
                if (key.isNotEmpty() && !key.any { it == ' ' || it == '\t' } && value.isNotBlank()) {
                    // 改行は1行に収めるため \n と書いて保存する
                    write(entry, "$key ${value.replace("\n", "\\n")}")
                }
            }
            .setNegativeButton("キャンセル", null)
        if (entry != null) dialog.setNeutralButton("削除") { _, _ -> write(entry, null) }
        dialog.show()
    }

    /** [entry] の行を [line] に置き換える（null なら消す）。[entry] が null なら末尾に足す */
    private fun write(entry: Entry?, line: String?) {
        val lines = store.read().lines().toMutableList()
        if (lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)
        when {
            entry == null -> if (line != null) lines += line
            line == null -> lines.removeAt(entry.line)
            else -> lines[entry.line] = line
        }
        store.write(lines.joinToString("\n", postfix = "\n"))
        refresh()
    }
}
