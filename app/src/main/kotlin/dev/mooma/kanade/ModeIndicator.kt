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
 * 日本語オン/オフを切り替えたとき、「あ」「A」を画面の中央下に少しだけ出す（重ねる窓の権限があるとき）。
 * カーソルの近くは文字に重なるので避け、目に入りやすい中央下に置く。
 */
class ModeIndicator(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val view = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        gravity = Gravity.CENTER
        val size = (64 * density).toInt()
        minWidth = size
        minHeight = size
        background = GradientDrawable().apply {
            cornerRadius = 16 * density
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
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = (120 * density).toInt()
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
        handler.removeCallbacks(hide)
        if (!added) {
            wm.addView(view, params)
            added = true
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
