package dev.mooma.kanade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import dev.mooma.kanade.core.flick.FlickTables
import kotlin.math.abs
import kotlin.math.hypot

/**
 * フリック配列の画面キーボード（12キー、Gboard と同じ考え方）。かな・英字・数字を「あa1」のキーで切り替える。
 * 真ん中の 3×4 が文字のキー（押す・上下左右に払うで5文字。同じキーを続けて押すとトグル入力）、
 * 左の列は ↶（トグルを戻す）・◀・☺（押すと絵文字、左に払うとクリップボード、上に払うと音声入力）・あa1（上に払うと QWERTY へ）、
 * 右の列は ⌫・▶・空白（日本語入力中は変換）・⏎。
 * 広い画面（[ScreenWidth.isWide]）では、Gboard と同じく左右に分け、真ん中の文字のキーを両側に置く。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class FlickKeyboardView(
    context: Context,
    private val actions: Actions,
) : View(context) {
    /** キーを押したときに IME にしてもらうこと */
    interface Actions {
        /** 文字を入れる（日本語入力中なら入力中の文字に足す） */
        fun text(s: String)
        /** 直前の1文字を [s] に置き換える（トグル入力・濁点） */
        fun replaceLast(s: String)
        /** 直前の1文字（入力中の文字の最後、なければカーソルの前） */
        fun lastChar(): Char?
        /** 特別なキー（⌫・矢印・空白・⏎） */
        fun key(keyCode: Int)
        /** 日本語入力のオン・オフ（かなはオン、英字・数字はオフ） */
        fun setJapanese(on: Boolean)
        fun emoji()
        fun clipboard()
        fun voice(down: Boolean)
        fun switchToQwerty()
        /** 浮かせる・固定に戻す */
        fun toggleFloat()
    }

    enum class Layout(val table: List<FlickTables.Key>) { KANA(FlickTables.KANA), ALPHABET(FlickTables.ALPHABET), NUMBER(FlickTables.NUMBER) }

    var layout = Layout.KANA
        set(value) {
            field = value
            toggleKey = null
            invalidate()
        }

    /** トグル入力（同じキーを続けて押すと次の文字）も使うか。切るとフリックだけ */
    var toggleInput = true

    /** 英字を大文字で入れる（a/A のキー） */
    private var upper = false

    private sealed interface Slot {
        data class Chars(val index: Int) : Slot
        data class Fn(val id: String) : Slot
    }

    private class Cell(val slot: Slot, val rect: RectF)

    private val palette = KeyboardPalette.of(context)
    private val density = resources.displayMetrics.density
    private val cells = ArrayList<Cell>()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 20 * resources.displayMetrics.scaledDensity
    }
    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11 * resources.displayMetrics.scaledDensity
        color = palette.dim
    }
    private val icons = HashMap<Int, android.graphics.drawable.Drawable>()
    private val handler = Handler(Looper.getMainLooper())

    /** 押したときのピル型への変形（0: ふつうの角、1: ピル型）と、その動き（QWERTY と同じ） */
    private val pill = HashMap<Cell, Float>()
    private val pillAnimators = HashMap<Cell, android.animation.ValueAnimator>()

    private fun animatePill(cell: Cell, pressed: Boolean) {
        pillAnimators.remove(cell)?.cancel()
        val from = pill[cell] ?: 0f
        val to = if (pressed) 1f else 0f
        if (from == to) return
        pillAnimators[cell] = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = if (pressed) 90L else 280L
            interpolator = if (pressed) android.view.animation.DecelerateInterpolator() else android.view.animation.OvershootInterpolator(1.2f)
            addUpdateListener {
                val v = it.animatedValue as Float
                if (v <= 0f && !pressed) pill.remove(cell) else pill[cell] = v
                invalidate()
            }
            start()
        }
    }

    /** 高さの倍率（画面キーボードと同じ設定） */
    var heightPercent = 100
        set(value) {
            field = value
            requestLayout()
        }
    var extraBottomDp = 0
        set(value) {
            field = value
            requestLayout()
        }
    private var bottomInset = 0
    val bottomSpace: Int get() = if (floating) 0 else bottomInset + (extraBottomDp * density).toInt()

    /** 浮かせているとき（下の余白を取らない） */
    var floating = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** 日本語入力中か（「あa1」のキーの表示に使う。かなの配列のときだけ意味がある） */
    var japanese = true

    /** 聞き続けるモード中（☺ のキーの色を変える） */
    var voiceActive = false
        set(value) {
            field = value
            invalidate()
        }

    private class Touch(val cell: Cell, val x0: Float, val y0: Float) {
        var dir = FlickTables.Dir.TAP
        /** 最後の指の位置（斜めに払ったときの向きの紛らわしさを見る） */
        var lastX = x0
        var lastY = y0
        var repeated = false
        var voiceStarted = false
    }
    private val touches = HashMap<Int, Touch>()

    /** トグル入力: 最後に押した文字のキーと時刻、入れた文字 */
    private var toggleKey: Int? = null
    private var toggleAt = 0L
    private var toggleChar = ""

    private val rowHeight get() = 52 * density * heightPercent / 100
    private val gap = 3 * density
    private val swipe = 18 * density

    init {
        setOnApplyWindowInsetsListener { _, insets ->
            val t = android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.tappableElement()
            val bottom = insets.getInsets(t).bottom
            if (bottom != bottomInset) {
                bottomInset = bottom
                requestLayout()
            }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (4 * rowHeight + gap * 2).toInt() + bottomSpace)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutCells(w)

    /** キーの位置を決める。広い画面では左右に分ける */
    private fun layoutCells(w: Int) {
        pillAnimators.values.forEach { it.cancel() }
        pillAnimators.clear()
        pill.clear()
        cells.clear()
        val wide = ScreenWidth.isWide(context, w)
        val left = listOf("undo", "left", "emoji", "mode")
        val right = listOf("backspace", "right", "space", "enter")
        fun block(x0: Float, width: Float, withLeft: Boolean, withRight: Boolean) {
            val cols = 3 + (if (withLeft) 1 else 0) + (if (withRight) 1 else 0)
            val cw = width / cols
            for (r in 0 until 4) {
                var c = 0
                val top = gap + r * rowHeight
                fun add(slot: Slot) {
                    val x = x0 + c * cw
                    cells += Cell(slot, RectF(x + gap / 2, top + gap / 2, x + cw - gap / 2, top + rowHeight - gap / 2))
                    c++
                }
                if (withLeft) add(Slot.Fn(left[r]))
                for (i in 0 until 3) add(Slot.Chars(r * 3 + i))
                if (withRight) add(Slot.Fn(right[r]))
            }
        }
        if (wide) {
            // 左右に分ける（左手側: 左の列＋文字、右手側: 文字＋右の列）。真ん中は空ける
            val half = w * 0.42f
            block(gap, half, withLeft = true, withRight = false)
            block(w - half - gap, half, withLeft = false, withRight = true)
        } else {
            block(gap / 2, w - gap, withLeft = true, withRight = true)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
        for (cell in cells) drawCell(canvas, cell)
    }

    private fun drawCell(canvas: Canvas, cell: Cell) {
        val touch = touches.values.firstOrNull { it.cell === cell }
        val slot = cell.slot
        val fn = (slot as? Slot.Fn)?.id
        val (fill, ink) = when {
            touch != null -> palette.pressed to palette.text
            fn == "emoji" && voiceActive -> palette.voice to palette.onVoice
            fn == "enter" -> palette.accent to palette.onAccent
            fn != null -> palette.functionKey to palette.text
            isSpecialChars(slot) -> palette.functionKey to palette.text
            else -> palette.key to palette.text
        }
        keyPaint.color = fill
        // 押すとピル型に変わり、離すと弾むように戻る（画面キーボードの QWERTY と同じ）
        val base = 10 * density
        val r = base + (minOf(cell.rect.width(), cell.rect.height()) / 2 - base) * (pill[cell] ?: 0f).coerceAtLeast(0f)
        canvas.drawRoundRect(cell.rect, r, r, keyPaint)
        val cx = cell.rect.centerX()
        val cy = cell.rect.centerY()
        if (fn != null) {
            val icon = when (fn) {
                "undo" -> R.drawable.sym_undo
                "left" -> R.drawable.sym_arrow_back
                "right" -> R.drawable.sym_arrow_forward
                "emoji" -> R.drawable.sym_mood
                "backspace" -> R.drawable.sym_backspace
                "space" -> R.drawable.sym_space_bar
                "enter" -> R.drawable.sym_keyboard_return
                else -> null
            }
            if (icon != null) {
                drawIcon(canvas, icon, cx, cy, minOf(22 * density, cell.rect.height() * 0.45f), ink)
            } else {
                labelPaint.color = ink
                labelPaint.textSize = 15 * resources.displayMetrics.scaledDensity
                drawText(canvas, modeLabel(), cx, cy, labelPaint)
                labelPaint.textSize = 20 * resources.displayMetrics.scaledDensity
            }
            // 払うと使えるものの小さな目印
            if (fn == "emoji") {
                drawIcon(canvas, R.drawable.sym_mic, cx, cell.rect.top + 10 * density, 11 * density, palette.dim)
                drawIcon(canvas, R.drawable.sym_content_paste, cell.rect.left + 10 * density, cy, 11 * density, palette.dim)
            }
            if (fn == "mode") {
                drawIcon(canvas, R.drawable.sym_keyboard, cx, cell.rect.top + 10 * density, 11 * density, palette.dim)
                drawIcon(canvas, R.drawable.sym_picture_in_picture_alt, cx, cell.rect.bottom - 10 * density, 11 * density, palette.dim)
            }
            return
        }
        val key = layout.table[(slot as Slot.Chars).index]
        // 払っている間は、選んでいる文字を大きく出す
        if (touch != null && touch.dir != FlickTables.Dir.TAP) {
            labelPaint.color = palette.accent
            drawText(canvas, shown(key.char(touch.dir) ?: key.label), cx, cy, labelPaint)
            labelPaint.color = ink
            return
        }
        labelPaint.color = ink
        val label = if (layout == Layout.ALPHABET && upper && key.label.all { it.isLetter() }) key.label else shown(key.label)
        drawText(canvas, label, cx, cy, labelPaint)
        // 押している間は、上下左右の文字を小さく出す（どちらへ払えば何が出るか）
        if (touch != null) {
            val dx = cell.rect.width() * 0.32f
            val dy = cell.rect.height() * 0.32f
            key.char(FlickTables.Dir.LEFT)?.let { drawText(canvas, shown(it), cx - dx, cy, smallPaint) }
            key.char(FlickTables.Dir.UP)?.let { drawText(canvas, shown(it), cx, cy - dy, smallPaint) }
            key.char(FlickTables.Dir.RIGHT)?.let { drawText(canvas, shown(it), cx + dx, cy, smallPaint) }
            key.char(FlickTables.Dir.DOWN)?.let { drawText(canvas, shown(it), cx, cy + dy, smallPaint) }
        }
    }

    private fun isSpecialChars(slot: Slot) =
        slot is Slot.Chars && layout.table[slot.index].label.let { it == FlickTables.DAKUTEN || it == FlickTables.CASE }

    /** 英字の大文字の状態を表示にも反映する */
    private fun shown(s: String) = if (layout == Layout.ALPHABET && upper && s.length == 1 && s[0].isLetter()) s.uppercase() else s

    private fun modeLabel() = when (layout) {
        Layout.KANA -> "あa1"
        Layout.ALPHABET -> "aA1"
        Layout.NUMBER -> "1あa"
    }

    private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        canvas.drawText(text, x, y - (paint.descent() + paint.ascent()) / 2, paint)
    }

    private fun drawIcon(canvas: Canvas, id: Int, cx: Float, cy: Float, size: Float, tint: Int) {
        val d = icons.getOrPut(id) { context.getDrawable(id)!!.mutate() }
        val half = (size / 2).toInt()
        d.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        d.setTint(tint)
        d.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val i = event.actionIndex
        val id = event.getPointerId(i)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val cell = cells.firstOrNull { it.rect.contains(event.getX(i), event.getY(i)) } ?: return true
                val t = Touch(cell, event.getX(i), event.getY(i))
                touches[id] = t
                animatePill(cell, pressed = true)
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                scheduleRepeat(id, t)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until event.pointerCount) {
                    val t = touches[event.getPointerId(p)] ?: continue
                    val dir = direction(t, event.getX(p), event.getY(p))
                    if (dir != t.dir) {
                        t.dir = dir
                        handler.removeCallbacksAndMessages(event.getPointerId(p))
                        // ☺ を上に払ったら、その場で録音を始める（離すまで。短ければ聞き続けるモード）
                        if ((t.cell.slot as? Slot.Fn)?.id == "emoji" && dir == FlickTables.Dir.UP && !t.voiceStarted) {
                            t.voiceStarted = true
                            actions.voice(true)
                        }
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val t = touches.remove(id) ?: return true
                handler.removeCallbacksAndMessages(id)
                if (touches.values.none { it.cell === t.cell }) animatePill(t.cell, pressed = false)
                t.dir = direction(t, event.getX(i), event.getY(i))
                t.lastX = event.getX(i)
                t.lastY = event.getY(i)
                if (t.voiceStarted) actions.voice(false) else if (!t.repeated) fire(t)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                touches.keys.forEach { handler.removeCallbacksAndMessages(it) }
                if (touches.values.any { it.voiceStarted }) actions.voice(false)
                touches.values.forEach { animatePill(it.cell, pressed = false) }
                touches.clear()
                invalidate()
            }
        }
        return true
    }

    private fun direction(t: Touch, x: Float, y: Float): FlickTables.Dir {
        val dx = x - t.x0
        val dy = y - t.y0
        if (hypot(dx, dy) < swipe) return FlickTables.Dir.TAP
        return if (abs(dx) > abs(dy)) {
            if (dx < 0) FlickTables.Dir.LEFT else FlickTables.Dir.RIGHT
        } else {
            if (dy < 0) FlickTables.Dir.UP else FlickTables.Dir.DOWN
        }
    }

    /** ⌫・◀・▶ は長押しで繰り返す */
    private fun scheduleRepeat(id: Int, t: Touch) {
        val fn = (t.cell.slot as? Slot.Fn)?.id ?: return
        val code = when (fn) {
            "backspace" -> KeyEvent.KEYCODE_DEL
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> return
        }
        val tick = object : Runnable {
            override fun run() {
                if (touches[id] !== t || t.dir != FlickTables.Dir.TAP) return
                actions.key(code)
                t.repeated = true
                handler.postAtTime(this, id, android.os.SystemClock.uptimeMillis() + 50)
            }
        }
        handler.postAtTime(tick, id, android.os.SystemClock.uptimeMillis() + 400)
    }

    private fun fire(t: Touch) {
        when (val slot = t.cell.slot) {
            is Slot.Fn -> {
                if (slot.id != "undo") toggleKey = null
                when (slot.id) {
                    "undo" -> undoToggle()
                    "left" -> actions.key(KeyEvent.KEYCODE_DPAD_LEFT)
                    "right" -> actions.key(KeyEvent.KEYCODE_DPAD_RIGHT)
                    "backspace" -> actions.key(KeyEvent.KEYCODE_DEL)
                    "space" -> actions.key(KeyEvent.KEYCODE_SPACE)
                    "enter" -> actions.key(KeyEvent.KEYCODE_ENTER)
                    "emoji" -> if (t.dir == FlickTables.Dir.LEFT) actions.clipboard() else actions.emoji()
                    "mode" -> when (t.dir) {
                        FlickTables.Dir.UP -> actions.switchToQwerty()
                        FlickTables.Dir.DOWN -> actions.toggleFloat()
                        else -> nextLayout()
                    }
                }
            }
            is Slot.Chars -> correct(t, slot.index).let { (index, dir) -> typeKey(index, dir) }
        }
    }

    /**
     * タップの位置の補い（かなの配列）: 隣のキーとの境目を触った・斜めに払って向きがはっきりしないときに、
     * 入力中の読みのあとに来やすいかなを [tapCorrector] に選んでもらう。キーと向きを返す
     */
    var tapCorrector: ((List<dev.mooma.kanade.core.touch.TapCorrection.Option>) -> Int)? = null

    private fun correct(t: Touch, index: Int): Pair<Int, FlickTables.Dir> {
        val keep = index to t.dir
        val corrector = tapCorrector ?: return keep
        if (layout != Layout.KANA) return keep
        // トグル入力で同じキーを続けて押しているところは補わない
        if (toggleInput && t.dir == FlickTables.Dir.TAP && toggleKey == index) return keep
        val key = layout.table[index]
        val base = key.char(t.dir)?.singleOrNull() ?: return keep
        val options = ArrayList<Pair<Pair<Int, FlickTables.Dir>, dev.mooma.kanade.core.touch.TapCorrection.Option>>()
        val rect = t.cell.rect
        fun distance(r: RectF) = hypot((t.x0 - r.centerX()) / r.width(), (t.y0 - r.centerY()) / r.height())
        if (t.dir == FlickTables.Dir.TAP) {
            options += keep to dev.mooma.kanade.core.touch.TapCorrection.Option(base, distance(rect))
            // 近くの文字のキーの、同じ向き（タップ）の文字
            for (cell in cells) {
                val slot = cell.slot as? Slot.Chars ?: continue
                if (slot.index == index) continue
                val c = layout.table[slot.index].char(FlickTables.Dir.TAP)?.singleOrNull() ?: continue
                if (c !in 'ぁ'..'ゖ') continue
                val d = distance(cell.rect)
                if (d <= NEIGHBOR_RADIUS) options += (slot.index to FlickTables.Dir.TAP) to dev.mooma.kanade.core.touch.TapCorrection.Option(c, d)
            }
        } else {
            // 斜めに払ったとき: 横と縦の動きが近いほど、もう一方の向きとも紛らわしい
            val dx = abs(t.lastX - t.x0)
            val dy = abs(t.lastY - t.y0)
            val m = abs(dx - dy) / maxOf(1f, dx + dy)
            val other = when (t.dir) {
                FlickTables.Dir.LEFT, FlickTables.Dir.RIGHT -> if (t.lastY < t.y0) FlickTables.Dir.UP else FlickTables.Dir.DOWN
                else -> if (t.lastX < t.x0) FlickTables.Dir.LEFT else FlickTables.Dir.RIGHT
            }
            options += keep to dev.mooma.kanade.core.touch.TapCorrection.Option(base, 0.5f * (1 - m))
            val alt = key.char(other)?.singleOrNull()
            if (alt != null && alt in 'ぁ'..'ゖ') options += (index to other) to dev.mooma.kanade.core.touch.TapCorrection.Option(alt, 0.5f * (1 + m))
        }
        if (options.size < 2) return keep
        return options.getOrNull(corrector(options.map { it.second }))?.first ?: keep
    }

    private fun nextLayout() {
        layout = Layout.entries[(layout.ordinal + 1) % Layout.entries.size]
        actions.setJapanese(layout == Layout.KANA)
    }

    /** かなの配列にそろえる（IME が日本語入力をオンにしたとき） */
    fun syncJapanese(on: Boolean) {
        japanese = on
        if (on && layout != Layout.KANA) layout = Layout.KANA
        if (!on && layout == Layout.KANA) layout = Layout.ALPHABET
    }

    private fun typeKey(index: Int, dir: FlickTables.Dir) {
        val key = layout.table[index]
        when (key.label) {
            FlickTables.DAKUTEN -> {
                val last = actions.lastChar() ?: return
                FlickTables.nextDakuten(last)?.let { actions.replaceLast(it.toString()) }
                toggleKey = null
                return
            }
            FlickTables.CASE -> {
                upper = !upper
                toggleKey = null
                invalidate()
                return
            }
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (toggleInput && dir == FlickTables.Dir.TAP && toggleKey == index && now - toggleAt < TOGGLE_MS) {
            // 同じキーを続けて押した: 直前の文字を次の文字に入れ替える（トグル入力）
            val next = FlickTables.toggle(key, toggleChar.lowercase()) ?: return
            actions.replaceLast(shown(next))
            toggleChar = next
            toggleAt = now
            return
        }
        val c = key.char(dir) ?: return
        actions.text(shown(c))
        toggleKey = if (dir == FlickTables.Dir.TAP) index else null
        toggleChar = c
        toggleAt = now
    }

    /** ↶: トグル入力を1つ戻す */
    private fun undoToggle() {
        val index = toggleKey ?: return
        val prev = FlickTables.toggle(layout.table[index], toggleChar, forward = false) ?: return
        actions.replaceLast(shown(prev))
        toggleChar = prev
        toggleAt = android.os.SystemClock.uptimeMillis()
    }

    private companion object {
        const val TOGGLE_MS = 1000L
        /** タップの補いで候補にする近くのキー（中心までの距離。キーの大きさを 1 として） */
        const val NEIGHBOR_RADIUS = 1.1f
    }
}
