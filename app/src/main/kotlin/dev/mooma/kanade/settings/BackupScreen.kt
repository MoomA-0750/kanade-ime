package dev.mooma.kanade.settings

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import dev.mooma.kanade.SettingsBackup

/** 終わったときの知らせ（読み込んだ後に画面を作り直しても残るよう、画面の外に置く） */
private object BackupUi {
    var message by mutableStateOf<String?>(null)
}

/** 設定の書き出し・読み込み */
@Composable
fun ColumnScope.BackupScreen() {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Uri?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        BackupUi.message = runCatching { SettingsBackup.export(context, uri) }
            .fold({ "書き出しました。" }, { "書き出せませんでした（${it.message}）。" })
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pending = uri
    }

    Section(
        "書き出す",
        "設定と、ユーザー辞書・スニペットを1つのファイルに書き出します。別の端末に移すときや、控えを取っておくときに使います。",
    ) {
        Note("学習した変換・クリップボードの履歴・キー記録・音声モデルは入りません。")
        Button(onClick = { exporter.launch(SettingsBackup.suggestedName()) }, modifier = Modifier.padding(top = 8.dp)) {
            Text("ファイルに書き出す")
        }
    }

    Section("読み込む", "書き出したファイルを読み込みます。今の設定・ユーザー辞書・スニペットは、ファイルの中身に置き換わります。") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                Text("ファイルから読み込む")
            }
        }
    }

    BackupUi.message?.let { Note(it, Modifier.padding(horizontal = 4.dp)) }

    pending?.let { uri ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("設定を読み込みますか") },
            text = { Text("今の設定・ユーザー辞書・スニペットは、ファイルの中身に置き換わります。元に戻すには、先に今の設定を書き出しておいてください。") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    runCatching { SettingsBackup.import(context, uri) }
                        .onSuccess {
                            BackupUi.message = "読み込みました（設定 ${it.prefs} 項目、ファイル ${it.files} 個）。"
                            // 画面に出している値を読み直す
                            (context as? Activity)?.recreate()
                        }
                        .onFailure { BackupUi.message = "読み込めませんでした（${it.message}）。" }
                }) { Text("読み込む") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("やめる") } },
        )
    }
}
