package dev.mooma.kanade.settings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mooma.kanade.R

/** 端末のアプリ（ホーム画面に出るもの）の名前・パッケージ名・アイコン */
private data class AppInfo(val label: String, val packageName: String, val icon: ImageBitmap?)

private fun Drawable.toImageBitmap(sizePx: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, sizePx, sizePx)
    draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

private fun appInfo(context: Context, packageName: String): AppInfo {
    val pm = context.packageManager
    return runCatching {
        val info = pm.getApplicationInfo(packageName, 0)
        AppInfo(pm.getApplicationLabel(info).toString(), packageName, pm.getApplicationIcon(info).toImageBitmap(96))
    }.getOrElse { AppInfo(packageName, packageName, null) }
}

/** ホーム画面に出るアプリの一覧（名前の順） */
private fun launchableApps(context: Context): List<AppInfo> {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != context.packageName }
        .map { appInfo(context, it) }
        .sortedBy { it.label.lowercase() }
}

/**
 * 入力中の文字を帯に出すアプリ（端末アプリなど）。ユーザー辞書と同じく一覧で見せ、追加は端末のアプリの一覧から選ぶ（パッケージ名を直接入れることもできる）
 */
@Composable
fun ColumnScope.PreviewAppsSection() {
    val context = LocalContext.current
    val settings = rememberSettings()
    var packages by remember { mutableStateOf(settings.previewInBarPackages.toList()) }
    val apps = remember(packages) { packages.map { appInfo(context, it) }.sortedBy { it.label.lowercase() } }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<AppInfo?>(null) }

    fun save(next: List<String>) {
        packages = next.distinct()
        settings.previewInBarPackages = packages.toSet()
    }

    Section("入力中の文字を帯に出すアプリ", "入力中の文字をうまく出せないアプリ（Termux などの端末アプリ）では、入力中の文字を帯に出します。") {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text("アプリ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        if (apps.isEmpty()) Note("まだありません。")
        for (app in apps) {
            AppRow(app, Modifier.clickable { removing = app })
            HorizontalDivider()
        }
        Button(onClick = { adding = true }) {
            Icon(painterResource(R.drawable.sym_add), contentDescription = null)
            Text("追加", Modifier.padding(start = 8.dp))
        }
    }

    if (adding) {
        AddAppDialog(
            exclude = packages.toSet(),
            onAdd = {
                save(packages + it)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
    removing?.let { app ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(app.label) },
            text = { Text("このアプリでは、入力中の文字を帯に出さなくなります。") },
            confirmButton = {
                TextButton(onClick = {
                    save(packages - app.packageName)
                    removing = null
                }) { Text("外す", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("キャンセル") } },
        )
    }
}

@Composable
private fun AppRow(app: AppInfo, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp)) {
            app.icon?.let { Image(it, contentDescription = null, Modifier.size(36.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 端末のアプリの一覧から選ぶ（名前・パッケージ名で絞れる。一覧に無いものはパッケージ名をそのまま追加できる） */
@Composable
private fun AddAppDialog(exclude: Set<String>, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val all = remember { launchableApps(context) }
    var query by remember { mutableStateOf("") }
    val shown = all.filter { it.packageName !in exclude }.filter {
        query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
    }
    // 打ったものがパッケージ名の形なら、一覧に無くてもそのまま追加できる
    val typedPackage = query.trim().takeIf { Regex("[A-Za-z][\\w]*(\\.[A-Za-z_][\\w]*)+").matches(it) && it !in exclude }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("アプリを追加") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("名前かパッケージ名で探す") })
                LazyColumn(Modifier.height(360.dp)) {
                    if (typedPackage != null && shown.none { it.packageName == typedPackage }) {
                        item {
                            Text(
                                "「$typedPackage」を追加",
                                Modifier.fillMaxWidth().clickable { onAdd(typedPackage) }.padding(12.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    items(shown, key = { it.packageName }) { app ->
                        AppRow(app, Modifier.clickable { onAdd(app.packageName) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}
