package dev.mooma.kanade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout

/**
 * IME の画面のいちばん外側。ふだんは画面の下いっぱいにキーボードを置き、フロートのときは
 * 窓を画面いっぱいに広げて（透明）、その中の好きな位置・幅にキーボードを浮かせる。
 * 浮いているキーボードの外は触っても下のアプリに届く（[touchableRect] を IME の当たり判定にする）。
 * フロート中はキーボードの下につまみを出し、真ん中をドラッグで移動、右端のドラッグで幅を変え、左端で固定に戻す。
 */
@SuppressLint("ViewConstructor")
class FloatingFrame(
    context: Context,
    private val content: View,
    /** 位置・幅を動かし終えた（保存する） */
    private val onMoved: (x: Float, y: Float, width: Float) -> Unit,
    /** 固定（画面の下いっぱい）に戻す */
    private val onDock: () -> Unit,
) : ViewGroup(context) {
    private val density = resources.displayMetrics.density
    private val handle = Handle()
    private val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(handle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (HANDLE_DP * density).toInt()))
    }

    var floating = false
        set(value) {
            if (field == value) return
            field = value
            handle.visibility = if (value) VISIBLE else GONE
            card.clipToOutline = value
            card.elevation = if (value) 8 * density else 0f
            requestLayout()
        }

    /** 浮かせる位置（空いている範囲の中の割合。0 が左・上、1 が右・下） */
    var posX = 0.5f
    var posY = 1f

    /** 幅（画面の幅に対する割合）。0 以下なら画面の広さに合わせて決める */
    var widthFraction = 0f

    /** 浮いているキーボードの、窓の中の位置（IME の当たり判定に使う） */
    val touchableRect = Rect()

    /** 画面の上下で避ける高さ（ステータスバー・ナビゲーションバー） */
    private var safeTop = 0
    private var safeBottom = 0

    init {
        handle.visibility = GONE
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 16 * density)
            }
        }
        addView(card)
        setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            if (bars.top != safeTop || bars.bottom != safeBottom) {
                safeTop = bars.top
                safeBottom = bars.bottom
                if (floating) requestLayout()
            }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    private fun cardWidth(total: Int): Int {
        val f = widthFraction.takeIf { it > 0f } ?: if (ScreenWidth.isWide(context, total)) 0.5f else 0.85f
        return (total * f).toInt().coerceIn(minOf(total, (MIN_WIDTH_DP * density).toInt()), total)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (!floating) {
            card.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            setMeasuredDimension(w, card.measuredHeight)
            return
        }
        // 窓を画面いっぱいにして、その中に浮かせる
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) resources.displayMetrics.heightPixels
        else MeasureSpec.getSize(heightMeasureSpec)
        card.measure(MeasureSpec.makeMeasureSpec(cardWidth(w), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        if (!floating) {
            card.layout(0, 0, w, card.measuredHeight)
        } else {
            val cw = card.measuredWidth
            val ch = card.measuredHeight
            val freeH = maxOf(0, h - safeTop - safeBottom - ch)
            val x = ((w - cw) * posX.coerceIn(0f, 1f)).toInt()
            val y = safeTop + (freeH * posY.coerceIn(0f, 1f)).toInt()
            card.layout(x, y, x + cw, y + ch)
        }
        val loc = IntArray(2)
        getLocationInWindow(loc)
        touchableRect.set(loc[0] + card.left, loc[1] + card.top, loc[0] + card.right, loc[1] + card.bottom)
    }

    /** 画面上での浮いているキーボードの上端（フロートでなければ null） */
    fun floatingTopOnScreen(): Int? {
        if (!floating || !card.isShown) return null
        val loc = IntArray(2)
        card.getLocationOnScreen(loc)
        return loc[1]
    }

    /** キーボードの下のつまみ。左: 固定に戻す、真ん中: 動かす、右: 幅を変える */
    private inner class Handle : View(context) {
        private val palette = KeyboardPalette.of(context)
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.dim }
        private val dock = context.getDrawable(R.drawable.sym_dock_to_bottom)!!.mutate().apply { setTint(palette.dim) }
        private val resize = context.getDrawable(R.drawable.sym_width)!!.mutate().apply { setTint(palette.dim) }
        private val iconZone get() = 48 * density

        private var mode = 0 // 0: なし 1: 移動 2: 幅 3: 固定に戻す
        private var lastX = 0f
        private var lastY = 0f

        init {
            setBackgroundColor(palette.background)
        }

        override fun onDraw(canvas: Canvas) {
            val s = (18 * density).toInt()
            val cy = height / 2
            dock.setBounds((iconZone / 2).toInt() - s / 2, cy - s / 2, (iconZone / 2).toInt() + s / 2, cy + s / 2)
            dock.draw(canvas)
            resize.setBounds((width - iconZone / 2).toInt() - s / 2, cy - s / 2, (width - iconZone / 2).toInt() + s / 2, cy + s / 2)
            resize.draw(canvas)
            val bw = 36 * density
            val bh = 4 * density
            canvas.drawRoundRect(RectF(width / 2f - bw / 2, cy - bh / 2, width / 2f + bw / 2, cy + bh / 2), bh / 2, bh / 2, bar)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val frame = this@FloatingFrame
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mode = when {
                        e.x < iconZone -> 3
                        e.x > width - iconZone -> 2
                        else -> 1
                    }
                    lastX = e.rawX
                    lastY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - lastX
                    val dy = e.rawY - lastY
                    lastX = e.rawX
                    lastY = e.rawY
                    val freeW = frame.width - card.width
                    val freeH = frame.height - safeTop - safeBottom - card.height
                    when (mode) {
                        1 -> {
                            if (freeW > 0) posX = (posX + dx / freeW).coerceIn(0f, 1f)
                            if (freeH > 0) posY = (posY + dy / freeH).coerceIn(0f, 1f)
                            frame.requestLayout()
                        }
                        2 -> {
                            // 左端を動かさずに右端を伸び縮みさせる
                            val left = card.left
                            val newW = (card.width + dx).coerceIn(MIN_WIDTH_DP * density, frame.width.toFloat())
                            widthFraction = newW / frame.width
                            val free = frame.width - newW
                            posX = if (free > 0) (left / free).coerceIn(0f, 1f) else 0.5f
                            frame.requestLayout()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    when (mode) {
                        1, 2 -> onMoved(posX, posY, widthFraction)
                        3 -> if (e.x < iconZone) onDock()
                    }
                    mode = 0
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (mode == 1 || mode == 2) onMoved(posX, posY, widthFraction)
                    mode = 0
                }
            }
            return true
        }
    }

    private companion object {
        const val HANDLE_DP = 28
        const val MIN_WIDTH_DP = 260
    }
}
