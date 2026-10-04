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

    Section("入力中の文字を帯に出すアプリ", "入力中の文字をうまく出せないアプリ（Termux などの端末アプリ）では、入力中の文字を帯に出します。パッケージ名をカンマで区切って入れます。") {
        var saved by remember { mutableStateOf(settings.previewInBarPackages.sorted().joinToString(", ")) }
        var text by remember { mutableStateOf(saved) }
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = {
                    settings.previewInBarPackages = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    saved = text
                },
                enabled = text != saved,
            ) { Text("保存") }
        }
    }
}

/** 画面キーボード */
@Composable
fun ColumnScope.KeyboardScreen() {
    val settings = rememberSettings()

    Section("出すとき", "物理キーボードがつながっていないときに使う画面キーボードです（Unexpected Keyboard と同じ考え方です）。") {
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

    Section("配列", "キーボードの上で切り替えることもできます（QWERTY は Ctrl を上に払う、フリックは「あa1」を上に払う）。") {
        var layout by rememberPref({ settings.softKeyboardLayout }, { settings.softKeyboardLayout = it })
        ConnectedChoices(listOf("QWERTY" to "QWERTY", "FLICK" to "フリック"), layout, { layout = it })
        if (layout == "FLICK") {
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

    Section("フロート", "キーボードを画面の下から離して、好きな位置・幅に浮かせます。浮かせている間はアプリの画面が縮まず、キーボードの外を触ると下のアプリを操作できます。") {
        var floating by rememberPref({ settings.softKeyboardFloating }, { settings.softKeyboardFloating = it })
        SwitchRow("キーボードを浮かせる", floating, { floating = it })
        Note("キーボードの下のつまみの真ん中をドラッグすると動かせます。右端をドラッグすると幅が変わり、左端を押すと画面の下に戻ります。")
        Note("Ctrl を下に払う（フリックでは「あa1」を下に払う）と、キーボードからも浮かせる・戻すを切り替えられます。")
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
