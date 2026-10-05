package dev.mooma.kanade.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import dev.mooma.kanade.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mooma.kanade.TextStore
import dev.mooma.kanade.core.dict.UserDictionary

/** ユーザー辞書とスニペット。どちらも1行に「キー 値」のテキストファイル */
enum class PairKind(
    val keyLabel: String,
    val valueLabel: String,
    val description: String,
    val textHelp: String,
    val prefKey: String,
    val store: (Context) -> TextStore,
) {
    DICTIONARY(
        "よみ（ひらがな）", "語",
        "読みと語を登録します。登録した語は変換で優先され、候補の先頭に「ユーザー辞書」と付いて出ます（macOS のユーザ辞書と同じです）。文の途中でも、その読みの部分に使われやすくなります。",
        "1行に「よみ 語」と書きます（最初の空白で区切ります）。# で始まる行は無視します",
        "user_dictionary_text_mode", { TextStore.userDictionary(it) },
    ),
    SNIPPETS(
        "キー（打つ文字）", "出力",
        "キーを打ち終わった瞬間に、出力へ置き換えます（例: @@ → メールアドレス、-> → →）。日本語入力中も直接入力中も使えます。" +
            "キーは打つ文字そのものです（かなや全角になる前の文字で見ます）。英字だけのキーはローマ字にも反応するので、記号を含むキーがおすすめです。長いキーが優先されます。",
        "1行に「キー 出力」と書きます（最初の空白で区切ります）。出力の中の \\n は改行です。# で始まる行は無視します",
        "snippets_text_mode", { TextStore.snippets(it) },
    ),
}

/** ファイルの中の1件。[line] は何行目か */
private data class Entry(val line: Int, val key: String, val value: String)

private fun entries(text: String): List<Entry> = text.lines().mapIndexedNotNull { i, line ->
    UserDictionary.parsePairs(line).singleOrNull()?.let { (k, v) -> Entry(i, k, v) }
}

/**
 * 「一覧」（ダイアログで足す・直す・消す）と「テキスト」（まとめて書く・貼り付けで書き出す）を切り替えて編集する。
 * 一覧で直したときは、その行だけを書き換え、コメントなど他の行は残す
 */
@Composable
fun ColumnScope.PairEditorScreen(kind: PairKind) {
    val context = LocalContext.current
    val store = remember(kind) { kind.store(context) }
    val prefs = remember { context.getSharedPreferences("kanade_ui", Context.MODE_PRIVATE) }
    var version by remember { mutableIntStateOf(0) }
    val text = remember(kind, version) { store.read() }
    val list = remember(text) { entries(text) }
    var textMode by remember(kind) { mutableStateOf(prefs.getBoolean(kind.prefKey, false)) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var adding by remember { mutableStateOf(false) }

    fun write(entry: Entry?, line: String?) {
        val lines = store.read().lines().toMutableList()
        if (lines.lastOrNull() == "") lines.removeAt(lines.lastIndex)
        when {
            entry == null -> if (line != null) lines += line
            line == null -> lines.removeAt(entry.line)
            else -> lines[entry.line] = line
        }
        store.write(lines.joinToString("\n", postfix = "\n"))
        version++
    }

    Section(if (kind == PairKind.DICTIONARY) "ユーザー辞書" else "スニペット", kind.description) {
        ConnectedChoices(listOf(false to "一覧", true to "テキスト"), textMode, {
            textMode = it
            prefs.edit().putBoolean(kind.prefKey, it).apply()
        })
        Note("${list.size} 件")
    }

    if (!textMode) {
        Section("登録したもの") {
            // 列の見出し（登録したものと見分けられるよう、地の色を付けて小さく薄い字にする）
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                val header = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(kind.keyLabel, Modifier.width(128.dp), style = header)
                Text(kind.valueLabel, style = header)
            }
            for (e in list) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editing = e }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    // キーと値を2列に並べる（値に「→」のような記号があっても区切りと紛れないように）
                    Text(
                        e.key, Modifier.width(128.dp), style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        e.value.replace("\n", " ⏎ "), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                HorizontalDivider()
            }
            Button(onClick = { adding = true }) {
                Icon(painterResource(R.drawable.sym_add), contentDescription = null)
                Text("追加", Modifier.padding(start = 8.dp))
            }
        }
    } else {
        Section("テキストで編集", kind.textHelp + "。エディターでまとめて書いたり、ここをコピーして書き出したりできます。") {
            var draft by remember(text) { mutableStateOf(text) }
            OutlinedTextField(
                draft, { draft = it },
                Modifier.fillMaxWidth().heightIn(min = 200.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Button(onClick = {
                store.write(draft)
                version++
            }, enabled = draft != text) { Text("保存") }
        }
    }

    val target = editing
    if (adding || target != null) {
        EntryDialog(
            kind, target,
            onSave = { key, value ->
                // 改行は1行に収めるため \n と書いて保存する
                write(target, "$key ${value.replace("\n", "\\n")}")
                editing = null
                adding = false
            },
            onDelete = { target?.let { write(it, null) }; editing = null },
            onDismiss = { editing = null; adding = false },
        )
    }
}

@Composable
private fun EntryDialog(kind: PairKind, entry: Entry?, onSave: (String, String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf(entry?.key.orEmpty()) }
    var value by remember { mutableStateOf(entry?.value.orEmpty()) }
    val valid = key.isNotBlank() && key.none { it == ' ' || it == '\t' } && value.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry == null) "追加" else "編集") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(key, { key = it.trim() }, label = { Text(kind.keyLabel) }, singleLine = true)
                OutlinedTextField(value, { value = it }, label = { Text(kind.valueLabel) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key, value) }, enabled = valid) { Text("保存") } },
        dismissButton = {
            Row {
                if (entry != null) TextButton(onClick = onDelete) { Text("削除", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("キャンセル") }
            }
        },
    )
}
