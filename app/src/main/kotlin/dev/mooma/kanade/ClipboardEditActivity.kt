package dev.mooma.kanade

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.mooma.kanade.settings.KanadeTheme

/**
 * クリップボードの履歴の文字を編集する画面。IME のパネルの中では文字を打てないので、ふつうの入力欄の画面として開き、
 * キーボード（Kanade）でそのまま直す。保存すると履歴の項目を書き換えて閉じ、元のアプリに戻る
 */
class ClipboardEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val history = ClipboardHistory(this)
        val item = id?.let { history.get(it) }
        setContent {
            KanadeTheme {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.ime_clipboard_edit_title), style = MaterialTheme.typography.titleLarge)
                        if (item?.text == null) {
                            Text(stringResource(R.string.ime_clipboard_edit_missing))
                            TextButton(onClick = { finish() }) { Text(stringResource(R.string.ime_clipboard_cancel)) }
                            return@Column
                        }
                        var value by remember { mutableStateOf(TextFieldValue(item.text, TextRange(item.text.length))) }
                        val focus = remember { FocusRequester() }
                        OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth().weight(1f).focusRequester(focus))
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                            TextButton(onClick = { finish() }) { Text(stringResource(R.string.ime_clipboard_cancel)) }
                            Button(
                                onClick = {
                                    history.editText(item.id, value.text)
                                    finish()
                                },
                                enabled = value.text.isNotBlank(),
                            ) { Text(stringResource(R.string.ime_clipboard_save)) }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "id"

        fun intent(context: Context, id: String): Intent = Intent(context, ClipboardEditActivity::class.java)
            .putExtra(EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
