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
import dev.mooma.kanade.R
import androidx.compose.ui.unit.dp

/** 左の一覧の区画 */
enum class CategoryGroup(val title: String) { INPUT("入力"), WORDS("辞書"), OTHER("その他") }

/** 設定のまとまり。左の一覧に区画ごとに並べ、選ぶと中身を出す。アイコンは Material Symbols */
enum class Category(val title: String, val summary: String, val icon: Int, val group: CategoryGroup) {
    INPUT("入力と変換", "日本語オン/オフのキー・句読点・ライブ変換・学習", R.drawable.sym_translate, CategoryGroup.INPUT),
    KEYBOARD("画面キーボード", "出すとき・高さ・操作", R.drawable.sym_keyboard, CategoryGroup.INPUT),
    VOICE("音声入力", "モデル・録音キー・話し方", R.drawable.sym_mic, CategoryGroup.INPUT),
    DICTIONARY("ユーザー辞書", "読みと語を登録して、変換で優先します", R.drawable.sym_dictionary, CategoryGroup.WORDS),
    SNIPPETS("スニペット", "打った瞬間に置き換える文字列", R.drawable.sym_bolt, CategoryGroup.WORDS),
    TOOLS("試験と記録", "試し打ち・変換の試験・キー記録", R.drawable.sym_science, CategoryGroup.OTHER),
    USAGE("使い方", "Kanade の使い方", R.drawable.sym_help, CategoryGroup.OTHER),
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
                subtitle = { Text("物理キーボードのための日本語入力") },
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
            group.title,
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
                    headlineContent = { Text(c.title, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(c.summary) },
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
                "Kanade を入力方法として有効にしてください",
                "Android の「入力方法」で Kanade をオンにすると、キーボードで日本語が打てるようになります。",
                "入力方法の設定を開く",
            ) { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        } else if (!state.imeSelected) {
            SetupCard(
                "Kanade に切り替えてください",
                "Kanade は有効になっていますが、今使っている入力方法が Kanade ではありません。",
                "入力方法を選ぶ",
            ) { context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        }
        if (!state.overlay) {
            SetupCard(
                "「他のアプリの上に重ねて表示」を許可してください",
                "変換候補と「あ」「A」の表示を、カーソルの近くに出すのに使います。許可しないと、Galaxy で物理キーボードを使っているときに候補が出ないことがあります。",
                "許可する",
            ) { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }
        }
        if (!micGranted) {
            SetupCard(
                "マイクを許可してください",
                "音声入力に使います。声は端末の中で文字にし、外へは送りません。",
                if (micAsked) "アプリの設定で許可する" else "許可する",
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
                title = { Text(category.title) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.sym_arrow_back), contentDescription = "戻る") }
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
                    Category.TOOLS -> ToolsScreen()
                    Category.USAGE -> UsageScreen()
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
