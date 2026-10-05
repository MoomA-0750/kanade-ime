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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mooma.kanade.R
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
            .fold({ context.getString(R.string.app_backup_exported) }, { context.getString(R.string.app_backup_export_failed, it.message) })
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pending = uri
    }

    Section(
        stringResource(R.string.app_backup_export_title),
        stringResource(R.string.app_backup_export_description),
    ) {
        Note(stringResource(R.string.app_backup_export_note))
        Button(onClick = { exporter.launch(SettingsBackup.suggestedName()) }, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.app_backup_export_button))
        }
    }

    Section(stringResource(R.string.app_backup_import_title), stringResource(R.string.app_backup_import_description)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                Text(stringResource(R.string.app_backup_import_button))
            }
        }
    }

    BackupUi.message?.let { Note(it, Modifier.padding(horizontal = 4.dp)) }

    pending?.let { uri ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.app_backup_import_confirm_title)) },
            text = { Text(stringResource(R.string.app_backup_import_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    runCatching { SettingsBackup.import(context, uri) }
                        .onSuccess {
                            BackupUi.message = context.getString(R.string.app_backup_imported, it.prefs, it.files)
                            // 画面に出している値を読み直す
                            (context as? Activity)?.recreate()
                        }
                        .onFailure { BackupUi.message = context.getString(R.string.app_backup_import_failed, it.message) }
                }) { Text(stringResource(R.string.app_backup_import_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.app_backup_cancel)) } },
        )
    }
}
