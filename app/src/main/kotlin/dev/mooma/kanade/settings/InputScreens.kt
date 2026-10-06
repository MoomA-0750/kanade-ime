package dev.mooma.kanade.settings

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
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
import dev.mooma.kanade.LAYOUTS
import dev.mooma.kanade.KanadeSettings
import dev.mooma.kanade.R
import dev.mooma.kanade.ToggleTrigger
import java.io.File

@Composable
fun rememberSettings(): KanadeSettings {
    val context = LocalContext.current
    return remember { KanadeSettings(context) }
}

/** 入力と変換 */
@Composable
fun ColumnScope.InputScreen() {
    val context = LocalContext.current
    val settings = rememberSettings()

    Section(stringResource(R.string.input_toggle_keys_title), stringResource(R.string.input_toggle_keys_description)) {
        var triggers by rememberPref({ settings.toggleTriggers }, { settings.toggleTriggers = it })
        for (t in ToggleTrigger.entries) {
            SwitchRow(stringResource(t.labelRes), t in triggers, { on -> triggers = if (on) triggers + t else triggers - t })
        }
        OutlinedButton(onClick = { context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }) {
            Text(stringResource(R.string.input_switch_input_method))
        }
    }

    Section(stringResource(R.string.input_characters_title)) {
        var fullWidth by rememberPref({ settings.fullWidthSymbols }, { settings.fullWidthSymbols = it })
        SwitchRow(stringResource(R.string.input_full_width_symbols_title), fullWidth, { fullWidth = it }, stringResource(R.string.input_full_width_symbols_summary))
        var pairBrackets by rememberPref({ settings.pairBrackets }, { settings.pairBrackets = it })
        SwitchRow(
            stringResource(R.string.input_pair_brackets_title), pairBrackets, { pairBrackets = it },
            stringResource(R.string.input_pair_brackets_summary),
        )
        Text(stringResource(R.string.input_punctuation_label))
        var punctuation by rememberPref({ settings.punctuation }, { settings.punctuation = it })
        ConnectedChoices(listOf("、。", "，．", "、．", "，。").map { it to it }, punctuation, { punctuation = it })
    }

    Section(stringResource(R.string.input_conversion_title)) {
        var live by rememberPref({ settings.liveConversion }, { settings.liveConversion = it })
        SwitchRow(stringResource(R.string.input_live_conversion_title), live, { live = it }, stringResource(R.string.input_live_conversion_summary))
        var learning by rememberPref({ settings.learningEnabled }, { settings.learningEnabled = it })
        SwitchRow(stringResource(R.string.input_learning_title), learning, { learning = it }, stringResource(R.string.input_learning_summary))
        var confirm by remember { mutableStateOf(false) }
        OutlinedButton(onClick = { confirm = true }) { Text(stringResource(R.string.input_clear_learning)) }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text(stringResource(R.string.input_clear_learning)) },
                text = { Text(stringResource(R.string.input_clear_learning_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        File(context.filesDir, "learning.tsv").delete()
                        settings.learningResetAt = System.currentTimeMillis()
                        confirm = false
                    }) { Text(stringResource(R.string.input_clear)) }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.input_cancel)) } },
            )
        }
    }

    PreviewAppsSection()
}

/** 画面キーボード */
@Composable
fun ColumnScope.KeyboardScreen() {
    val settings = rememberSettings()

    Section(stringResource(R.string.input_show_keyboard_title), stringResource(R.string.input_show_keyboard_description)) {
        var mode by rememberPref({ settings.softKeyboard }, { settings.softKeyboard = it })
        ConnectedChoices(listOf(
            "AUTO" to stringResource(R.string.input_show_keyboard_auto),
            "ALWAYS" to stringResource(R.string.input_show_keyboard_always),
            "NEVER" to stringResource(R.string.input_show_keyboard_never),
        ), mode, { mode = it })
        Note(
            when (mode) {
                "AUTO" -> stringResource(R.string.input_show_keyboard_auto_note)
                "ALWAYS" -> stringResource(R.string.input_show_keyboard_always_note)
                else -> stringResource(R.string.input_show_keyboard_never_note)
            },
        )
    }

    Section(stringResource(R.string.input_layouts_title), stringResource(R.string.input_layouts_description)) {
        val names = mapOf(
            "GLIDE" to stringResource(R.string.input_layout_glide),
            "QWERTY" to stringResource(R.string.input_layout_qwerty),
            "FLICK" to stringResource(R.string.input_layout_flick),
        )
        var enabled by rememberPref({ settings.enabledLayouts }, { settings.enabledLayouts = it })
        var layout by rememberPref({ settings.softKeyboardLayout }, { settings.softKeyboardLayout = it })
        Note(stringResource(R.string.input_layouts_enabled_note))
        val descriptions = mapOf(
            "GLIDE" to stringResource(R.string.input_layout_glide_description),
            "QWERTY" to stringResource(R.string.input_layout_qwerty_description),
            "FLICK" to stringResource(R.string.input_layout_flick_description),
        )
        for (id in LAYOUTS) {
            SwitchRow(names.getValue(id), id in enabled, { on ->
                // 最後の1つは外せない
                val next = if (on) enabled + id else enabled - id
                if (next.isNotEmpty()) {
                    enabled = next
                    if (layout !in next) layout = LAYOUTS.first { it in next }
                }
            }, descriptions.getValue(id))
            if (id in enabled) LayoutModesRow(settings, id)
        }
        if ("GLIDE" in enabled) {
            var glideZenz by rememberPref({ settings.glideZenz }, { settings.glideZenz = it })
            SwitchRow(
                stringResource(R.string.input_glide_zenz_title), glideZenz, { glideZenz = it },
                stringResource(R.string.input_glide_zenz_summary),
            )
        }
        if ("FLICK" in enabled) {
            var toggle by rememberPref({ settings.flickToggle }, { settings.flickToggle = it })
            SwitchRow(stringResource(R.string.input_flick_toggle_title), toggle, { toggle = it }, stringResource(R.string.input_flick_toggle_summary))
        }
    }

    Section(stringResource(R.string.input_clipboard_title), stringResource(R.string.input_clipboard_description)) {
        var on by rememberPref({ settings.clipboardHistory }, { settings.clipboardHistory = it })
        SwitchRow(stringResource(R.string.input_clipboard_history), on, { on = it })
        if (on) {
            var expiry by rememberPref({ settings.clipboardExpiryHours }, { settings.clipboardExpiryHours = it })
            Text(stringResource(R.string.input_clipboard_expiry), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            RadioRows(
                listOf(
                    0 to stringResource(R.string.input_clipboard_expiry_never),
                    1 to stringResource(R.string.input_clipboard_expiry_hour),
                    24 to stringResource(R.string.input_clipboard_expiry_day),
                    24 * 7 to stringResource(R.string.input_clipboard_expiry_week),
                    24 * 30 to stringResource(R.string.input_clipboard_expiry_month),
                ),
                expiry, { expiry = it },
            )
            Note(stringResource(R.string.input_clipboard_expiry_note))
        }
    }

    Section(stringResource(R.string.input_size_title)) {
        var height by rememberPref({ settings.softKeyboardHeight }, { settings.softKeyboardHeight = it })
        SliderRow(stringResource(R.string.input_height), height, 60..300, 10, { height = it }) { "$it%" }
        var bottom by rememberPref({ settings.softKeyboardBottom }, { settings.softKeyboardBottom = it })
        SliderRow(stringResource(R.string.input_bottom_margin), bottom, 0..64, 4, { bottom = it }) { "${it}dp" }
        Note(stringResource(R.string.input_size_note))
    }

    Section(stringResource(R.string.input_gestures_title)) {
        var tapCorrection by rememberPref({ settings.tapCorrection }, { settings.tapCorrection = it })
        SwitchRow(stringResource(R.string.input_tap_correction), tapCorrection, { tapCorrection = it }, stringResource(R.string.input_tap_correction_note))
        var swipe by rememberPref({ settings.softKeyboardSwipe }, { settings.softKeyboardSwipe = it })
        SliderRow(stringResource(R.string.input_swipe_distance), swipe, 25..80, 5, { swipe = it }) { "$it%" }
        Note(stringResource(R.string.input_swipe_distance_note))
        for (line in listOf(
            R.string.input_gesture_swipe_keys,
            R.string.input_gesture_modifiers,
            R.string.input_gesture_modifier_events,
            R.string.input_gesture_meta_fn,
            R.string.input_gesture_arrows,
            R.string.input_gesture_mic,
        )) Note(stringResource(line))
    }
}

/** 配列ごとに使う言語（日本語・英語・数字/記号）を選ぶチップ。1つは残す */
@Composable
private fun LayoutModesRow(settings: dev.mooma.kanade.KanadeSettings, layout: String) {
    var modes by remember(layout) { mutableStateOf(settings.layoutModes(layout)) }
    val names = mapOf(
        "JA" to stringResource(R.string.ime_mode_ja),
        "EN" to stringResource(R.string.ime_mode_en),
        "NUM" to stringResource(R.string.ime_mode_num),
    )
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (mode in dev.mooma.kanade.KeyboardSources.choosableModes(layout)) {
            androidx.compose.material3.FilterChip(
                selected = mode in modes,
                onClick = {
                    val next = if (mode in modes) modes - mode else modes + mode
                    if (next.isNotEmpty()) {
                        modes = next
                        settings.setLayoutModes(layout, next)
                    }
                },
                label = { Text(names.getValue(mode)) },
            )
        }
    }
}
