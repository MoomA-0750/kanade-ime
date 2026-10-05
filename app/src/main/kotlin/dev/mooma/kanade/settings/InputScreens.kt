package dev.mooma.kanade.settings

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.mooma.kanade.LAYOUTS
import dev.mooma.kanade.KanadeSettings
import dev.mooma.kanade.ToggleTrigger
import java.io.File

@Composable
fun rememberSettings(): KanadeSettings {
    val context = LocalContext.current
    return remember { KanadeSettings(context) }
}

/** 入力と変換 */
@Composable
fun ColumnScope.InputScreen() {
    val context = LocalContext.current
    val settings = rememberSettings()

    Section("日本語オン/オフのキー", "US 配列で使うキーを選びます。JIS 配列の変換（オン）・無変換（オフ）・かな（オン）・半角/全角（切り替え）は、いつでも使えます。") {
        var triggers by rememberPref({ settings.toggleTriggers }, { settings.toggleTriggers = it })
        for (t in ToggleTrigger.entries) {
            SwitchRow(t.label, t in triggers, { on -> triggers = if (on) triggers + t else triggers - t })
        }
        OutlinedButton(onClick = { context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }) {
            Text("入力方法を切り替える")
        }
    }

    Section("文字") {
        var fullWidth by rememberPref({ settings.fullWidthSymbols }, { settings.fullWidthSymbols = it })
        SwitchRow("数字・記号を全角で入力する", fullWidth, { fullWidth = it }, "オフにすると半角で入力します（変換の候補には両方出ます）。")
        var pairBrackets by rememberPref({ settings.pairBrackets }, { settings.pairBrackets = it })
        SwitchRow(
            "括弧を対で入れる", pairBrackets, { pairBrackets = it },
            "「（ などを打つと閉じ括弧も入れ、カーソルを中に置きます。空の括弧で Space を押すと、「」『』【】などに替わります。",
        )
        Text("句読点（, と . で入るもの）")
        var punctuation by rememberPref({ settings.punctuation }, { settings.punctuation = it })
        ConnectedChoices(listOf("、。", "，．", "、．", "，。").map { it to it }, punctuation, { punctuation = it })
    }

    Section("変換") {
        var live by rememberPref({ settings.liveConversion }, { settings.liveConversion = it })
        SwitchRow("ライブ変換", live, { live = it }, "打つたびに変換した結果を出します。")
        var learning by rememberPref({ settings.learningEnabled }, { settings.learningEnabled = it })
        SwitchRow("学習する", learning, { learning = it }, "前に選んだ候補を、次から上に出します。")
        var confirm by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { confirm = true }) { Text("学習を消す") }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("学習を消す") },
                text = { Text("覚えた候補をすべて消します（ユーザー辞書は消えません）。") },
                confirmButton = {
                    TextButton(onClick = {
                        File(context.filesDir, "learning.tsv").delete()
                        settings.learningResetAt = System.currentTimeMillis()
                        confirm = false
                    }) { Text("消す") }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("キャンセル") } },
            )
        }
    }

    PreviewAppsSection()
}

/** 画面キーボード */
@Composable
fun ColumnScope.KeyboardScreen() {
    val settings = rememberSettings()

    Section("出すとき", "物理キーボードがつながっていないときに使う画面キーボードです。") {
        var mode by rememberPref({ settings.softKeyboard }, { settings.softKeyboard = it })
        ConnectedChoices(listOf("AUTO" to "自動", "ALWAYS" to "いつも", "NEVER" to "出さない"), mode, { mode = it })
        Note(
            when (mode) {
                "AUTO" -> "物理キーボードがないときだけ出します。"
                "ALWAYS" -> "物理キーボードがあっても出します。"
                else -> "出しません（物理キーボード専用）。"
            },
        )
    }

    Section("配列", "キーボードの左下のキー（グライドは ?123、フルキーは Ctrl、テンキーは「あa1」）を上に払うと、使う配列の中で次の配列に切り替わります。") {
        val names = mapOf("GLIDE" to "グライド", "QWERTY" to "フルキー", "FLICK" to "テンキー")
        var enabled by rememberPref({ settings.enabledLayouts }, { settings.enabledLayouts = it })
        var layout by rememberPref({ settings.softKeyboardLayout }, { settings.softKeyboardLayout = it })
        Note("使う配列（左下のキーで切り替えるときは、この中だけを回ります）")
        val descriptions = mapOf(
            "GLIDE" to "QWERTY の文字のキーの上を指でなぞると、語を入れます（日本語はローマ字でなぞると、かなになります）。数字・記号は ?123 のページにあります。",
            "QWERTY" to "キーを払うと数字・記号が出ます。Ctrl・Alt・Fn・Esc・Tab もあり、物理キーボードと同じように使えます。",
            "FLICK" to "12 のキーをフリック（トグル入力も選べます）で打ちます。",
        )
        for (id in LAYOUTS) {
            SwitchRow(names.getValue(id), id in enabled, { on ->
                // 最後の1つは外せない
                val next = if (on) enabled + id else enabled - id
                if (next.isNotEmpty()) {
                    enabled = next
                    if (layout !in next) layout = LAYOUTS.first { it in next }
                }
            }, descriptions.getValue(id))
        }
        if ("GLIDE" in enabled) {
            var glideZenz by rememberPref({ settings.glideZenz }, { settings.glideZenz = it })
            SwitchRow(
                "グライドの候補を zenz で並べ直す", glideZenz, { glideZenz = it },
                "日本語のグライドで、前の文とのつながりを zenz（変換の並べ替えに使う言語モデル）に見てもらいます。辞書の1位を先に入れ、zenz が別の語を選んだら少し遅れて入れ替えます。",
            )
        }
        if ("FLICK" in enabled) {
            var toggle by rememberPref({ settings.flickToggle }, { settings.flickToggle = it })
            SwitchRow("トグル入力", toggle, { toggle = it }, "同じキーを続けて押すと、次の文字になります（あ→い→う）。オフにするとフリックだけで入力します。")
        }
    }

    Section("クリップボード", "あ/A のキーを下に払う（フリックでは ☺ を左に払う）と、コピーした文字や画像の履歴が出ます。押すと貼り付け、長押しでピン留め・切り抜き・削除ができます。パスワードなど機密の印が付いたコピーは残しません。") {
        var on by rememberPref({ settings.clipboardHistory }, { settings.clipboardHistory = it })
        SwitchRow("コピーの履歴を残す", on, { on = it })
    }

    Section("大きさ") {
        var height by rememberPref({ settings.softKeyboardHeight }, { settings.softKeyboardHeight = it })
        SliderRow("高さ", height, 60..300, 10, { height = it }) { "$it%" }
        var bottom by rememberPref({ settings.softKeyboardBottom }, { settings.softKeyboardBottom = it })
        SliderRow("下の余白", bottom, 0..64, 4, { bottom = it }) { "${it}dp" }
        Note("ナビゲーションバーに重なるときは、下の余白を増やしてください。次にキーボードを出したときから変わります。")
    }

    Section("操作") {
        for (line in listOf(
            "キーを隅や上下左右へ払うと、小さく書いてある文字（数字・記号）が出ます。",
            "Shift・Ctrl・Alt・Fn は次の1打だけ効き、2回続けて押すと固定します。押したまま別のキーを押す同時押しもできます。",
            "修飾キーの押す・離すも送るので、Shift＋矢印の選択やアプリのショートカットが物理キーボードと同じように使えます。",
            "Alt を上に払うと Meta（⌘）です。Fn＋数字で F1〜F10、Fn＋- = で F11・F12 を入力します。",
            "Space を左右に払うと矢印です。Tab のキーは上下左右に払うと矢印、四隅に払うと Home・End・PgUp・PgDn です。Backspace を右に払うと Delete です。",
            "マイクのキーは、押している間だけ録音します。短く押すと聞き続けます。",
        )) Note("・$line")
    }
}
