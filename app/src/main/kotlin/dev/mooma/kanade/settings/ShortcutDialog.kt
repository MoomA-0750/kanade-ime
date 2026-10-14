package dev.mooma.kanade.settings

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import dev.mooma.kanade.KeyShortcut
import dev.mooma.kanade.R

/**
 * 修飾キーつきのショートカットを選ぶ。物理キーボードで組み合わせを押すとそれにする（Esc は閉じるだけ）。
 * 押せないとき用に、よく使う組み合わせを一覧からも選べる。「使わない」で切れる
 */
@Composable
fun ShortcutDialog(
    title: String,
    current: KeyShortcut?,
    presets: List<KeyShortcut>,
    onPick: (KeyShortcut?) -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val none = stringResource(R.string.shortcut_none)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shortcut_dialog_title, title, current?.label() ?: none)) },
        text = {
            Column(
                Modifier
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                        val native = e.nativeKeyEvent
                        val bare = !native.isCtrlPressed && !native.isAltPressed && !native.isMetaPressed && !native.isShiftPressed
                        if (bare && (native.keyCode == KeyEvent.KEYCODE_ESCAPE || native.keyCode == KeyEvent.KEYCODE_BACK)) {
                            onDismiss()
                        } else {
                            // 修飾キーだけのうちは待つ
                            KeyShortcut.from(native)?.let(onPick)
                        }
                        true
                    }
                    .focusable()
                    .verticalScroll(rememberScrollState()),
            ) {
                Note(stringResource(R.string.shortcut_dialog_note))
                RadioRows(presets.map<KeyShortcut, Pair<KeyShortcut?, String>> { it to it.label() } + (null to none), current) { onPick(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_close)) } },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
