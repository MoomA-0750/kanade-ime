package dev.mooma.kanade.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 設定の値（SharedPreferences）を Compose の状態として持つ。書き換えると、すぐ保存もする
 */
class PrefState<T>(initial: T, private val write: (T) -> Unit) : MutableState<T> {
    private val state = mutableStateOf(initial)
    override var value: T
        get() = state.value
        set(v) {
            state.value = v
            write(v)
        }

    override fun component1() = value
    override fun component2(): (T) -> Unit = { value = it }
}

@Composable
fun <T> rememberPref(read: () -> T, write: (T) -> Unit): PrefState<T> = remember { PrefState(read(), write) }

/** ひとまとまりの設定を入れるカード */
@Composable
fun Section(title: String, description: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            if (description != null) Note(description)
            content()
        }
    }
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 入り切りの行（行のどこを押しても切り替わる） */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, summary: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) Note(summary)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** 縦に並べる選択肢（説明が長いもの用） */
@Composable
fun <T> RadioRows(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        for ((value, label) in options) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == selected, onClick = null)
                Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** つながったボタンの選択肢（Material 3 Expressive の connected button group。短い選択肢用） */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ConnectedChoices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { i, (value, label) ->
            ToggleButton(
                checked = value == selected,
                onCheckedChange = { onSelect(value) },
                modifier = Modifier.weight(1f),
                // 選んでいないものも、カードの上で見えるよう少し濃い面にする
                colors = ToggleButtonDefaults.toggleButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 値を動かす行。[format] で今の値を見出しに出す */
@Composable
fun SliderRow(title: String, value: Int, range: IntRange, step: Int, onChange: (Int) -> Unit, format: (Int) -> String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(format(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange((Math.round(it / step) * step).coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
        )
    }
}
