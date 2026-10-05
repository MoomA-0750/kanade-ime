package dev.mooma.kanade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.mooma.kanade.core.dict.EmojiPanelData

/**
 * 画面キーボードの絵文字パネル。上に検索の欄とグループのタブ（最近使ったもの＋Unicode のグループ）、真ん中に絵文字の一覧、
 * 下にキーボードへ戻る・空白・Backspace。色は画面キーボードと同じ（Material 3）。
 * 検索の欄を押すと検索モードになり、パネルは検索の欄と結果の1行だけに縮み、その下に画面キーボードが出る
 * （IME は検索中、変換の結果をここへ回す: 入力中の文字は [setSearchComposing]、確定は [commitSearch]、
 * 変換の候補は [showSearchCandidates]。IME が使わなかったキーは [typeSearch]・[backspaceSearch]）。
 */
@SuppressLint("ViewConstructor")
class EmojiPanelView(
    context: Context,
    private val onEmoji: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onSpace: () -> Unit,
    private val onClose: () -> Unit,
    /** 検索モードに入った（true）・出た（false）。IME はパネルの高さとキーボードを切り替える */
    private val onSearchMode: (Boolean) -> Unit,
    /** 検索の欄で変換の候補を選んだ */
    private val onSearchCandidate: (Int) -> Unit,
) : LinearLayout(context) {
    private val palette = KeyboardPalette.of(context)
    private val density = resources.displayMetrics.density
    private val prefs = context.getSharedPreferences("kanade_ui", Context.MODE_PRIVATE)
    private val data: EmojiPanelData by lazy {
        EmojiPanelData.read(runCatching { context.assets.open("emoji-panel.tsv").bufferedReader().readText() }.getOrDefault(""))
    }
    /** この端末のフォントで描けるものだけ（新しすぎる絵文字は豆腐になるので出さない） */
    private val drawable = HashMap<String, List<String>>()
    private val glyphPaint = Paint()

    /** タブ: 0 が最近使ったもの、1 から Unicode のグループ */
    private var tab = 0
    private val tabBar = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val grid = GridView(context)
    private var shown: List<String> = emptyList()

    /** 下の行の「キーボードに戻る」に出す文字（日本語入力中は あ） */
    var japanese = false
        set(value) {
            field = value
            backLabel.text = if (value) "あ" else "ABC"
        }
    private val backLabel = TextView(context)

    /** 一覧を見ているときの部品（検索モードでは隠す） */
    private val browseViews = ArrayList<View>()
    /** 検索モードの部品 */
    private val searchViews = ArrayList<View>()
    var searching = false
        private set
    private var query = ""
    /** 検索の欄で変換中・入力中の文字（IME から） */
    private var composing = ""
    /** 変換の候補を出しているか（出している間は結果の行に候補を並べる） */
    private var candidates: List<String>? = null
    private var selectedCandidate = 0
    private val queryView = TextView(context)
    private val results = LinearLayout(context).apply { orientation = HORIZONTAL }

    private fun dp(v: Int) = (v * density).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.background)
        // 検索の欄（押すと検索モード）
        val searchPill = pill().apply {
            addView(icon(R.drawable.sym_search, palette.dim), LayoutParams(dp(20), dp(20)).apply { setMargins(dp(12), 0, dp(8), 0) })
            addView(TextView(context).apply {
                text = context.getString(R.string.ime_emoji_search)
                setTextColor(palette.dim)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            setOnClickListener { startSearch() }
        }
        val tabs = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabBar)
        }
        if (ScreenWidth.isWide(context)) {
            // 幅が十分なら、種類のタブの横に検索の欄を置く（1行にまとめて、絵文字の一覧を広く取る）
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(tabs, LayoutParams(LayoutParams.WRAP_CONTENT, dp(44)))
                addView(searchPill, LayoutParams(0, dp(40), 1f).apply { setMargins(dp(8), 0, dp(8), 0) })
            }
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply { setMargins(0, dp(4), 0, 0) })
            browseViews += row
        } else {
            addView(searchPill, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply { setMargins(dp(8), dp(6), dp(8), dp(2)) })
            browseViews += searchPill
            addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
            browseViews += tabs
        }
        grid.apply {
            columnWidth = dp(48)
            numColumns = GridView.AUTO_FIT
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            isVerticalScrollBarEnabled = true
            selector = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(palette.pressed) }
            setOnItemClickListener { _, _, position, _ ->
                val e = shown[position]
                onEmoji(e)
                remember(e)
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            }
            adapter = EmojiAdapter()
        }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        browseViews += grid
        val bottom = bottomRow()
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
        browseViews += bottom
        // 検索モード: 戻る・打った言葉・消す、その下に結果の1行
        val searchBar = pill().apply {
            addView(icon(R.drawable.sym_arrow_back, palette.text).apply { setOnClickListener { endSearch() } }, LayoutParams(dp(40), LayoutParams.MATCH_PARENT))
            queryView.apply {
                setTextColor(palette.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                isSingleLine = true
            }
            addView(queryView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(icon(R.drawable.sym_close, palette.dim).apply {
                setOnClickListener {
                    query = ""
                    refreshSearch()
                }
            }, LayoutParams(dp(40), LayoutParams.MATCH_PARENT))
        }
        addView(searchBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { setMargins(dp(8), dp(6), dp(8), dp(2)) })
        searchViews += searchBar
        val resultScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(results)
        }
        addView(resultScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
        searchViews += resultScroll
        searchViews.forEach { it.visibility = GONE }
        buildTabs()
        // 最近使ったものがなければ、最初のグループから
        selectTab(if (recent().isEmpty()) 1 else 0)
    }

    /** 角の丸い入力欄の形の行（検索の欄） */
    private fun pill() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(22).toFloat()
            setColor(palette.key)
        }
    }

    private fun icon(id: Int, tint: Int) = ImageView(context).apply {
        setImageResource(id)
        setColorFilter(tint)
        scaleType = ImageView.ScaleType.CENTER
    }

    fun startSearch() {
        if (searching) return
        searching = true
        query = ""
        composing = ""
        candidates = null
        browseViews.forEach { it.visibility = GONE }
        searchViews.forEach { it.visibility = VISIBLE }
        refreshSearch()
        onSearchMode(true)
    }

    fun endSearch() {
        if (!searching) return
        searching = false
        searchViews.forEach { it.visibility = GONE }
        browseViews.forEach { it.visibility = VISIBLE }
        selectTab(tab)
        onSearchMode(false)
    }

    /** 画面キーボードで打った文字を検索の言葉に足す */
    fun typeSearch(c: Char) {
        query += c
        refreshSearch()
    }

    fun setSearchComposing(text: String) {
        composing = text
        refreshSearch()
    }

    fun commitSearch(text: String) {
        query += text
        composing = ""
        candidates = null
        refreshSearch()
    }

    fun showSearchCandidates(list: List<String>, selected: Int) {
        candidates = list
        selectedCandidate = selected
        refreshSearch()
    }

    fun hideSearchCandidates() {
        if (candidates == null) return
        candidates = null
        refreshSearch()
    }

    fun backspaceSearch() {
        if (query.isEmpty()) return
        query = query.dropLast(1)
        refreshSearch()
    }

    private fun refreshSearch() {
        val full = query + composing
        val kana = EmojiPanelData.toKana(full.lowercase())
        // 直接入力のローマ字は、かなにしたものも並べて見せる（どちらでも探している）。変換中の文字には下線
        queryView.text = when {
            full.isEmpty() -> context.getString(R.string.ime_emoji_search_hint)
            composing.isEmpty() && kana != full.lowercase() && kana.none { it in 'a'..'z' } -> context.getString(R.string.ime_emoji_search_query_with_kana, full, kana)
            else -> android.text.SpannableString(full).apply {
                if (composing.isNotEmpty()) setSpan(android.text.style.UnderlineSpan(), query.length, full.length, 0)
            }
        }
        queryView.setTextColor(if (full.isEmpty()) palette.dim else palette.text)
        results.removeAllViews()
        // 変換中は、結果の行に変換の候補を並べる
        candidates?.let { list ->
            list.forEachIndexed { i, c ->
                results.addView(TextView(context).apply {
                    this.text = c
                    gravity = Gravity.CENTER
                    setTextColor(palette.text)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setPadding(dp(14), 0, dp(14), 0)
                    if (i == selectedCandidate) background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(palette.modOnce) }
                    setOnClickListener { onSearchCandidate(i) }
                }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
            }
            return
        }
        val found = data.search(full).filter { glyphPaint.hasGlyph(it) }
        if (query.isNotEmpty() && found.isEmpty()) {
            results.addView(TextView(context).apply {
                text = context.getString(R.string.ime_emoji_not_found)
                setTextColor(palette.dim)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(16), 0)
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        for (e in found) {
            results.addView(TextView(context).apply {
                text = e
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setOnClickListener {
                    onEmoji(e)
                    remember(e)
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
            }, LayoutParams(dp(52), LayoutParams.MATCH_PARENT))
        }
    }

    private fun tabLabels(): List<String> = listOf("") + listOf("😀", "👋", "🐻", "🍔", "✈️", "⚽", "💡", "🔣", "🏁").take(data.groups.size)

    private fun buildTabs() {
        tabBar.removeAllViews()
        tabLabels().forEachIndexed { i, label ->
            val view: View = if (i == 0) {
                ImageView(context).apply {
                    setImageResource(R.drawable.sym_schedule)
                    setColorFilter(palette.text)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                }
            } else {
                TextView(context).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                }
            }
            view.contentDescription = if (i == 0) context.getString(R.string.ime_emoji_recent) else data.groups.getOrNull(i - 1)?.name
            view.setOnClickListener { selectTab(i) }
            tabBar.addView(view, LayoutParams(dp(52), LayoutParams.MATCH_PARENT))
        }
    }

    private fun selectTab(i: Int) {
        tab = i
        for (j in 0 until tabBar.childCount) {
            tabBar.getChildAt(j).background = if (j == i) {
                GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(palette.modOnce) }
            } else {
                null
            }
        }
        shown = if (i == 0) recent() else emojisOf(i - 1)
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        grid.setSelection(0)
    }

    private fun emojisOf(group: Int): List<String> {
        val g = data.groups.getOrNull(group) ?: return emptyList()
        return drawable.getOrPut(g.name) { g.emojis.filter { glyphPaint.hasGlyph(it) } }
    }

    private fun recent(): List<String> = prefs.getString(KEY_RECENT, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    private fun remember(e: String) {
        val list = (listOf(e) + recent().filter { it != e }).take(MAX_RECENT)
        prefs.edit().putString(KEY_RECENT, list.joinToString("\n")).apply()
    }

    private fun bottomRow() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(dp(3), dp(3), dp(3), dp(3))
        backLabel.apply {
            text = "ABC"
            gravity = Gravity.CENTER
            setTextColor(palette.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = keyBackground(palette.functionKey)
            setOnClickListener { onClose() }
        }
        addView(backLabel, LayoutParams(0, LayoutParams.MATCH_PARENT, 1.5f).withMargin())
        addView(iconKey(R.drawable.sym_space_bar, palette.key) { onSpace() }, LayoutParams(0, LayoutParams.MATCH_PARENT, 5f).withMargin())
        addView(repeatingKey(R.drawable.sym_backspace, palette.functionKey, onBackspace), LayoutParams(0, LayoutParams.MATCH_PARENT, 1.5f).withMargin())
    }

    private fun LayoutParams.withMargin() = apply { setMargins(dp(3), 0, dp(3), 0) }

    private fun keyBackground(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(color)
    }

    private fun iconKey(icon: Int, color: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(palette.text)
        scaleType = ImageView.ScaleType.CENTER
        background = keyBackground(color)
        setOnClickListener { onClick() }
    }

    /** 押し続けると繰り返すキー（Backspace） */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeatingKey(icon: Int, color: Int, action: () -> Unit) = iconKey(icon, color) {}.apply {
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 50)
            }
        }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    action()
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    handler.postDelayed(tick, 400)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(tick)
            }
            true
        }
    }

    private inner class EmojiAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView as? TextView ?: TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
            }
            v.text = shown[position]
            return v
        }
    }

    private companion object {
        const val KEY_RECENT = "emoji_recent"
        const val MAX_RECENT = 40
    }
}
