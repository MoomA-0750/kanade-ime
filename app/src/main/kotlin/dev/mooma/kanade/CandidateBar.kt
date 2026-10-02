package dev.mooma.kanade

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/**
 * 画面下に出す1行の帯。変換中は候補（番号付き）を、未確定文字列を出せないアプリでは入力中の文字を出す。
 * 段階3の最小の表示で、カーソルの近くに出すのは後で試す。
 */
class CandidateBar(context: Context) : TextView(context) {
    private val dark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    private val fg = if (dark) Color.rgb(0xEE, 0xEE, 0xEE) else Color.rgb(0x22, 0x22, 0x22)
    private val dim = if (dark) Color.rgb(0x99, 0x99, 0x99) else Color.rgb(0x77, 0x77, 0x77)
    private val selectedBg = if (dark) Color.rgb(0x2E, 0x5C, 0x8A) else Color.rgb(0xC8, 0xDD, 0xF5)

    init {
        setBackgroundColor(if (dark) Color.rgb(0x20, 0x20, 0x22) else Color.rgb(0xF4, 0xF4, 0xF6))
        setTextColor(fg)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        gravity = Gravity.CENTER_VERTICAL
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
    }

    /** 候補の一覧。選んでいるものの周りの最大9件を「1 橋」の形で並べる */
    fun showCandidates(candidates: List<String>, selected: Int, preview: String? = null) {
        val page = selected / PAGE
        val from = page * PAGE
        val sb = SpannableStringBuilder()
        if (preview != null) appendPreview(sb, preview)
        for (i in from until minOf(from + PAGE, candidates.size)) {
            val start = sb.length
            sb.append("${i - from + 1}").setSpan(ForegroundColorSpan(dim), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(' ')
            val wordStart = sb.length
            sb.append(candidates[i])
            if (i == selected) {
                sb.setSpan(BackgroundColorSpan(selectedBg), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), wordStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append("   ")
        }
        if (candidates.size > PAGE) {
            val start = sb.length
            sb.append("${selected + 1}/${candidates.size}")
            sb.setSpan(ForegroundColorSpan(dim), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text = sb
    }

    /** 未確定文字列の代わりに、入力中の文字だけを出す */
    fun showPreview(preview: String) {
        text = SpannableStringBuilder().also { appendPreview(it, preview) }
    }

    private fun appendPreview(sb: SpannableStringBuilder, preview: String) {
        val start = sb.length
        sb.append(preview)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("    ")
    }

    companion object {
        /** 数字キーで選べる数（エンジンと同じ） */
        const val PAGE = dev.mooma.kanade.core.InputEngine.PAGE
    }
}
