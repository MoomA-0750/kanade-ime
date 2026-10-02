package dev.mooma.kanade

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 設定とキー記録の確認画面。段階1では見た目を作り込まず、動作確認に要るものだけを置く。
 */
class SettingsActivity : Activity() {
    private lateinit var settings: KanadeSettings
    private lateinit var keyLog: KeyLog
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = KanadeSettings(this)
        keyLog = KeyLog(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(heading("有効化"))
        root.addView(Button(this).apply {
            text = "入力方法の設定を開く"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "入力方法を選ぶ"
            setOnClickListener { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        })

        root.addView(heading("日本語オン/オフのキー（US 配列用）"))
        root.addView(note("JIS 配列の変換（オン）・無変換（オフ）・かな（オン）・半角/全角（切り替え）は常に有効"))
        for (trigger in ToggleTrigger.entries) {
            root.addView(CheckBox(this).apply {
                text = trigger.label
                isChecked = trigger in settings.toggleTriggers
                setOnCheckedChangeListener { _, checked ->
                    settings.toggleTriggers =
                        if (checked) settings.toggleTriggers + trigger else settings.toggleTriggers - trigger
                }
            })
        }

        root.addView(heading("入力"))
        root.addView(CheckBox(this).apply {
            text = "数字・記号を全角で入力する"
            isChecked = settings.fullWidthSymbols
            setOnCheckedChangeListener { _, checked -> settings.fullWidthSymbols = checked }
        })

        root.addView(heading("試し打ち"))
        root.addView(EditText(this).apply { hint = "ここで入力を試す" })

        root.addView(heading("変換の試験（段階2）"))
        root.addView(note("ひらがなの読みを入れて変換する。まだ入力中の変換には使っていない"))
        val readingInput = EditText(this).apply { hint = "例: きょうはいいてんきですね" }
        root.addView(readingInput)
        val convertResult = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        val convertButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        convertButtons.addView(Button(this).apply {
            text = "変換"
            setOnClickListener {
                val reading = readingInput.text.toString().trim()
                if (reading.isEmpty()) return@setOnClickListener
                convertResult.text = "変換中…"
                background { ConversionBench.tryConvert(this@SettingsActivity, reading) }.let { task ->
                    task { convertResult.text = it }
                }
            }
        })
        convertButtons.addView(Button(this).apply {
            text = "評価セットで測る"
            setOnClickListener {
                convertResult.text = "準備中…"
                background {
                    ConversionBench.runEval(this@SettingsActivity) { msg -> runOnUiThread { convertResult.text = msg } }
                }.let { task -> task { convertResult.text = it } }
            }
        })
        root.addView(convertButtons)
        root.addView(convertResult)

        root.addView(heading("キー記録"))
        root.addView(note("IME に届いたキーを記録する。DeX が先に取るキーはここに出ない"))
        root.addView(CheckBox(this).apply {
            text = "記録する"
            isChecked = settings.keyLogEnabled
            setOnCheckedChangeListener { _, checked -> settings.keyLogEnabled = checked }
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "再読み込み"
            setOnClickListener { refreshLog() }
        })
        buttons.addView(Button(this).apply {
            text = "消去"
            setOnClickListener {
                keyLog.clear()
                refreshLog()
            }
        })
        root.addView(buttons)
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    /** 重い処理を別スレッドで動かし、結果を UI スレッドで受け取る */
    private fun background(work: () -> String): ((String) -> Unit) -> Unit = { onDone ->
        Thread {
            val result = runCatching(work).getOrElse { "失敗: $it" }
            runOnUiThread { onDone(result) }
        }.start()
    }

    private fun refreshLog() {
        logView.text = keyLog.read().lines().takeLast(200).joinToString("\n")
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
    }
}
