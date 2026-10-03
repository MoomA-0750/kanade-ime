package dev.mooma.kanade.settings

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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mooma.kanade.ConversionBench
import dev.mooma.kanade.KeyLog

/** 試し打ち・変換の試験・キー記録 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ColumnScope.ToolsScreen() {
    val context = LocalContext.current
    val settings = rememberSettings()

    Section("試し打ち") {
        var text by remember { mutableStateOf("") }
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), placeholder = { Text("ここで入力を試す") })
    }

    Section("変換の試験", "ひらがなの読みを入れて、辞書と zenz の結果と時間を見る。評価セット（202 文）で正解率も測れる") {
        var reading by remember { mutableStateOf("") }
        var result by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        val main = context.mainExecutor
        fun run(work: ((String) -> Unit) -> String) {
            running = true
            Thread {
                val r = runCatching { work { msg -> main.execute { result = msg } } }.getOrElse { "失敗: $it" }
                main.execute {
                    result = r
                    running = false
                }
            }.start()
        }
        OutlinedTextField(reading, { reading = it }, Modifier.fillMaxWidth(), placeholder = { Text("例: きょうはいいてんきですね") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { run { ConversionBench.tryConvert(context, reading.trim()) } }, enabled = !running && reading.isNotBlank()) { Text("変換") }
            OutlinedButton(onClick = { run { progress -> ConversionBench.runEval(context, progress) } }, enabled = !running) { Text("評価セットで測る") }
            if (running) LoadingIndicator(Modifier.size(40.dp))
        }
        if (result.isNotEmpty()) SelectionContainer { Text(result, style = MaterialTheme.typography.bodyMedium) }
    }

    Section("キー記録", "IME に届いたキーを記録する。DeX が先に取るキーはここに出ない") {
        val keyLog = remember { KeyLog(context) }
        var enabled by rememberPref({ settings.keyLogEnabled }, { settings.keyLogEnabled = it })
        SwitchRow("記録する", enabled, { enabled = it })
        var version by remember { mutableIntStateOf(0) }
        val log = remember(version) { keyLog.read().lines().takeLast(200).joinToString("\n") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { version++ }) { Text("読み直す") }
            OutlinedButton(onClick = {
                keyLog.clear()
                version++
            }) { Text("消す") }
        }
        SelectionContainer {
            Column(
                Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            ) {
                Text(log.ifEmpty { "（まだない）" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }
}

/** 使い方（docs/USAGE.md を assets に入れたもの）。見出し・箇条書き・表を簡単に整えて出す */
@Composable
fun ColumnScope.UsageScreen() {
    val context = LocalContext.current
    val blocks = remember { parseUsage(runCatching { context.assets.open("usage.md").bufferedReader().readText() }.getOrDefault("（使い方が見つからない）")) }
    for ((title, lines) in blocks) {
        Section(title) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (line in lines) {
                        when {
                            line.startsWith("### ") -> Text(line.removePrefix("### "), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            line.startsWith("- ") || line.matches(Regex("^\\d+\\. .*")) || line.startsWith("   ") ->
                                Text("・" + line.trimStart().removePrefix("- ").replace(Regex("^\\d+\\. "), ""), style = MaterialTheme.typography.bodyMedium)
                            line.startsWith("|") -> Text(line.trim('|').split('|').joinToString("　") { it.trim() }, style = MaterialTheme.typography.bodyMedium)
                            line.isBlank() -> {}
                            else -> Text(line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
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
