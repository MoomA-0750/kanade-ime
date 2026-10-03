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
 * 画面キーボードの絵文字パネル。上にグループのタブ（最近使ったもの＋Unicode のグループ）、真ん中に絵文字の一覧、
 * 下にキーボードへ戻る・空白・Backspace。色は画面キーボードと同じ（Material 3）。
 */
@SuppressLint("ViewConstructor")
class EmojiPanelView(
    context: Context,
    private val onEmoji: (String) -> Unit,
    private val onBackspace: () -> Unit,
    private val onSpace: () -> Unit,
    private val onClose: () -> Unit,
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

    private fun dp(v: Int) = (v * density).toInt()

    init {
        orientation = VERTICAL
        setBackgroundColor(palette.background)
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabBar)
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
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
        addView(bottomRow(), LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
        buildTabs()
        // 最近使ったものがなければ、最初のグループから
        selectTab(if (recent().isEmpty()) 1 else 0)
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
            view.contentDescription = if (i == 0) "最近使った絵文字" else data.groups.getOrNull(i - 1)?.name
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
