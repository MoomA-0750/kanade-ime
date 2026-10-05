package dev.mooma.kanade.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.mooma.kanade.R
import androidx.compose.ui.unit.dp

/** 左の一覧の区画（見出しは文字列リソース） */
enum class CategoryGroup(val title: Int) { INPUT(R.string.app_group_input), WORDS(R.string.app_group_words), OTHER(R.string.app_group_other) }

/** 設定のまとまり。左の一覧に区画ごとに並べ、選ぶと中身を出す。見出しと説明は文字列リソース、アイコンは Material Symbols */
enum class Category(val title: Int, val summary: Int, val icon: Int, val group: CategoryGroup) {
    INPUT(R.string.app_category_input_title, R.string.app_category_input_summary, R.drawable.sym_translate, CategoryGroup.INPUT),
    KEYBOARD(R.string.app_category_keyboard_title, R.string.app_category_keyboard_summary, R.drawable.sym_keyboard, CategoryGroup.INPUT),
    VOICE(R.string.app_category_voice_title, R.string.app_category_voice_summary, R.drawable.sym_mic, CategoryGroup.INPUT),
    DICTIONARY(R.string.app_category_dictionary_title, R.string.app_category_dictionary_summary, R.drawable.sym_dictionary, CategoryGroup.WORDS),
    SNIPPETS(R.string.app_category_snippets_title, R.string.app_category_snippets_summary, R.drawable.sym_bolt, CategoryGroup.WORDS),
    LANGUAGE(R.string.app_category_language_title, R.string.app_category_language_summary, R.drawable.sym_language, CategoryGroup.OTHER),
    BACKUP(R.string.app_category_backup_title, R.string.app_category_backup_summary, R.drawable.sym_upload_file, CategoryGroup.OTHER),
    TOOLS(R.string.app_category_tools_title, R.string.app_category_tools_summary, R.drawable.sym_science, CategoryGroup.OTHER),
    USAGE(R.string.app_category_usage_title, R.string.app_category_usage_summary, R.drawable.sym_help, CategoryGroup.OTHER),
}

/** 幅がこれ以上なら2カラム（左に一覧、右に中身）。Fold の内側の画面や DeX の広い窓 */
private val TWO_PANE_WIDTH = 600.dp
private val LIST_PANE_WIDTH = 360.dp

/**
 * 設定画面の全体。スマホの幅では1カラム（一覧→中身へ移る）、広い画面では2カラム。
 * 足りない権限・設定があるときだけ、一覧の一番上にその案内を出す。[resumeTick] は画面に戻るたびに増える（権限の状態を読み直す）
 */
@Composable
fun SettingsApp(resumeTick: Int) {
    var selected by rememberSaveable { mutableStateOf<Category?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 実際の幅（インチ）も見る（表示を小さくした細い画面では2カラムにしない。画面キーボードと同じ決まり）
        val context = LocalContext.current
        val px = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.roundToPx() }
        if (maxWidth >= TWO_PANE_WIDTH && dev.mooma.kanade.ScreenWidth.isWide(context, px)) {
            val current = selected ?: Category.INPUT
            Row(Modifier.fillMaxSize()) {
                ListPane(resumeTick, current, onSelect = { selected = it }, modifier = Modifier.width(LIST_PANE_WIDTH).fillMaxHeight())
                VerticalDivider()
                DetailPane(current, onBack = null, modifier = Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            BackHandler(enabled = selected != null) { selected = null }
            AnimatedContent(targetState = selected, label = "pane") { category ->
                if (category == null) {
                    ListPane(resumeTick, null, onSelect = { selected = it }, modifier = Modifier.fillMaxSize())
                } else {
                    DetailPane(category, onBack = { selected = null }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ListPane(resumeTick: Int, selected: Category?, onSelect: (Category) -> Unit, modifier: Modifier) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Kanade") },
                subtitle = { Text(stringResource(R.string.app_header_subtitle)) },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SetupCards(resumeTick)
            CategoryList(selected, onSelect)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** まとまりの一覧。区画ごとに見出しを付け、区画の中は Expressive のつながったリスト（先頭と末尾だけ大きく丸める） */
@Composable
private fun CategoryList(selected: Category?, onSelect: (Category) -> Unit) {
    for (group in CategoryGroup.entries) {
        Text(
            stringResource(group.title),
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        CategoryGroupList(Category.entries.filter { it.group == group }, selected, onSelect)
    }
}

@Composable
private fun CategoryGroupList(items: List<Category>, selected: Category?, onSelect: (Category) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEachIndexed { i, c ->
            val big = 24.dp
            val small = 6.dp
            val shape = RoundedCornerShape(
                topStart = if (i == 0) big else small, topEnd = if (i == 0) big else small,
                bottomStart = if (i == items.lastIndex) big else small, bottomEnd = if (i == items.lastIndex) big else small,
            )
            val isSelected = c == selected
            Surface(
                onClick = { onSelect(c) },
                shape = shape,
                color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(c.title), style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(stringResource(c.summary)) },
                    leadingContent = {
                        Box(
                            Modifier.size(40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(c.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
            }
        }
    }
}

/** 足りない権限・設定。そろっていれば何も出さない */
@Composable
private fun SetupCards(resumeTick: Int) {
    val context = LocalContext.current
    // 画面に戻るたびに読み直す
    val state = remember(resumeTick) { SetupState.read(context) }
    var micAsked by remember { mutableStateOf(false) }
    var micGranted by remember(resumeTick) { mutableStateOf(state.mic) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micAsked = true
        micGranted = granted
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!state.imeEnabled) {
            SetupCard(
                stringResource(R.string.app_setup_enable_title),
                stringResource(R.string.app_setup_enable_why),
                stringResource(R.string.app_setup_enable_action),
            ) { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        } else if (!state.imeSelected) {
            SetupCard(
                stringResource(R.string.app_setup_select_title),
                stringResource(R.string.app_setup_select_why),
                stringResource(R.string.app_setup_select_action),
            ) { context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        }
        if (!state.overlay) {
            SetupCard(
                stringResource(R.string.app_setup_overlay_title),
                stringResource(R.string.app_setup_overlay_why),
                stringResource(R.string.app_setup_allow),
            ) { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
        }
        if (!micGranted) {
            SetupCard(
                stringResource(R.string.app_setup_mic_title),
                stringResource(R.string.app_setup_mic_why),
                stringResource(if (micAsked) R.string.app_setup_mic_allow_in_settings else R.string.app_setup_allow),
            ) {
                if (micAsked) {
                    // 2回断られると、もうダイアログが出ないので、アプリの設定を開く
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                } else {
                    micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
}

@Composable
private fun SetupCard(title: String, why: String, action: String, onClick: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.sym_warning), contentDescription = null, modifier = Modifier.size(20.dp))
                Text(title, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
            }
            Text(why, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onClick, modifier = Modifier.align(Alignment.End)) { Text(action) }
        }
    }
}

/** 権限・有効化の状態 */
data class SetupState(val imeEnabled: Boolean, val imeSelected: Boolean, val overlay: Boolean, val mic: Boolean) {
    companion object {
        fun read(context: Context): SetupState {
            val imm = context.getSystemService(InputMethodManager::class.java)
            val enabled = imm.enabledInputMethodList.any { it.packageName == context.packageName }
            val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
            return SetupState(
                imeEnabled = enabled,
                imeSelected = current.startsWith(context.packageName + "/"),
                overlay = Settings.canDrawOverlays(context),
                mic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailPane(category: Category, onBack: (() -> Unit)?, modifier: Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(category.title)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.sym_arrow_back), contentDescription = stringResource(R.string.app_back)) }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (category) {
                    Category.INPUT -> InputScreen()
                    Category.KEYBOARD -> KeyboardScreen()
                    Category.VOICE -> VoiceScreen()
                    Category.DICTIONARY -> PairEditorScreen(PairKind.DICTIONARY)
                    Category.SNIPPETS -> PairEditorScreen(PairKind.SNIPPETS)
                    Category.LANGUAGE -> LanguageScreen()
                    Category.BACKUP -> BackupScreen()
                    Category.TOOLS -> ToolsScreen()
                    Category.USAGE -> UsageScreen()
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
