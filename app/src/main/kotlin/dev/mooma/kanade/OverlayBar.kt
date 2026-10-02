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

    /** カーソル（未確定文字列の先頭）の画面上の位置。分からなければ null で、画面下の中央に出す */
    private var anchor: RectF? = null

    val isShowing: Boolean get() = added

    fun show(update: (CandidateBar) -> Unit) {
        update(bar)
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        place(bar.measuredWidth, bar.measuredHeight)
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

    /** アプリから届いたカーソルの位置。未確定文字列の先頭の文字の枠があればそれ、なければ挿入位置を使う */
    fun updateAnchor(info: CursorAnchorInfo?) {
        if (info == null) {
            anchor = null
            return
        }
        val local = info.composingTextStart.takeIf { it >= 0 }?.let { info.getCharacterBounds(it) }
            ?: info.insertionMarkerHorizontal.takeIf { !it.isNaN() }?.let {
                RectF(it, info.insertionMarkerTop, it, info.insertionMarkerBottom)
            }
        anchor = local?.let { RectF(it).also { r -> Matrix(info.matrix).mapRect(r) } }
    }

    fun clearAnchor() {
        anchor = null
    }

    private fun place(w: Int, h: Int) {
        val screen = wm.currentWindowMetrics.bounds
        val a = anchor
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
