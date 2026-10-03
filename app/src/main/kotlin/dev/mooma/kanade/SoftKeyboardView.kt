package dev.mooma.kanade

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 物理キーボードがないとき用の画面キーボード（Unexpected Keyboard と同じ考え方の、自前の実装）。
 * - 1つのキーに、真ん中の文字と、四隅・上下左右へ払ったときの文字を持つ（数字や記号は払って出す）
 * - Shift・Ctrl・Alt・Fn は、押すと次の1打だけ効き、2回続けて押すと固定になる
 * - Esc・Tab・矢印・Home/End・PgUp/PgDn・Delete・F1〜F12 も出せる
 * 押したキーは [onKey]（キーコードと修飾）か [onToggleJapanese] で IME に渡し、物理キーボードと同じ流れで処理する。
 */
class SoftKeyboardView(
    context: Context,
    private val onKey: (keyCode: Int, meta: Int) -> Unit,
    private val onToggleJapanese: () -> Unit,
) : View(context) {

    /** 払う方向（上から時計回り） */
    enum class Dir { N, NE, E, SE, S, SW, W, NW }

    enum class Mod(val label: String, val meta: Int) {
        SHIFT("⇧", KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
        CTRL("Ctrl", KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
        ALT("Alt", KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
        FN("Fn", 0),
    }

    sealed interface Action {
        /** 印字できる文字（キーコードと Shift はキー配列から引く） */
        data class Text(val char: Char) : Action
        /** Esc・矢印などの特別なキー */
        data class Code(val keyCode: Int, val label: String, val repeat: Boolean = false) : Action
        data class Modifier(val mod: Mod) : Action
        data object ToggleJapanese : Action
    }

    class Key(val width: Float, val center: Action?, val corners: Map<Dir, Action> = emptyMap())

    private val rows: List<List<Key>> = layout()

    /** 修飾の状態: 次の1打だけ（ONCE）か、固定（LOCKED） */
    private enum class Latch { ONCE, LOCKED }
    private val mods = HashMap<Mod, Latch>()
    private var lastModTap: Pair<Mod, Long>? = null

    /** 日本語入力中か（あ/A のキーの表示） */
    var japanese = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val bg = if (dark) Color.rgb(0x1B, 0x1B, 0x1E) else Color.rgb(0xE4, 0xE5, 0xEA)
    private val keyBg = if (dark) Color.rgb(0x33, 0x33, 0x38) else Color.WHITE
    private val keyPressed = if (dark) Color.rgb(0x55, 0x55, 0x5C) else Color.rgb(0xC8, 0xCA, 0xD2)
    private val modOnce = if (dark) Color.rgb(0x2E, 0x5C, 0x8A) else Color.rgb(0xB8, 0xD4, 0xF5)
    private val modLocked = if (dark) Color.rgb(0x1F, 0x8A, 0x5A) else Color.rgb(0x9F, 0xE0, 0xBC)
    private val fg = if (dark) Color.rgb(0xEE, 0xEE, 0xEE) else Color.rgb(0x22, 0x22, 0x22)
    private val dim = if (dark) Color.rgb(0x9A, 0x9A, 0xA0) else Color.rgb(0x80, 0x80, 0x88)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fg
        textAlign = Paint.Align.CENTER
        textSize = 19 * scaledDensity
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = dim
        textAlign = Paint.Align.CENTER
        textSize = 11 * scaledDensity
    }

    private val rowHeight = 52 * density
    private val gap = 3 * density
    private val swipeThreshold = 16 * density

    /** 押している指ごとの状態（速く打つときに前の指を離す前に次を押せるように） */
    private class Touch(val key: Key, val rect: RectF, val x0: Float, val y0: Float) {
        var dir: Dir? = null
        var repeated = false
    }
    private val touches = HashMap<Int, Touch>()
    private val keyRects = HashMap<Key, RectF>()
    private val handler = Handler(Looper.getMainLooper())

    /** 画面の下のナビゲーションバー（ジェスチャーの線・切り替えボタン）の高さ。その分だけ下を空ける */
    private var bottomInset = 0

    init {
        setOnApplyWindowInsetsListener { _, insets ->
            val nav = insets.getInsets(android.view.WindowInsets.Type.navigationBars())
            if (nav.bottom != bottomInset) {
                bottomInset = nav.bottom
                requestLayout()
            }
            insets
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (rows.size * rowHeight + gap * 2).toInt() + bottomInset)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        keyRects.clear()
        val unit = (width - gap) / 10f
        rows.forEachIndexed { r, row ->
            var x = gap / 2
            val top = gap + r * rowHeight
            for (key in row) {
                val rect = RectF(x + gap / 2, top + gap / 2, x + key.width * unit - gap / 2, top + rowHeight - gap / 2)
                x += key.width * unit
                if (key.center == null) continue
                keyRects[key] = rect
                drawKey(canvas, key, rect)
            }
        }
    }

    private fun drawKey(canvas: Canvas, key: Key, rect: RectF) {
        val pressed = touches.values.any { it.key === key }
        val mod = (key.center as? Action.Modifier)?.mod
        keyPaint.color = when {
            pressed -> keyPressed
            mod != null && mods[mod] == Latch.LOCKED -> modLocked
            mod != null && mods[mod] == Latch.ONCE -> modOnce
            else -> keyBg
        }
        canvas.drawRoundRect(rect, 6 * density, 6 * density, keyPaint)
        val cx = rect.centerX()
        val cy = rect.centerY() - (centerPaint.descent() + centerPaint.ascent()) / 2
        canvas.drawText(label(key.center!!, center = true), cx, cy, centerPaint)
        val dx = rect.width() / 2 - 9 * density
        val dy = rect.height() / 2 - 9 * density
        for ((dir, action) in key.corners) {
            val (ox, oy) = when (dir) {
                Dir.N -> 0f to -dy
                Dir.NE -> dx to -dy
                Dir.E -> dx to 0f
                Dir.SE -> dx to dy
                Dir.S -> 0f to dy
                Dir.SW -> -dx to dy
                Dir.W -> -dx to 0f
                Dir.NW -> -dx to -dy
            }
            val y = rect.centerY() + oy - (cornerPaint.descent() + cornerPaint.ascent()) / 2
            canvas.drawText(label(action, center = false), rect.centerX() + ox, y, cornerPaint)
        }
    }

    private fun label(action: Action, center: Boolean): String = when (action) {
        is Action.Text -> {
            val c = action.char
            when {
                c == ' ' -> "␣"
                center && c.isLetter() && (mods.containsKey(Mod.SHIFT)) -> c.uppercase()
                else -> c.toString()
            }
        }
        is Action.Code -> action.label
        is Action.Modifier -> action.mod.label
        Action.ToggleJapanese -> if (japanese) "あ" else "A"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val i = event.actionIndex
        val id = event.getPointerId(i)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = event.getX(i)
                val y = event.getY(i)
                val (key, rect) = keyRects.entries.firstOrNull { it.value.contains(x, y) }?.toPair() ?: return true
                val t = Touch(key, rect, x, y)
                touches[id] = t
                scheduleRepeat(id, t)
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until event.pointerCount) {
                    val t = touches[event.getPointerId(p)] ?: continue
                    val dir = direction(t, event.getX(p), event.getY(p))
                    if (dir != t.dir) {
                        t.dir = dir
                        // 払う方向が変わったら、長押しの繰り返しもその方向の動きで数え直す
                        scheduleRepeat(event.getPointerId(p), t)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val t = touches.remove(id) ?: return true
                handler.removeCallbacksAndMessages(id)
                t.dir = direction(t, event.getX(i), event.getY(i))
                if (!t.repeated) actionOf(t)?.let { fire(it) }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                touches.keys.forEach { handler.removeCallbacksAndMessages(it) }
                touches.clear()
                invalidate()
            }
        }
        return true
    }

    private fun direction(t: Touch, x: Float, y: Float): Dir? {
        val dx = x - t.x0
        val dy = y - t.y0
        if (hypot(dx, dy) < swipeThreshold) return null
        // 画面の上が N。8方向に分ける
        val angle = (Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())) + 360 + 22.5) % 360
        return Dir.entries[(angle / 45).toInt() % 8]
    }

    /** 払った方向の動き。その方向に何もなければ、近い隅か真ん中 */
    private fun actionOf(t: Touch): Action? {
        val dir = t.dir ?: return t.key.center
        t.key.corners[dir]?.let { return it }
        // 斜めに払ってその隅がなければ、上下左右の近い方を見る
        val near = when (dir) {
            Dir.N -> listOf(Dir.NE, Dir.NW)
            Dir.S -> listOf(Dir.SE, Dir.SW)
            Dir.E -> listOf(Dir.NE, Dir.SE)
            Dir.W -> listOf(Dir.NW, Dir.SW)
            Dir.NE -> listOf(Dir.N, Dir.E)
            Dir.NW -> listOf(Dir.N, Dir.W)
            Dir.SE -> listOf(Dir.S, Dir.E)
            Dir.SW -> listOf(Dir.S, Dir.W)
        }
        return near.firstNotNullOfOrNull { t.key.corners[it] } ?: t.key.center
    }

    /** 長押しで繰り返すキー（Backspace・矢印・Delete・Space）は、押し続けるあいだ繰り返す */
    private fun scheduleRepeat(id: Int, t: Touch) {
        handler.removeCallbacksAndMessages(id)
        val action = actionOf(t)
        val repeatable = (action as? Action.Code)?.repeat == true || (action is Action.Text && action.char == ' ' && t.dir != null)
        if (!repeatable) return
        val tick = object : Runnable {
            override fun run() {
                if (touches[id] !== t) return
                actionOf(t)?.let { fire(it) }
                t.repeated = true
                handler.postAtTime(this, id, android.os.SystemClock.uptimeMillis() + REPEAT_MS)
            }
        }
        handler.postAtTime(tick, id, android.os.SystemClock.uptimeMillis() + REPEAT_DELAY_MS)
    }

    private fun fire(action: Action) {
        when (action) {
            is Action.Modifier -> toggleModifier(action.mod)
            Action.ToggleJapanese -> onToggleJapanese()
            is Action.Code -> {
                onKey(action.keyCode, currentMeta())
                consumeOnceModifiers()
            }
            is Action.Text -> {
                sendText(action.char)
                consumeOnceModifiers()
            }
        }
        invalidate()
    }

    /** 文字を、物理キーボードと同じキーコードと Shift に直して渡す */
    private fun sendText(c0: Char) {
        val shift = mods.containsKey(Mod.SHIFT)
        val c = if (shift && c0.isLetter()) c0.uppercaseChar() else c0
        // Fn を押しているときは、数字と - = を F1〜F12 にする
        if (mods.containsKey(Mod.FN)) {
            FN_KEYS[c]?.let {
                onKey(it, currentMeta())
                return
            }
        }
        val events = KEY_MAP.getEvents(charArrayOf(c))
        val down = events?.firstOrNull { it.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(it.keyCode) } ?: return
        onKey(down.keyCode, (down.metaState and KeyEvent.META_SHIFT_MASK) or (currentMeta() and KeyEvent.META_SHIFT_MASK.inv()))
    }

    private fun currentMeta(): Int = mods.keys.fold(0) { acc, m -> acc or m.meta }

    private fun toggleModifier(mod: Mod) {
        val now = android.os.SystemClock.uptimeMillis()
        val doubleTap = lastModTap?.let { it.first == mod && now - it.second < DOUBLE_TAP_MS } == true
        lastModTap = mod to now
        mods[mod] = when {
            doubleTap -> Latch.LOCKED
            mods.containsKey(mod) -> {
                mods.remove(mod)
                return
            }
            else -> Latch.ONCE
        }
    }

    private fun consumeOnceModifiers() {
        mods.entries.removeAll { it.value == Latch.ONCE }
    }

    companion object {
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_MS = 50L
        private const val DOUBLE_TAP_MS = 350L

        private val KEY_MAP: android.view.KeyCharacterMap =
            android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)

        private val FN_KEYS = mapOf(
            '1' to KeyEvent.KEYCODE_F1, '2' to KeyEvent.KEYCODE_F2, '3' to KeyEvent.KEYCODE_F3, '4' to KeyEvent.KEYCODE_F4,
            '5' to KeyEvent.KEYCODE_F5, '6' to KeyEvent.KEYCODE_F6, '7' to KeyEvent.KEYCODE_F7, '8' to KeyEvent.KEYCODE_F8,
            '9' to KeyEvent.KEYCODE_F9, '0' to KeyEvent.KEYCODE_F10, '-' to KeyEvent.KEYCODE_F11, '=' to KeyEvent.KEYCODE_F12,
        )

        private fun t(c: Char) = Action.Text(c)
        private fun code(k: Int, label: String, repeat: Boolean = false) = Action.Code(k, label, repeat)

        /**
         * 並び（幅の合計は各行 10）。上の段の右上が数字、左上が Shift した記号。2段目は右上・左上に記号。
         * Space は左右に払うと矢印、Tab のキーは上下左右が矢印・四隅が Home/End/PgUp/PgDn、Backspace は右に払うと Delete
         */
        fun layout(): List<List<Key>> {
            val top = "qwertyuiop"
            val digits = "1234567890"
            val shifted = "!@#$%^&*()"
            val row1 = top.indices.map { i ->
                Key(1f, t(top[i]), mapOf(Dir.NE to t(digits[i]), Dir.NW to t(shifted[i])))
            }
            val mid = "asdfghjkl"
            val midNe = "`\\[]-=;'/"
            val midNw = "~|{}_+:\"?"
            val row2 = listOf(Key(0.5f, null)) + mid.indices.map { i ->
                Key(1f, t(mid[i]), mapOf(Dir.NE to t(midNe[i]), Dir.NW to t(midNw[i])))
            } + listOf(Key(0.5f, null))
            val row3 = listOf(Key(1.5f, Action.Modifier(Mod.SHIFT))) +
                listOf(
                    Key(1f, t('z'), mapOf(Dir.NE to t(','), Dir.NW to t('<'))),
                    Key(1f, t('x'), mapOf(Dir.NE to t('.'), Dir.NW to t('>'))),
                    Key(1f, t('c')), Key(1f, t('v')), Key(1f, t('b')), Key(1f, t('n')), Key(1f, t('m')),
                ) +
                listOf(
                    Key(
                        1.5f, code(KeyEvent.KEYCODE_DEL, "⌫", repeat = true),
                        mapOf(Dir.E to code(KeyEvent.KEYCODE_FORWARD_DEL, "⌦", repeat = true)),
                    ),
                )
            val row4 = listOf(
                Key(1.2f, Action.Modifier(Mod.CTRL)),
                Key(0.9f, Action.Modifier(Mod.FN)),
                Key(0.9f, Action.Modifier(Mod.ALT)),
                Key(1f, code(KeyEvent.KEYCODE_ESCAPE, "Esc")),
                Key(
                    2.6f, t(' '),
                    mapOf(
                        Dir.W to code(KeyEvent.KEYCODE_DPAD_LEFT, "←", repeat = true),
                        Dir.E to code(KeyEvent.KEYCODE_DPAD_RIGHT, "→", repeat = true),
                    ),
                ),
                Key(
                    1.2f, code(KeyEvent.KEYCODE_TAB, "⇥"),
                    mapOf(
                        Dir.N to code(KeyEvent.KEYCODE_DPAD_UP, "↑", repeat = true),
                        Dir.S to code(KeyEvent.KEYCODE_DPAD_DOWN, "↓", repeat = true),
                        Dir.W to code(KeyEvent.KEYCODE_DPAD_LEFT, "←", repeat = true),
                        Dir.E to code(KeyEvent.KEYCODE_DPAD_RIGHT, "→", repeat = true),
                        Dir.NW to code(KeyEvent.KEYCODE_MOVE_HOME, "⇱"),
                        Dir.NE to code(KeyEvent.KEYCODE_MOVE_END, "⇲"),
                        Dir.SW to code(KeyEvent.KEYCODE_PAGE_UP, "⇞", repeat = true),
                        Dir.SE to code(KeyEvent.KEYCODE_PAGE_DOWN, "⇟", repeat = true),
                    ),
                ),
                Key(1f, Action.ToggleJapanese),
                Key(1.2f, code(KeyEvent.KEYCODE_ENTER, "⏎")),
            )
            return listOf(row1, row2, row3, row4)
        }
    }
}
