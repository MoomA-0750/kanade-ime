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
import androidx.compose.ui.res.stringResource
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
            context, items,
            progress = { i, got, _ -> main.execute { done = items.take(i).sumOf { it.bytes } + got } },
            done = { error ->
                main.execute {
                    message = context.getString(R.string.voice_download_result, model.name, error ?: context.getString(R.string.voice_download_done))
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
        stringResource(R.string.voice_key_section_title),
        stringResource(R.string.voice_key_section_description),
    ) {
        var keyDialog by remember { mutableStateOf(false) }
        var voiceKey by rememberPref({ settings.voiceKey }, { settings.voiceKey = it })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.voice_key_label), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            FilledTonalButton(onClick = { keyDialog = true }) { Text(keyName(voiceKey)) }
        }
        Note(stringResource(R.string.voice_key_note_soft_keyboard))
        if (keyDialog) VoiceKeyDialog(voiceKey, onPick = { voiceKey = it; keyDialog = false }, onDismiss = { keyDialog = false })
    }

    ModelsSection(tick, onChanged = { refresh++ })

    Section(stringResource(R.string.voice_speaking_section_title)) {
        var streaming by rememberPref({ settings.voiceStreaming }, { settings.voiceStreaming = it })
        SwitchRow(
            stringResource(R.string.voice_streaming_title), streaming, { streaming = it },
            stringResource(R.string.voice_streaming_summary),
        )
        var spoken by rememberPref({ settings.spokenPunctuation }, { settings.spokenPunctuation = it })
        SwitchRow(
            stringResource(R.string.voice_spoken_punctuation_title), spoken, { spoken = it },
            stringResource(R.string.voice_spoken_punctuation_summary),
        )
        var autoPunct by rememberPref({ settings.voiceAutoPunctuation }, { settings.voiceAutoPunctuation = it })
        SwitchRow(
            stringResource(R.string.voice_auto_punctuation_title), autoPunct, { autoPunct = it },
            stringResource(R.string.voice_auto_punctuation_summary),
        )
        var fillers by rememberPref({ settings.voiceFillers }, { settings.voiceFillers = it })
        SwitchRow(stringResource(R.string.voice_fillers_title), fillers, { fillers = it }, stringResource(R.string.voice_fillers_summary))
        var commands by rememberPref({ settings.voiceCommands }, { settings.voiceCommands = it })
        SwitchRow(stringResource(R.string.voice_commands_title), commands, { commands = it }, stringResource(R.string.voice_commands_summary))
        var userDict by rememberPref({ settings.voiceUserDictionary }, { settings.voiceUserDictionary = it })
        SwitchRow(
            stringResource(R.string.voice_user_dictionary_title), userDict, { userDict = it },
            stringResource(R.string.voice_user_dictionary_summary),
        )
        Text(stringResource(R.string.voice_continuous_idle_label), style = MaterialTheme.typography.labelLarge)
        var idle by rememberPref({ settings.continuousIdleSeconds }, { settings.continuousIdleSeconds = it })
        ConnectedChoices(listOf(
            30 to stringResource(R.string.voice_idle_30s), 60 to stringResource(R.string.voice_idle_1m),
            120 to stringResource(R.string.voice_idle_2m), 0 to stringResource(R.string.voice_idle_never),
        ), idle, { idle = it })
        Text(stringResource(R.string.voice_threads_label), style = MaterialTheme.typography.labelLarge)
        var threads by rememberPref({ settings.voiceThreads }, { settings.voiceThreads = it })
        ConnectedChoices(listOf(2, 4, 6, 8).map { it to "$it" }, threads, { threads = it })
    }

    Section(stringResource(R.string.voice_bench_section_title), stringResource(R.string.voice_bench_section_description)) {
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
        stringResource(R.string.voice_models_section_title),
        stringResource(R.string.voice_models_section_description),
    ) {
        if (inUse != null && inUse.id != applied) {
            Note(stringResource(R.string.voice_models_fallback_note, inUse.name))
        }
        if (inUse == null) Note(stringResource(R.string.voice_models_none_note))
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
        Text(stringResource(R.string.voice_import_button), Modifier.padding(start = 8.dp))
    }

    confirmDownload?.let { model ->
        val mb = Math.round((model.download?.invoke(context)?.sumOf { it.bytes } ?: 0L) / 1e6)
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            title = { Text(stringResource(R.string.voice_metered_dialog_title)) },
            text = { Text(stringResource(R.string.voice_metered_dialog_text, model.name, mb)) },
            confirmButton = {
                TextButton(onClick = {
                    DownloadUi.start(context, model)
                    confirmDownload = null
                }) { Text(stringResource(R.string.voice_metered_dialog_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDownload = null }) { Text(stringResource(R.string.voice_cancel)) } },
        )
    }
    confirmDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.voice_delete_dialog_title, model.name)) },
            text = { Text(stringResource(if (model.custom) R.string.voice_delete_dialog_text_custom else R.string.voice_delete_dialog_text_downloaded)) },
            confirmButton = {
                TextButton(onClick = {
                    model.delete(context)
                    confirmDelete = null
                    onChanged()
                }) { Text(stringResource(R.string.voice_delete_dialog_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.voice_cancel)) } },
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
                    Text(stringResource(R.string.voice_model_in_use), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tag in model.tags.sortedBy { it.kind.ordinal }) Tag(tag)
            }
            model.noteRes?.let { Note(stringResource(it)) }
            if (downloading) {
                Text("${DownloadUi.done / 1_000_000}MB / ${DownloadUi.total / 1_000_000}MB", style = MaterialTheme.typography.bodyMedium)
                LinearWavyProgressIndicator(
                    progress = { if (DownloadUi.total > 0) DownloadUi.done.toFloat() / DownloadUi.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    downloading -> OutlinedButton(onClick = onDownload) { Text(stringResource(R.string.voice_cancel)) }
                    !ready -> Button(onClick = onDownload, enabled = DownloadUi.id == null) {
                        Icon(painterResource(R.drawable.sym_download), contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.voice_download_button), Modifier.padding(start = 6.dp))
                    }
                    !applied -> Button(onClick = onApply) { Text(stringResource(R.string.voice_apply_button)) }
                }
                if (ready && !downloading) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.voice_delete_button)) }
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
        Text(tag.textRes?.let { stringResource(it) } ?: tag.literal.orEmpty(), Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/** リストにないモデルを取り込む。選んだファイルの名前から形式を見当つけ、名前と形式を確かめてもらう */
@Composable
private fun ImportDialog(uris: List<Uri>, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val names = remember(uris) { uris.mapNotNull { VoiceCatalog.displayName(context, it) } }
    // 名前は、tokens.txt 以外の（モデルの）ファイルの名前から
    val defaultName = stringResource(R.string.voice_import_default_name)
    var name by remember {
        mutableStateOf(names.firstOrNull { !it.lowercase().startsWith("tokens") }?.substringBeforeLast('.') ?: defaultName)
    }
    var type by remember { mutableStateOf(VoiceCatalog.guessType(names)) }
    var copying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val missing = VoiceCatalog.missing(type, names)
    val separator = stringResource(R.string.voice_list_separator)
    AlertDialog(
        onDismissRequest = { if (!copying) onDone(null) },
        title = { Text(stringResource(R.string.voice_import_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Note(stringResource(R.string.voice_import_selected_files, names.joinToString(separator)))
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.voice_import_name_label)) }, singleLine = true)
                Text(stringResource(R.string.voice_import_format_label), style = MaterialTheme.typography.labelLarge)
                RadioRows(CustomModelType.entries.map { it to stringResource(it.labelRes) + "\n" + stringResource(it.helpRes) }, type, { type = it })
                if (missing.isNotEmpty()) Note(stringResource(R.string.voice_import_missing_files, missing.joinToString(separator)))
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
                            result.onSuccess { onDone(it) }.onFailure { error = context.getString(R.string.voice_import_failed, "${it.message}") }
                        }
                    }.start()
                },
            ) { Text(stringResource(if (copying) R.string.voice_import_copying else R.string.voice_import_confirm)) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }, enabled = !copying) { Text(stringResource(R.string.voice_cancel)) } },
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
                lines = listOf(context.getString(R.string.voice_bench_no_models))
                return@Button
            }
            running = true
            lines = listOf(context.getString(R.string.voice_bench_threads, threads))
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
                        lines = lines + context.getString(R.string.voice_bench_result, m.name, ms, text ?: context.getString(R.string.voice_bench_not_recognized))
                        run(i + 1)
                    }
                }
            }
            run(0)
        }, enabled = !running) { Text(stringResource(R.string.voice_bench_button)) }
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
        title = { Text(stringResource(R.string.voice_key_dialog_title, keyName(current))) },
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
                Note(stringResource(R.string.voice_key_dialog_note))
                RadioRows(choices.map { it to keyName(it) }, current, onPick)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_close)) } },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
