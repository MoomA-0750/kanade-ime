package dev.mooma.kanade

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 候補の窓。変換中は候補を縦に並べ（MS-IME と同じ形）、選んでいる候補が見えるように自動でスクロールする。
 * 指でスクロールでき、触った候補を選べる。未確定文字列を出せないアプリでは、上の行に入力中の文字を出す。
 */
class CandidateBar(context: Context, private val onSelect: (Int) -> Unit = {}) : LinearLayout(context) {
    private val density = context.resources.displayMetrics.density
    private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val fg = if (dark) Color.rgb(0xEE, 0xEE, 0xEE) else Color.rgb(0x22, 0x22, 0x22)
    private val dim = if (dark) Color.rgb(0x99, 0x99, 0x99) else Color.rgb(0x88, 0x88, 0x88)
    private val selectedBg = if (dark) Color.rgb(0x2E, 0x5C, 0x8A) else Color.rgb(0xC8, 0xDD, 0xF5)
    private val bg = if (dark) Color.rgb(0x26, 0x26, 0x29) else Color.rgb(0xFA, 0xFA, 0xFC)

    private val preview = TextView(context).apply {
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setTypeface(typeface, Typeface.BOLD)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.START
        setPadding(dp(12), dp(6), dp(12), dp(6))
        visibility = View.GONE
    }
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = object : ScrollView(context) {
        // 9行ぶんより高くしない（それより多い候補はスクロールで見る）
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val max = rowHeight() * VISIBLE_ROWS
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
        }
    }.apply {
        isVerticalScrollBarEnabled = true
        addView(list)
    }
    private val footer = TextView(context).apply {
        setTextColor(dim)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.END
        setPadding(dp(12), dp(2), dp(12), dp(4))
    }

    private var shown: List<String> = emptyList()
    private val rows = ArrayList<TextView>()

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), if (dark) Color.rgb(0x44, 0x44, 0x48) else Color.rgb(0xD0, 0xD0, 0xD6))
        }
        clipToOutline = true
        elevation = dp(6).toFloat()
        minimumWidth = dp(160)
        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** 候補の一覧。数字は選んでいる候補がいる頁（9件ずつ）にだけ付ける（数字キーで選べるもの） */
    fun showCandidates(candidates: List<String>, selected: Int, preview: String? = null) {
        showPreviewLine(preview)
        if (candidates != shown) rebuild(candidates)
        val pageStart = selected / PAGE * PAGE
        for ((i, row) in rows.withIndex()) {
            val n = i - pageStart
            val label = if (n in 0 until PAGE) "${n + 1}" else " "
            row.text = "$label  ${candidates[i]}"
            row.setBackgroundColor(if (i == selected) selectedBg else Color.TRANSPARENT)
            row.setTypeface(null, if (i == selected) Typeface.BOLD else Typeface.NORMAL)
        }
        scroll.visibility = View.VISIBLE
        footer.visibility = View.VISIBLE
        footer.text = "${selected + 1}/${candidates.size}"
        // 選んでいる候補が見える位置までスクロールする
        scroll.post {
            val row = rows.getOrNull(selected) ?: return@post
            val top = row.top
            val bottom = row.bottom
            if (top < scroll.scrollY) scroll.scrollTo(0, top)
            else if (bottom > scroll.scrollY + scroll.height) scroll.scrollTo(0, bottom - scroll.height)
        }
    }

    /** 未確定文字列の代わりに、入力中の文字だけを出す */
    fun showPreview(text: String) {
        showPreviewLine(text)
        scroll.visibility = View.GONE
        footer.visibility = View.GONE
    }

    private fun showPreviewLine(text: String?) {
        preview.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) preview.text = text
    }

    private fun rebuild(candidates: List<String>) {
        shown = candidates
        list.removeAllViews()
        rows.clear()
        for (i in candidates.indices) {
            val row = TextView(context).apply {
                setTextColor(fg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), dp(5), dp(16), dp(5))
                minHeight = rowHeight()
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { onSelect(i) }
            }
            rows += row
            list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun rowHeight() = dp(36)

    private fun dp(v: Int) = (v * density).toInt()

    companion object {
        /** 数字キーで選べる数（エンジンと同じ） */
        const val PAGE = dev.mooma.kanade.core.InputEngine.PAGE
        const val VISIBLE_ROWS = 9
    }
}
