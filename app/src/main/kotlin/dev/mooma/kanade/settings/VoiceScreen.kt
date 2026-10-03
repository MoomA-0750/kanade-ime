package dev.mooma.kanade.settings

import android.content.Context
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.mooma.kanade.ModelDownloader
import dev.mooma.kanade.SherpaRecognizer
import dev.mooma.kanade.VoiceInput
import dev.mooma.kanade.VoiceModel

/**
 * 音声認識のモデル1つ（whisper の各モデル・sherpa-onnx の各モデル）を、画面から同じ形で扱うためのもの
 */
class ModelEntry(
    val key: String,
    val name: String,
    val size: String,
    val description: String,
    val ready: (Context) -> Boolean,
    val items: (Context) -> List<ModelDownloader.Item>,
    /** ファイルから取り込む（複数のファイルからなるものは、まとめて選ぶ） */
    val multipleFiles: Boolean,
    val import: (Context, List<Uri>) -> Boolean,
)

private fun whisperEntry(m: VoiceModel, size: String, description: String) = ModelEntry(
    "WHISPER_${m.name}", m.label.substringBefore('（'), size, description,
    ready = { m.isReady(it) },
    items = { listOf(ModelDownloader.Item(m.url, m.path(it), m.bytes)) },
    multipleFiles = false,
    import = { c, uris -> uris.firstOrNull()?.let { m.importFrom(c, it) {} } == true },
)

private fun sherpaEntry(r: SherpaRecognizer, size: String, description: String) = ModelEntry(
    r.label, r.label, size, description,
    ready = { r.isReady(it) },
    items = { r.downloadItems(it) },
    multipleFiles = true,
    import = { c, uris -> r.importFrom(c, uris) > 0 },
)

val MODEL_ENTRIES = listOf(
    sherpaEntry(SherpaRecognizer.parakeet, "656MB", "NVIDIA の日本語専用。精度が高く（公開の評価で whisper small の半分以下の誤り）、句読点が付く"),
    sherpaEntry(SherpaRecognizer.reazon, "73MB", "日本語向けで速い。話している途中の文字を出すのに使う。句読点は付かない"),
    sherpaEntry(SherpaRecognizer.senseVoice, "239MB", "中・英・日・韓。速く、句読点が付く"),
    whisperEntry(VoiceModel.SMALL, "190MB", "多言語。句読点が付く。日本語の精度はいまひとつ"),
    whisperEntry(VoiceModel.KOTOBA, "538MB", "日本語向けの whisper。精度が高いが重い"),
    whisperEntry(VoiceModel.TURBO, "574MB", "多言語の whisper。精度が高いが重い"),
)

/** モデルの取得の進み具合（画面を移っても残るよう、画面の外に置く） */
object DownloadUi {
    var name by mutableStateOf<String?>(null)
    var done by mutableLongStateOf(0L)
    var total by mutableLongStateOf(0L)
    var message by mutableStateOf<String?>(null)
    /** 取得が終わるたびに増える（取得済みの表示を読み直す） */
    var finished by mutableIntStateOf(0)

    fun start(context: Context, entry: ModelEntry) {
        if (ModelDownloader.running) {
            ModelDownloader.cancel()
            return
        }
        val items = entry.items(context)
        val main = context.mainExecutor
        name = entry.name
        total = items.sumOf { it.bytes }
        done = 0
        message = null
        ModelDownloader.start(
            items,
            progress = { i, got, _ -> main.execute { done = items.take(i).sumOf { it.bytes } + got } },
            done = { error ->
                main.execute {
                    message = "${entry.name}: ${error ?: "落とし終えた"}"
                    name = null
                    finished++
                }
            },
        )
    }
}

/** 音声入力 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ColumnScope.VoiceScreen() {
    val context = LocalContext.current
    val settings = rememberSettings()
    // 取得・取り込みが終わったら、取得済みの表示を読み直す
    var refresh by remember { mutableIntStateOf(0) }
    val tick = DownloadUi.finished + refresh

    Section(
        "使い方",
        "録音キーを押している間だけ録音し、離すと端末の中で文字にして入れる（音声は外へ送らない）。" +
            "短く押すと聞き続けるモードになり、話し終わるたびに入れる（もう一度押すか、1分話さなければ終わる）",
    ) {
        var keyDialog by remember { mutableStateOf(false) }
        var voiceKey by rememberPref({ settings.voiceKey }, { settings.voiceKey = it })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("録音キー", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            FilledTonalButton(onClick = { keyDialog = true }) { Text(keyName(voiceKey)) }
        }
        Note("画面キーボードでは 🎤 のキー")
        if (keyDialog) VoiceKeyDialog(voiceKey, onPick = { voiceKey = it; keyDialog = false }, onDismiss = { keyDialog = false })
    }

    Section("確定の文を作る認識", "入力欄に入る文字を作る。話している途中の文字は、いつも ReazonSpeech で出す") {
        var final by rememberPref({ settings.voiceFinal }, { settings.voiceFinal = it })
        val whisperModel = settings.voiceModel
        val options = listOf(
            "PARAKEET" to "parakeet-ja（日本語向け・高精度・句読点あり）",
            "REAZON" to "ReazonSpeech（速い。句読点は話して入れる）",
            "SENSEVOICE" to "SenseVoice（速い・句読点あり）",
            "WHISPER" to "whisper（下で選んだモデル・句読点あり）",
        )
        val ready = remember(tick) {
            mapOf(
                "PARAKEET" to SherpaRecognizer.parakeet.isReady(context),
                "REAZON" to SherpaRecognizer.reazon.isReady(context),
                "SENSEVOICE" to SherpaRecognizer.senseVoice.isReady(context),
                "WHISPER" to whisperModel.isReady(context),
            )
        }
        RadioRows(options.map { (k, label) -> k to (label + if (ready[k] == true) "" else "・未取得") }, final, { final = it })
        var whisper by rememberPref({ settings.voiceModel }, { settings.voiceModel = it })
        if (final == "WHISPER") {
            Text("whisper のモデル", style = MaterialTheme.typography.labelLarge)
            ConnectedChoices(listOf(VoiceModel.SMALL to "small", VoiceModel.KOTOBA to "kotoba", VoiceModel.TURBO to "turbo"), whisper, { whisper = it })
        }
        if (ready[final] != true) Note("選んだものを落としていないので、落としてあるほかの認識を使う")
    }

    Section("モデル", "Wi-Fi で落とす。落としている間は設定画面を開いたままにする（止まっても次は続きから）") {
        DownloadProgress()
        MODEL_ENTRIES.forEachIndexed { i, entry ->
            if (i > 0) HorizontalDivider()
            ModelRow(entry, tick, onImported = { refresh++ })
        }
    }

    Section("話し方") {
        var streaming by rememberPref({ settings.voiceStreaming }, { settings.voiceStreaming = it })
        SwitchRow("話しながら文字を出す", streaming, { streaming = it }, "ReazonSpeech で途中の文字を出す")
        var spoken by rememberPref({ settings.spokenPunctuation }, { settings.spokenPunctuation = it })
        SwitchRow(
            "話して句読点を入れる", spoken, { spoken = it },
            "くてん→。 とうてん→、 かいぎょう→改行 はてなマーク→？ びっくりマーク→！ かぎかっこ／かぎかっことじ→「」 かっこ／かっことじ→（） なかぐろ→・",
        )
        Text("認識に使うスレッド数", style = MaterialTheme.typography.labelLarge)
        var threads by rememberPref({ settings.voiceThreads }, { settings.voiceThreads = it })
        ConnectedChoices(listOf(2, 4, 6, 8).map { it to "$it" }, threads, { threads = it })
    }

    Section("見本の音声で測る", "8.8 秒の日本語（音声合成）を、落としてあるモデルすべてにかけて、時間と結果を見る") {
        SampleBench(settings.voiceThreads)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DownloadProgress() {
    val name = DownloadUi.name
    if (name != null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$name を落としている… ${DownloadUi.done / 1_000_000}MB / ${DownloadUi.total / 1_000_000}MB", style = MaterialTheme.typography.bodyMedium)
            LinearWavyProgressIndicator(
                progress = { if (DownloadUi.total > 0) DownloadUi.done.toFloat() / DownloadUi.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    DownloadUi.message?.let { Note(it) }
}

@Composable
private fun ModelRow(entry: ModelEntry, tick: Int, onImported: () -> Unit) {
    val context = LocalContext.current
    val ready = remember(tick) { entry.ready(context) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    val onResult = { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            importMessage = "写している…"
            val main = context.mainExecutor
            Thread {
                val ok = runCatching { entry.import(context, uris) }.getOrDefault(false)
                main.execute {
                    importMessage = if (ok) "取り込んだ" else "取り込めなかった（名前と大きさが合うファイルか確かめる）"
                    onImported()
                }
            }.start()
        }
    }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> onResult(listOfNotNull(uri)) }
    val multiple = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> onResult(uris) }
    val downloading = DownloadUi.name == entry.name

    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${entry.name}（${entry.size}）", style = MaterialTheme.typography.titleSmall)
                Note(entry.description)
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (ready) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Text(
                    if (ready) "取得済み" else "未取得",
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ready) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!ready || downloading) {
                Button(onClick = { DownloadUi.start(context, entry) }, enabled = downloading || DownloadUi.name == null) {
                    Text(if (downloading) "やめる" else "ダウンロード")
                }
            }
            if (!ready) {
                OutlinedButton(onClick = { if (entry.multipleFiles) multiple.launch(arrayOf("*/*")) else single.launch(arrayOf("*/*")) }) {
                    Text("ファイルから取り込む")
                }
            }
        }
        importMessage?.let { Note(it) }
    }
}

/** 落としてあるモデルを順に見本の音声にかける。sherpa のモデルは2回かけて、2回目（読み込み済み）の速さを出す */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SampleBench(threads: Int) {
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(listOf<String>()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {
            running = true
            lines = listOf("$threads スレッド")
            val main = context.mainExecutor
            val pcm = readSampleWav(context)
            val steps = ArrayList<(() -> Unit) -> Unit>()
            for (r in listOf(SherpaRecognizer.reazon, SherpaRecognizer.senseVoice, SherpaRecognizer.parakeet)) {
                if (!r.isReady(context)) continue
                steps += { next ->
                    val started = System.nanoTime()
                    r.recognize(context, pcm, threads) { first ->
                        val firstMs = (System.nanoTime() - started) / 1_000_000
                        val again = System.nanoTime()
                        r.recognize(context, pcm, threads) { _ ->
                            val ms = (System.nanoTime() - again) / 1_000_000
                            main.execute {
                                lines = lines + "${r.label}: ${ms}ms（初回は読み込み込みで ${firstMs}ms）\n${first ?: "（認識できなかった）"}"
                                next()
                            }
                        }
                    }
                }
            }
            for (m in VoiceModel.entries) {
                if (!m.isReady(context)) continue
                steps += { next ->
                    val started = System.nanoTime()
                    VoiceInput.transcribe(context, pcm, m, threads) { text, ms ->
                        val total = (System.nanoTime() - started) / 1_000_000
                        main.execute {
                            lines = lines + "whisper ${m.name}: ${ms}ms（読み込み込みで ${total}ms）\n${text ?: "（認識できなかった）"}"
                            next()
                        }
                    }
                }
            }
            if (steps.isEmpty()) {
                lines = listOf("モデルがまだない")
                running = false
                return@Button
            }
            fun run(i: Int) {
                if (i < steps.size) steps[i] { run(i + 1) } else running = false
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
                Note("物理キーボードのキーを押すとそれにする。一覧からも選べる")
                RadioRows(choices.map { it to keyName(it) }, current, onPick)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
