package dev.mooma.kanade.settings

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mooma.kanade.ConversionBench
import dev.mooma.kanade.KeyLog
import dev.mooma.kanade.R
import dev.mooma.kanade.core.log.InputLogWriter
import kasane.KasaneSpace

/** 試し打ち・変換の試験・キー記録 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ColumnScope.ToolsScreen() {
    val context = LocalContext.current
    val settings = rememberSettings()

    Section(stringResource(R.string.app_tools_try_typing)) {
        var text by remember { mutableStateOf("") }
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.app_tools_try_typing_hint)) })
    }

    Section(stringResource(R.string.app_tools_conversion_test), stringResource(R.string.app_tools_conversion_test_description)) {
        var reading by remember { mutableStateOf("") }
        var result by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        val main = context.mainExecutor
        fun run(work: ((String) -> Unit) -> String) {
            running = true
            Thread {
                val r = runCatching { work { msg -> main.execute { result = msg } } }.getOrElse { context.getString(R.string.app_tools_failed, it.toString()) }
                main.execute {
                    result = r
                    running = false
                }
            }.start()
        }
        var threads by rememberPref({ settings.zenzThreads }, { settings.zenzThreads = it })
        SliderRow(stringResource(R.string.app_tools_zenz_threads), threads, 1..8, 1, { threads = it }) { "$it" }
        OutlinedTextField(reading, { reading = it }, Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.app_tools_reading_example)) }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(KasaneSpace.s2), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { run { ConversionBench.tryConvert(context, reading.trim()) } }, enabled = !running && reading.isNotBlank()) { Text(stringResource(R.string.app_tools_convert)) }
            OutlinedButton(onClick = { run { progress -> ConversionBench.runEval(context, progress) } }, enabled = !running) { Text(stringResource(R.string.app_tools_run_eval)) }
            if (running) LoadingIndicator(Modifier.size(40.dp))
        }
        if (result.isNotEmpty()) SelectionContainer { Text(result, style = MaterialTheme.typography.bodyMedium) }
    }

    Section(stringResource(R.string.app_tools_key_log), stringResource(R.string.app_tools_key_log_description)) {
        val keyLog = remember { KeyLog(context) }
        var enabled by rememberPref({ settings.keyLogEnabled }, { settings.keyLogEnabled = it })
        SwitchRow(stringResource(R.string.app_tools_key_log_enable), enabled, { enabled = it })
        var version by remember { mutableIntStateOf(0) }
        val log = remember(version) { keyLog.read().lines().takeLast(200).joinToString("\n") }
        Row(horizontalArrangement = Arrangement.spacedBy(KasaneSpace.s2)) {
            OutlinedButton(onClick = { version++ }) { Text(stringResource(R.string.app_tools_reload)) }
            OutlinedButton(onClick = {
                keyLog.clear()
                version++
            }) { Text(stringResource(R.string.app_tools_clear)) }
        }
        val empty = stringResource(R.string.app_tools_key_log_empty)
        SelectionContainer {
            Column(
                Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(log.ifEmpty { empty }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }

    Section(stringResource(R.string.app_tools_input_log), stringResource(R.string.app_tools_input_log_description)) {
        // 書き手は IME サービスの中。ここは読む・消すだけ（書くスレッドは起こさない）
        val reader = remember { InputLogWriter(java.io.File(context.filesDir, "input-log"), startThread = false) }
        var on by rememberPref({ settings.inputLog }, { settings.inputLog = it })
        SwitchRow(stringResource(R.string.app_tools_input_log_enable), on, { on = it })
        var days by rememberPref({ settings.inputLogDays }, { settings.inputLogDays = it })
        SliderRow(stringResource(R.string.app_tools_input_log_days), days, 7..365, 1, { days = it }) { context.getString(R.string.app_tools_input_log_days_value, it) }
        var maxMb by rememberPref({ settings.inputLogMaxMb }, { settings.inputLogMaxMb = it })
        SliderRow(stringResource(R.string.app_tools_input_log_max), maxMb, 10..1000, 10, { maxMb = it }) { context.getString(R.string.app_tools_input_log_max_value, it) }
        var version by remember { mutableIntStateOf(0) }
        val stats = remember(version) { reader.stats() }
        val recent = remember(version) { reader.tail(50).joinToString("\n") }
        Text(
            stringResource(R.string.app_tools_input_log_stats, stats.first, android.text.format.Formatter.formatShortFileSize(context, stats.second)),
            style = MaterialTheme.typography.bodyMedium,
        )
        var confirm by remember { mutableStateOf(false) }
        Row(horizontalArrangement = Arrangement.spacedBy(KasaneSpace.s2)) {
            OutlinedButton(onClick = { version++ }) { Text(stringResource(R.string.app_tools_reload)) }
            OutlinedButton(onClick = { confirm = true }) { Text(stringResource(R.string.app_tools_input_log_clear_all)) }
        }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text(stringResource(R.string.app_tools_input_log_clear_title)) },
                text = { Text(stringResource(R.string.app_tools_input_log_clear_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        reader.clearAll()
                        confirm = false
                        version++
                    }) { Text(stringResource(R.string.app_tools_input_log_clear_confirm)) }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.app_tools_input_log_cancel)) } },
            )
        }
        Text(stringResource(R.string.app_tools_input_log_recent), style = MaterialTheme.typography.titleSmallEmphasized)
        val empty = stringResource(R.string.app_tools_key_log_empty)
        SelectionContainer {
            Column(
                Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(recent.ifEmpty { empty }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}

/**
 * 使い方（docs/USAGE.md・USAGE.en.md を assets に入れたもの）。見出し・箇条書き・表を簡単に整えて出す。
 * 表示言語が日本語でなければ英語版（usage.en.md）を読み、無ければ日本語版にする
 */
@Composable
fun ColumnScope.UsageScreen() {
    val context = LocalContext.current
    val language = LocalConfiguration.current.locales[0].language
    val notFound = stringResource(R.string.app_usage_not_found)
    val blocks = remember(language) {
        val names = if (language == "ja") listOf("usage.md") else listOf("usage.en.md", "usage.md")
        val text = names.firstNotNullOfOrNull { name -> runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrNull() }
        parseUsage(text ?: notFound)
    }
    // 箇条書きの印と表の区切り（英語では全角の記号を使わない）
    val bullet = if (language == "ja") "・" else "• "
    val cellGap = if (language == "ja") "　" else "  "
    for ((title, lines) in blocks) {
        Section(title) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(KasaneSpace.s1)) {
                    for (line in lines) {
                        when {
                            line.startsWith("### ") -> Text(line.removePrefix("### "), Modifier.padding(top = KasaneSpace.s2), style = MaterialTheme.typography.titleSmallEmphasized)
                            line.startsWith("- ") || line.matches(Regex("^\\d+\\. .*")) || line.startsWith("   ") ->
                                Text(bullet + line.trimStart().removePrefix("- ").replace(Regex("^\\d+\\. "), ""), style = MaterialTheme.typography.bodyMedium)
                            line.startsWith("|") -> Text(line.trim('|').split('|').joinToString(cellGap) { it.trim() }, style = MaterialTheme.typography.bodyMedium)
                            line.isBlank() -> {}
                            else -> Text(line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

/** 表示言語の選択肢。端末に合わせる（空）・日本語・英語 */
private enum class AppLanguage(val tags: String) { SYSTEM(""), JAPANESE("ja"), ENGLISH("en") }

/**
 * 表示言語。Android 13 以降はアプリごとの言語（LocaleManager）を切り替える。
 * 端末の設定の「アプリの言語」と同じもので、変えると画面は自動で作り直される
 */
@Composable
fun ColumnScope.LanguageScreen() {
    val context = LocalContext.current
    Section(stringResource(R.string.app_language_title), stringResource(R.string.app_language_description)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val current = remember {
                val locales = manager.applicationLocales
                when {
                    locales.isEmpty() -> AppLanguage.SYSTEM
                    locales[0].language == "ja" -> AppLanguage.JAPANESE
                    locales[0].language == "en" -> AppLanguage.ENGLISH
                    else -> AppLanguage.SYSTEM
                }
            }
            ConnectedChoices(
                listOf(
                    AppLanguage.SYSTEM to stringResource(R.string.app_language_system),
                    AppLanguage.JAPANESE to stringResource(R.string.app_language_japanese),
                    AppLanguage.ENGLISH to stringResource(R.string.app_language_english),
                ),
                current,
                { choice ->
                    if (choice != current) {
                        manager.applicationLocales =
                            if (choice == AppLanguage.SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(choice.tags)
                    }
                },
            )
        } else {
            Note(stringResource(R.string.app_language_requires_android13))
        }
    }
}

/** 「## 見出し」ごとに分ける。強調や `コード` の記号は外す */
private fun parseUsage(md: String): List<Pair<String, List<String>>> {
    val out = ArrayList<Pair<String, MutableList<String>>>()
    for (raw in md.lines()) {
        if (raw.startsWith("|---") || raw.startsWith("```")) continue
        val line = raw.replace("**", "").replace("`", "")
        when {
            line.startsWith("# ") -> out += line.removePrefix("# ") to mutableListOf()
            line.startsWith("## ") -> out += line.removePrefix("## ") to mutableListOf()
            else -> {
                if (out.isEmpty()) out += "Kanade" to mutableListOf()
                out.last().second += line
            }
        }
    }
    return out.filter { it.second.any { l -> l.isNotBlank() } }
}
