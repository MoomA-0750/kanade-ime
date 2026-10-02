package dev.mooma.kanade

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 候補の帯を、IME の窓ではなく「他のアプリの上に重ねる」窓に出す。
 * IME の窓は表示をシステム（端末の作り）が決めるので、物理キーボードをつないだ Galaxy では出ないことがある。
 * 重ねる窓なら確実に出せ、カーソルの近くにも置ける。権限（SYSTEM_ALERT_WINDOW）が要る。
 *
 * [context] は IME の窓の Context（DeX では IME がいる画面に出すため）。
 */
class OverlayBar(private val context: Context, private val anchor: CursorAnchor, onSelect: (Int) -> Unit) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val bar = CandidateBar(context, onSelect)
    private var added = false
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // フォーカスは取らず（入力先のアプリのまま）、窓の中の指の操作だけ受ける。外の操作は後ろのアプリへ
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Kanade candidates"
    }

    val isShowing: Boolean get() = added

    /** [anchorOffset] は、帯を合わせる文字が未確定文字列の何文字目か（変換中は選んでいる文節の先頭） */
    fun show(anchorOffset: Int = 0, update: (CandidateBar) -> Unit) {
        update(bar)
        bar.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(wm.currentWindowMetrics.bounds.height(), View.MeasureSpec.AT_MOST),
        )
        params.width = bar.measuredWidth
        val (x, y) = anchor.place(context, wm, bar.measuredWidth, bar.measuredHeight, anchor.rect(anchorOffset))
        params.x = x
        params.y = y
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

    private fun maxWidth(): Int {
        val screen = wm.currentWindowMetrics.bounds
        return minOf(screen.width(), (480 * context.resources.displayMetrics.density).toInt())
    }

    companion object {
        fun permitted(context: Context) = Settings.canDrawOverlays(context)
    }
}
