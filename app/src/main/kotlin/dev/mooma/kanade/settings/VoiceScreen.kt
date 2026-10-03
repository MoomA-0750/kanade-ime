package dev.mooma.kanade.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.mooma.kanade.CustomModelType
import dev.mooma.kanade.ModelDownloader
import dev.mooma.kanade.R
import dev.mooma.kanade.VoiceCatalog
import dev.mooma.kanade.VoiceModelSpec
import dev.mooma.kanade.VoiceTag

/** モデルの取得の進み具合（画面を移っても残るよう、画面の外に置く） */
object DownloadUi {
    /** 落としているモデルの id */
    var id by mutableStateOf<String?>(null)
    var done by mutableLongStateOf(0L)
    var total by mutableLongStateOf(0L)
    var message by mutableStateOf<String?>(null)
    /** 取得が終わるたびに増える（取得済みの表示を読み直す） */
    var finished by mutableIntStateOf(0)

    fun start(context: Context, model: VoiceModelSpec) {
        if (ModelDownloader.running) {
            ModelDownloader.cancel()
            return
        }
        val items = model.download?.invoke(context) ?: return
        val main = context.mainExecutor
        id = model.id
        total = items.sumOf { it.bytes }
        done = 0
        message = null
        ModelDownloader.start(
            items,
            progress = { i, got, _ -> main.execute { done = items.take(i).sumOf { it.bytes } + got } },
            done = { error ->
                main.execute {
                    message = "${model.name}: ${error ?: "ダウンロードしました"}"
                    id = null
                    finished++
                }
            },
        )
    }
}

/** Wi-Fi（または従量制でない回線）につながっているか */
private fun onUnmeteredNetwork(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

/** 音声入力 */
@Composable
fun ColumnScope.VoiceScreen() {
    val context = LocalContext.current
    val settings = rememberSettings()
    // 取得・取り込み・削除のたびに、一覧と取得済みの表示を読み直す
    var refresh by remember { mutableIntStateOf(0) }
    val tick = DownloadUi.finished + refresh

    Section(
        "録音キー",
        "録音キーを押している間だけ録音し、離すと端末の中で文字にして入れます（音声は外へ送りません）。" +
            "短く押すと聞き続けるモードになり、話し終わるたびに入れます（もう一度押すか、1分話さないと終わります）。",
    ) {
        var keyDialog by remember { mutableStateOf(false) }
        var voiceKey by rememberPref({ settings.voiceKey }, { settings.voiceKey = it })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("録音キー", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            FilledTonalButton(onClick = { keyDialog = true }) { Text(keyName(voiceKey)) }
        }
        Note("画面キーボードでは マイクのキーを使います。")
        if (keyDialog) VoiceKeyDialog(voiceKey, onPick = { voiceKey = it; keyDialog = false }, onDismiss = { keyDialog = false })
    }

    ModelsSection(tick, onChanged = { refresh++ })

    Section("話し方") {
        var streaming by rememberPref({ settings.voiceStreaming }, { settings.voiceStreaming = it })
        SwitchRow("話しながら文字を出す", streaming, { streaming = it }, "ReazonSpeech で、話している途中の文字を出します。")
        var spoken by rememberPref({ settings.spokenPunctuation }, { settings.spokenPunctuation = it })
        SwitchRow(
            "話して句読点を入れる", spoken, { spoken = it },
            "くてん→。 とうてん→、 かいぎょう→改行 はてなマーク→？ びっくりマーク→！ かぎかっこ／かぎかっことじ→「」 かっこ／かっことじ→（） なかぐろ→・",
        )
        Text("認識に使うスレッド数", style = MaterialTheme.typography.labelLarge)
        var threads by rememberPref({ settings.voiceThreads }, { settings.voiceThreads = it })
        ConnectedChoices(listOf(2, 4, 6, 8).map { it to "$it" }, threads, { threads = it })
    }

    Section("見本の音声で測る", "8.8 秒の日本語（音声合成）を、ダウンロード済みのモデルすべてにかけて、時間と結果を見ます。") {
        SampleBench(settings.voiceThreads, tick)
    }
}

/** モデルの一覧。各モデルを「適用」すると、確定の文をそのモデルで作る */
@Composable
private fun ModelsSection(tick: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val settings = rememberSettings()
    var applied by rememberPref({ settings.voiceFinal }, { settings.voiceFinal = it })
    val models = remember(tick) { VoiceCatalog.all(context) }
    val ready = remember(tick) { models.associate { it.id to it.isReady(context) } }
    var confirmDownload by remember { mutableStateOf<VoiceModelSpec?>(null) }
    var confirmDelete by remember { mutableStateOf<VoiceModelSpec?>(null) }
    var picked by remember { mutableStateOf<List<Uri>?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) picked = uris }

    fun download(model: VoiceModelSpec) {
        if (DownloadUi.id == null && !onUnmeteredNetwork(context)) confirmDownload = model else DownloadUi.start(context, model)
    }

    val inUse = remember(tick, applied) { VoiceCatalog.finalModel(context, settings) }
    Section(
        "モデル",
        "入力欄に入る文字を作るモデルを「適用」で選びます。話している途中の文字は、いつも ReazonSpeech で出します。",
    ) {
        if (inUse != null && inUse.id != applied) {
            Note("適用したモデルがダウンロードされていないので、${inUse.name} を使っています。")
        }
        if (inUse == null) Note("モデルがまだありません。どれかをダウンロードしてください。")
        DownloadUi.message?.let { Note(it) }
    }
    for (model in models) {
        ModelCard(
            model,
            ready = ready[model.id] == true,
            applied = model.id == inUse?.id,
            onApply = { applied = model.id },
            onDownload = { download(model) },
            onDelete = { confirmDelete = model },
        )
    }
    OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
        Icon(painterResource(R.drawable.sym_upload_file), contentDescription = null, modifier = Modifier.size(20.dp))
        Text("リストにないモデルを取り込む", Modifier.padding(start = 8.dp))
    }

    confirmDownload?.let { model ->
        val mb = Math.round((model.download?.invoke(context)?.sumOf { it.bytes } ?: 0L) / 1e6)
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text("Wi-Fi につながっていません") },
            text = { Text("モバイルデータで ${model.name}（約 ${mb}MB）をダウンロードしますか？") },
            confirmButton = {
                TextButton(onClick = {
                    DownloadUi.start(context, model)
                    confirmDownload = null
                }) { Text("ダウンロードする") }
            },
            dismissButton = { TextButton(onClick = { confirmDownload = null }) { Text("やめる") } },
        )
    }
    confirmDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("${model.name} を削除しますか？") },
            text = { Text(if (model.custom) "取り込んだファイルを消し、一覧から外します。" else "ダウンロードしたファイルを消します。もう一度ダウンロードすれば使えます。") },
            confirmButton = {
                TextButton(onClick = {
                    model.delete(context)
                    confirmDelete = null
                    onChanged()
                }) { Text("削除する", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("やめる") } },
        )
    }
    picked?.let { uris ->
        ImportDialog(uris, onDone = { id ->
            picked = null
            if (id != null) {
                applied = id
                onChanged()
            }
        })
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModelCard(model: VoiceModelSpec, ready: Boolean, applied: Boolean, onApply: () -> Unit, onDownload: () -> Unit, onDelete: () -> Unit) {
    val downloading = DownloadUi.id == model.id
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (applied && ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(model.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (applied && ready) {
                    Icon(painterResource(R.drawable.sym_check_circle), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text("使用中", Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tag in model.tags.sortedBy { it.kind.ordinal }) Tag(tag)
            }
            model.note?.let { Note(it) }
            if (downloading) {
                Text("${DownloadUi.done / 1_000_000}MB / ${DownloadUi.total / 1_000_000}MB", style = MaterialTheme.typography.bodyMedium)
                LinearWavyProgressIndicator(
                    progress = { if (DownloadUi.total > 0) DownloadUi.done.toFloat() / DownloadUi.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    downloading -> OutlinedButton(onClick = onDownload) { Text("やめる") }
                    !ready -> Button(onClick = onDownload, enabled = DownloadUi.id == null) {
                        Icon(painterResource(R.drawable.sym_download), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("ダウンロード", Modifier.padding(start = 6.dp))
                    }
                    !applied -> Button(onClick = onApply) { Text("適用") }
                }
                if (ready && !downloading) {
                    TextButton(onClick = onDelete) { Text("削除") }
                }
            }
        }
    }
}

/** モデルの札。種類ごとに色を分けて、並べたときに比べやすくする */
@Composable
private fun Tag(tag: VoiceTag) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (tag.kind) {
        VoiceTag.Kind.LANGUAGE -> scheme.secondaryContainer to scheme.onSecondaryContainer
        VoiceTag.Kind.FORMAT -> scheme.surfaceContainerHighest to scheme.onSurface
        VoiceTag.Kind.ACCURACY -> scheme.primaryContainer to scheme.onPrimaryContainer
        VoiceTag.Kind.SPEED -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        VoiceTag.Kind.PUNCTUATION -> scheme.surfaceContainerHighest to scheme.onSurface
        VoiceTag.Kind.ROLE -> scheme.inversePrimary to scheme.onSurface
        VoiceTag.Kind.SIZE -> Color.Transparent to scheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = bg,
        border = if (tag.kind == VoiceTag.Kind.SIZE) androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant) else null,
    ) {
        Text(tag.text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** リストにないモデルを取り込む。選んだファイルの名前から形式を見当つけ、名前と形式を確かめてもらう */
@Composable
private fun ImportDialog(uris: List<Uri>, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val names = remember(uris) { uris.mapNotNull { VoiceCatalog.displayName(context, it) } }
    // 名前は、tokens.txt 以外の（モデルの）ファイルの名前から
    var name by remember {
        mutableStateOf(names.firstOrNull { !it.lowercase().startsWith("tokens") }?.substringBeforeLast('.') ?: "取り込んだモデル")
    }
    var type by remember { mutableStateOf(VoiceCatalog.guessType(names)) }
    var copying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val missing = VoiceCatalog.missing(type, names)
    AlertDialog(
        onDismissRequest = { if (!copying) onDone(null) },
        title = { Text("モデルを取り込む") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Note("選んだファイル: ${names.joinToString("、")}")
                OutlinedTextField(name, { name = it }, label = { Text("名前") }, singleLine = true)
                Text("形式", style = MaterialTheme.typography.labelLarge)
                RadioRows(CustomModelType.entries.map { it to "${it.label}\n${it.help}" }, type, { type = it })
                if (missing.isNotEmpty()) Note("足りないファイル: ${missing.joinToString("、")}（ファイル名に encoder・decoder・joiner・tokens などが入っているもので見分けます）")
                error?.let { Note(it) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = missing.isEmpty() && name.isNotBlank() && !copying,
                onClick = {
                    copying = true
                    val main = context.mainExecutor
                    Thread {
                        val result = runCatching { VoiceCatalog.importCustom(context, uris, name.trim(), type) }
                        main.execute {
                            copying = false
                            result.onSuccess { onDone(it) }.onFailure { error = "取り込めませんでした: ${it.message}" }
                        }
                    }.start()
                },
            ) { Text(if (copying) "取り込んでいます…" else "取り込む") }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }, enabled = !copying) { Text("やめる") } },
    )
}

/** ダウンロード済みのモデルを順に見本の音声にかける */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SampleBench(threads: Int, tick: Int) {
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(listOf<String>()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {
            val models = VoiceCatalog.all(context).filter { it.isReady(context) }
            if (models.isEmpty()) {
                lines = listOf("ダウンロード済みのモデルがありません。")
                return@Button
            }
            running = true
            lines = listOf("$threads スレッド（初回は読み込みの時間を含みます）")
            val main = context.mainExecutor
            val pcm = readSampleWav(context)
            fun run(i: Int) {
                if (i >= models.size) {
                    running = false
                    return
                }
                val m = models[i]
                val started = System.nanoTime()
                m.recognize(context, pcm, threads) { text ->
                    val ms = (System.nanoTime() - started) / 1_000_000
                    main.execute {
                        lines = lines + "${m.name}: ${ms}ms\n${text ?: "（認識できませんでした）"}"
                        run(i + 1)
                    }
                }
            }
            run(0)
        }, enabled = !running) { Text("測る") }
        if (running) LoadingIndicator(Modifier.size(40.dp))
    }
    for (line in lines) Text(line, style = MaterialTheme.typography.bodyMedium)
}

/** 同梱の見本の音声（16kHz・モノラル・16bit の wav）を -1〜1 の float にする */
private fun readSampleWav(context: Context): FloatArray {
    val bytes = context.assets.open("voice-sample-ja.wav").readBytes()
    val n = (bytes.size - 44) / 2
    return FloatArray(n) { i ->
        val lo = bytes[44 + 2 * i].toInt() and 0xFF
        val hi = bytes[45 + 2 * i].toInt()
        ((hi shl 8) or lo).toShort() / 32768f
    }
}

fun keyName(keyCode: Int): String = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")

/**
 * 録音キーを選ぶ。物理キーボードのキーを押すとそれにする。一覧からも選べる（画面キーボードだけのときや、押せないキー用）
 */
@Composable
private fun VoiceKeyDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val choices = listOf(
        KeyEvent.KEYCODE_F12, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3,
        KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F13, KeyEvent.KEYCODE_SCROLL_LOCK,
        KeyEvent.KEYCODE_BREAK, KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_CTRL_RIGHT,
    )
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("録音キー（今: ${keyName(current)}）") },
        text = {
            Column(
                Modifier
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                        val code = e.key.nativeKeyCode
                        if (code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BACK) onDismiss() else onPick(code)
                        true
                    }
                    .focusable()
                    .verticalScroll(rememberScrollState()),
            ) {
                Note("物理キーボードのキーを押すと、そのキーにします。一覧からも選べます。")
                RadioRows(choices.map { it to keyName(it) }, current, onPick)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
