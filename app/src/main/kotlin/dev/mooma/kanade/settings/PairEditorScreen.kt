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
import androidx.compose.ui.res.stringResource
import dev.mooma.kanade.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mooma.kanade.TextStore
import dev.mooma.kanade.core.dict.UserDictionary

/** ユーザー辞書・スニペット・話して入れる記号。どれも1行に「キー 値」のテキストファイル */
enum class PairKind(
    val titleRes: Int,
    val keyLabelRes: Int,
    val valueLabelRes: Int,
    val descriptionRes: Int,
    val textHelpRes: Int,
    val prefKey: String,
    val store: (Context) -> TextStore,
    /** 「既定に戻す」で書き戻す中身（null ならボタンを出さない） */
    val defaultText: String? = null,
) {
    DICTIONARY(
        R.string.input_dictionary_title,
        R.string.input_dictionary_key_label, R.string.input_dictionary_value_label,
        R.string.input_dictionary_description,
        R.string.input_dictionary_text_help,
        "user_dictionary_text_mode", { TextStore.userDictionary(it) },
    ),
    SNIPPETS(
        R.string.input_snippets_title,
        R.string.input_snippets_key_label, R.string.input_snippets_value_label,
        R.string.input_snippets_description,
        R.string.input_snippets_text_help,
        "snippets_text_mode", { TextStore.snippets(it) },
    ),
    SPOKEN_SYMBOLS(
        R.string.voice_symbols_title,
        R.string.voice_symbols_key_label, R.string.voice_symbols_value_label,
        R.string.voice_symbols_description,
        R.string.voice_symbols_text_help,
        "spoken_symbols_text_mode", { TextStore.spokenSymbols(it) },
        dev.mooma.kanade.core.voice.SpokenPunctuation.DEFAULT_TEXT,
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

    Section(stringResource(kind.titleRes), stringResource(kind.descriptionRes)) {
        ConnectedChoices(listOf(false to stringResource(R.string.input_mode_list), true to stringResource(R.string.input_mode_text)), textMode, {
            textMode = it
            prefs.edit().putBoolean(kind.prefKey, it).apply()
        })
        Note(stringResource(R.string.input_entry_count, list.size))
        kind.defaultText?.let { default ->
            var confirm by remember { mutableStateOf(false) }
            androidx.compose.material3.OutlinedButton(onClick = { confirm = true }) { Text(stringResource(R.string.input_reset_to_default)) }
            if (confirm) {
                AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text(stringResource(R.string.input_reset_to_default)) },
                    text = { Text(stringResource(R.string.input_reset_to_default_message)) },
                    confirmButton = {
                        TextButton(onClick = {
                            store.write(default)
                            version++
                            confirm = false
                        }) { Text(stringResource(R.string.input_reset)) }
                    },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.input_cancel)) } },
                )
            }
        }
    }

    if (!textMode) {
        Section(stringResource(R.string.input_registered_title)) {
            // 列の見出し（登録したものと見分けられるよう、地の色を付けて小さく薄い字にする）
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                val header = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(stringResource(kind.keyLabelRes), Modifier.width(128.dp), style = header)
                Text(stringResource(kind.valueLabelRes), style = header)
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
                Text(stringResource(R.string.input_add), Modifier.padding(start = 8.dp))
            }
        }
    } else {
        Section(
            stringResource(R.string.input_edit_as_text_title),
            stringResource(R.string.input_edit_as_text_description, stringResource(kind.textHelpRes)),
        ) {
            var draft by remember(text) { mutableStateOf(text) }
            OutlinedTextField(
                draft, { draft = it },
                Modifier.fillMaxWidth().heightIn(min = 200.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Button(onClick = {
                store.write(draft)
                version++
            }, enabled = draft != text) { Text(stringResource(R.string.input_save)) }
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
        title = { Text(stringResource(if (entry == null) R.string.input_add else R.string.input_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(key, { key = it.trim() }, label = { Text(stringResource(kind.keyLabelRes)) }, singleLine = true)
                OutlinedTextField(value, { value = it }, label = { Text(stringResource(kind.valueLabelRes)) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(key, value) }, enabled = valid) { Text(stringResource(R.string.input_save)) } },
        dismissButton = {
            Row {
                if (entry != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.input_delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.input_cancel)) }
            }
        },
    )
}
