package dev.mooma.kanade

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 日本語オン/オフを切り替えたとき、「あ」「A」をカーソルの近く（候補の窓と同じ位置）に少しだけ出す
 * （重ねる窓の権限があるとき）。カーソルの位置が分からなければ画面の中央下。
 */
class ModeIndicator(private val context: Context, private val anchor: CursorAnchor) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val view = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        gravity = Gravity.CENTER
        val size = (44 * density).toInt()
        minWidth = size
        minHeight = size
        background = GradientDrawable().apply {
            cornerRadius = 10 * density
            setColor(Color.argb(0xCC, 0x20, 0x20, 0x24))
        }
    }
    private var added = false
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Kanade mode"
    }
    private val hide = Runnable {
        if (added) {
            wm.removeViewImmediate(view)
            added = false
        }
    }

    fun show(japanese: Boolean) {
        view.text = if (japanese) "あ" else "A"
        view.measure(0, 0)
        val (x, y) = anchor.place(context, wm, view.measuredWidth, view.measuredHeight, anchor.rect())
        params.x = x
        params.y = y
        handler.removeCallbacks(hide)
        if (!added) {
            wm.addView(view, params)
            added = true
        } else {
            wm.updateViewLayout(view, params)
        }
        handler.postDelayed(hide, SHOW_MS)
    }

    fun dismiss() {
        handler.removeCallbacks(hide)
        hide.run()
    }

    private companion object {
        const val SHOW_MS = 700L
    }
}
