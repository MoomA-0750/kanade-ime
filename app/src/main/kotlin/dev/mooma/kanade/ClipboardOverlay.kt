package dev.mooma.kanade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * 物理キーボードのときのクリップボード。画面キーボードと同じ [ClipboardPanelView] を、候補の窓と同じ「重ねる窓」に出す
 * （物理キーボードをつないだ Galaxy では IME の窓が出ないことがあるため。[OverlayBar] と同じ理由）。
 * フォーカスは取らないので、キーは IME に届いたまま。選ぶ・貼る・閉じるのキーは IME から [panel] を動かす。
 * 指でも画面キーボードのときと同じに使える（押して貼り付け、長押しでピン留め・編集・切り抜き・削除）。
 * 下のつまみで動かせ、右下の角で大きさを変えられる（[settings] に残す）。つまみを2回たたくと、カーソルの近く・自動の大きさに戻る
 *
 * [context] は IME の窓の Context（DeX では IME がいる画面に出すため）。
 */
class ClipboardOverlay(
    private val context: Context,
    private val anchor: CursorAnchor,
    val panel: ClipboardPanelView,
    private val settings: KanadeSettings,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val handle = Handle()
    private val frame = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(KeyboardPalette.of(context).background)
            cornerRadius = 16 * density
        }
        clipToOutline = true
        elevation = 8 * density
        addView(panel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(handle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(HANDLE_DP)))
    }
    private var added = false
    private val params = WindowManager.LayoutParams(
        0, 0,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // フォーカスは取らず（入力先のアプリのまま）、窓の中の指の操作だけ受ける。外の操作は後ろのアプリへ
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "Kanade clipboard"
    }

    val isShowing: Boolean get() = added

    /**
     * 出す。動かしたことがあればその位置、なければカーソルの近く（入らなければ上、カーソルが分からなければ画面下の中央）。
     * 大きさを変えたことがあればその大きさ、なければ画面キーボードのパネルと同じくらいの幅で、高さは中身に合わせる
     */
    fun show() {
        val screen = wm.currentWindowMetrics.bounds
        val w = settings.clipboardOverlayWidth.takeIf { it > 0 }?.let { clampWidth(it) }
            ?: minOf(screen.width() - dp(16), dp(720))
        panel.contentWidth = w
        panel.keyboardMode = true
        panel.refresh()
        val h = settings.clipboardOverlayHeight.takeIf { it > 0 }?.let { clampHeight(it) }
            ?: minOf(minOf((screen.height() * 0.55f).toInt(), dp(340)), panel.contentHeight(w) + dp(HANDLE_DP))
        val savedX = settings.clipboardOverlayX
        val savedY = settings.clipboardOverlayY
        val (x, y) = if (savedX >= 0 && savedY >= 0) savedX to savedY else anchor.place(context, wm, w, h, anchor.rect(0))
        params.width = w
        params.height = h
        params.x = x.coerceIn(0, maxOf(0, screen.width() - w))
        params.y = y.coerceIn(0, maxOf(0, screen.height() - h))
        if (!added) {
            wm.addView(frame, params)
            added = true
        } else {
            wm.updateViewLayout(frame, params)
        }
    }

    fun hide() {
        if (added) {
            wm.removeViewImmediate(frame)
            added = false
        }
    }

    private fun clampWidth(w: Int) = w.coerceIn(dp(MIN_WIDTH_DP), maxOf(dp(MIN_WIDTH_DP), wm.currentWindowMetrics.bounds.width()))
    private fun clampHeight(h: Int) = h.coerceIn(dp(MIN_HEIGHT_DP), maxOf(dp(MIN_HEIGHT_DP), wm.currentWindowMetrics.bounds.height()))

    private fun dp(v: Int) = (v * density).toInt()

    /** パネルの下のつまみ。真ん中をつまんで動かし、右下の角で大きさを変える。2回たたくと元に戻す */
    private inner class Handle : View(context) {
        private val palette = KeyboardPalette.of(context)
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.dim
            strokeWidth = 1.5f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val gripZone get() = 48 * density

        private var mode = 0 // 0: なし 1: 移動 2: 大きさ
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0
        private var startW = 0
        private var startH = 0
        private var lastUpAt = 0L
        private var moved = false

        init {
            setBackgroundColor(palette.background)
        }

        override fun onDraw(canvas: Canvas) {
            val cy = height / 2f
            // 真ん中の棒（動かす）
            val bw = 36 * density
            val bh = 4 * density
            canvas.drawRoundRect(RectF(width / 2f - bw / 2, cy - bh / 2, width / 2f + bw / 2, cy + bh / 2), bh / 2, bh / 2, ink.apply { style = Paint.Style.FILL })
            // 右下の角の斜線（大きさを変える）
            ink.style = Paint.Style.STROKE
            val r = width - 10 * density
            val b = height - 6 * density
            for (i in 1..3) {
                val d = i * 4 * density
                canvas.drawLine(r - d, b, r, b - d, ink)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mode = if (e.x > width - gripZone) 2 else 1
                    moved = false
                    startRawX = e.rawX
                    startRawY = e.rawY
                    startX = params.x
                    startY = params.y
                    startW = params.width
                    startH = params.height
                    // つまみを触ったら、つかんだ手応えを返す（画面キーボードのフロートと同じ）
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - startRawX).toInt()
                    val dy = (e.rawY - startRawY).toInt()
                    if (!moved && kotlin.math.hypot(dx.toFloat(), dy.toFloat()) < 4 * density) return true
                    moved = true
                    val screen = wm.currentWindowMetrics.bounds
                    if (mode == 1) {
                        params.x = (startX + dx).coerceIn(0, maxOf(0, screen.width() - params.width))
                        params.y = (startY + dy).coerceIn(0, maxOf(0, screen.height() - params.height))
                    } else {
                        // 左上は動かさず、右下を指に合わせる（画面の外には出さない）
                        params.width = clampWidth(startW + dx).coerceAtMost(screen.width() - params.x)
                        params.height = clampHeight(startH + dy).coerceAtMost(screen.height() - params.y)
                    }
                    if (added) wm.updateViewLayout(frame, params)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        if (mode == 1) {
                            settings.clipboardOverlayX = params.x
                            settings.clipboardOverlayY = params.y
                        } else {
                            settings.clipboardOverlayWidth = params.width
                            settings.clipboardOverlayHeight = params.height
                            // 幅が変わったら、カードの列と幅を並べ直す
                            panel.contentWidth = params.width
                            panel.refresh()
                        }
                    } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastUpAt < DOUBLE_TAP_MS) {
                            // 2回たたいたら、カーソルの近く・自動の大きさに戻す
                            settings.clipboardOverlayX = -1
                            settings.clipboardOverlayY = -1
                            settings.clipboardOverlayWidth = 0
                            settings.clipboardOverlayHeight = 0
                            performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                            show()
                            lastUpAt = 0
                        } else {
                            lastUpAt = now
                        }
                    }
                    mode = 0
                }
            }
            return true
        }
    }

    companion object {
        const val HANDLE_DP = 22
        const val MIN_WIDTH_DP = 280
        const val MIN_HEIGHT_DP = 180
        const val DOUBLE_TAP_MS = 350L

        fun permitted(context: Context) = OverlayBar.permitted(context)
    }
}
