package dev.mooma.kanade

import android.content.Context
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RectF
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo

/**
 * 候補の帯を、IME の窓ではなく「他のアプリの上に重ねる」窓に出す。
 * IME の窓は表示をシステム（端末の作り）が決めるので、物理キーボードをつないだ Galaxy では出ないことがある。
 * 重ねる窓なら確実に出せ、カーソルの近くにも置ける。権限（SYSTEM_ALERT_WINDOW）が要る。
 *
 * [context] は IME の窓の Context（DeX では IME がいる画面に出すため）。
 */
class OverlayBar(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val bar = CandidateBar(context)
    private var added = false
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Kanade candidates"
    }

    /** アプリから届いた最後のカーソル情報。分からなければ null で、画面下の中央に出す */
    private var info: CursorAnchorInfo? = null

    val isShowing: Boolean get() = added

    /** [anchorOffset] は、帯を合わせる文字が未確定文字列の何文字目か（変換中は選んでいる文節の先頭） */
    fun show(anchorOffset: Int = 0, update: (CandidateBar) -> Unit) {
        update(bar)
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        place(bar.measuredWidth, bar.measuredHeight, anchorRect(anchorOffset))
        if (!added) {
            wm.addView(bar, params)
            added = true
        } else {
            wm.updateViewLayout(bar, params)
        }
    }

    fun hide() {
        if (added) {
            wm.removeViewImmediate(bar)
            added = false
        }
    }

    fun updateAnchor(info: CursorAnchorInfo?) {
        this.info = info
    }

    fun clearAnchor() {
        info = null
    }

    /** 未確定文字列の [offset] 文字目の枠（画面の座標）。なければ挿入位置 */
    private fun anchorRect(offset: Int): RectF? {
        val i = info ?: return null
        val local = i.composingTextStart.takeIf { it >= 0 }?.let { i.getCharacterBounds(it + offset) ?: i.getCharacterBounds(it) }
            ?: i.insertionMarkerHorizontal.takeIf { !it.isNaN() }?.let {
                RectF(it, i.insertionMarkerTop, it, i.insertionMarkerBottom)
            }
        return local?.let { RectF(it).also { r -> Matrix(i.matrix).mapRect(r) } }
    }

    private fun place(w: Int, h: Int, a: RectF?) {
        val screen = wm.currentWindowMetrics.bounds
        val gap = (4 * context.resources.displayMetrics.density).toInt()
        if (a != null) {
            params.x = a.left.toInt().coerceIn(0, maxOf(0, screen.width() - w))
            // 行の下に出し、はみ出すなら行の上に出す
            val below = a.bottom.toInt() + gap
            params.y = if (below + h <= screen.height()) below else maxOf(0, a.top.toInt() - h - gap)
        } else {
            params.x = maxOf(0, (screen.width() - w) / 2)
            params.y = screen.height() - h - (48 * context.resources.displayMetrics.density).toInt()
        }
    }

    private fun maxWidth(): Int {
        val screen = wm.currentWindowMetrics.bounds
        return minOf(screen.width(), (720 * context.resources.displayMetrics.density).toInt())
    }

    companion object {
        fun permitted(context: Context) = Settings.canDrawOverlays(context)
    }
}
