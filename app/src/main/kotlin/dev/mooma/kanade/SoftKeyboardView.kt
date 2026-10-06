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
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 物理キーボードがないとき用の画面キーボード（Unexpected Keyboard と同じ考え方の、自前の実装）。
 * - 1つのキーに、真ん中の文字と、四隅・上下左右へ払ったときの文字を持つ（数字や記号は払って出す）
 * - Shift・Ctrl・Alt・Fn は、押すと次の1打だけ効き、2回続けて押すと固定になる。押したまま別のキーを押す同時押しもできる
 * - Esc・Tab・矢印・Home/End・PgUp/PgDn・Delete・F1〜F12 も出せる
 * 押したキーは [onKey]（キーコードと修飾）か [onToggleJapanese] で IME に渡し、物理キーボードと同じ流れで処理する。
 */
class SoftKeyboardView(
    context: Context,
    private val onKey: (keyCode: Int, meta: Int) -> Unit,
    private val onToggleJapanese: () -> Unit,
    /** 🎤 のキーを押した（true）・離した（false）。押している間だけ録音する */
    private val onVoice: (Boolean) -> Unit,
    /** 絵文字パネルを開く */
    private val onEmoji: () -> Unit,
    /** クリップボードを開く */
    private val onClipboard: () -> Unit,
    /** フリック配列に切り替える */
    private val onSwitchLayout: () -> Unit,
    private val onFloat: () -> Unit = {},
    /** グライドの配列で、文字のキーの上をなぞった */
    private val onGlide: (GlideInput) -> Unit = {},
    /** キーイベントにできない文字（記号のページの「」・€ など）を入れる */
    private val onText: (String) -> Unit = {},
) : View(context) {

    /** なぞった軌跡と、そのときの文字のキーの中心・幅（同じ座標） */
    class GlideInput(
        val xs: FloatArray,
        val ys: FloatArray,
        val keys: Map<Char, Pair<Float, Float>>,
        val keyWidth: Float,
        /** キーの高さ（段の間隔） */
        val keyHeight: Float,
        /** Shift が効いていた（英語なら頭を大文字にする） */
        val shift: Boolean,
    )

    /** グライドの配列のページ（文字・記号・その他の記号） */
    enum class Page { LETTERS, SYMBOLS, MORE }

    /** 払う方向（上から時計回り） */
    enum class Dir { N, NE, E, SE, S, SW, W, NW }

    enum class Mod(val label: String, val meta: Int) {
        SHIFT("⇧", KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON),
        CTRL("Ctrl", KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON),
        ALT("Alt", KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON),
        META("◆", KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON),
        FN("Fn", 0),
    }

    sealed interface Action {
        /** 印字できる文字（キーコードと Shift はキー配列から引く） */
        data class Text(val char: Char) : Action
        /** Esc・矢印などの特別なキー */
        data class Code(val keyCode: Int, val label: String, val repeat: Boolean = false) : Action
        data class Modifier(val mod: Mod) : Action
        data object ToggleJapanese : Action
        /** 押している間だけ録音（物理キーボードの録音キーと同じ） */
        data object Voice : Action
        /** 絵文字パネルを開く */
        data object Emoji : Action
        data object Clipboard : Action
        data object SwitchLayout : Action
        /** 浮かせる・固定に戻す */
        data object Float : Action
        /** グライドの配列のページを替える（?123・ABC・=\\<） */
        data class ToPage(val page: Page) : Action
    }

    class Key(val width: Float, val center: Action?, val corners: Map<Dir, Action> = emptyMap())

    /**
     * グライドの配列（払いの記号を置かず、文字のキーの上をなぞるとグライドになる）にするか。
     * しないときは、払いで記号や修飾を出すフルキーの配列
     */
    var glideLayout = false
        set(value) {
            if (field == value) return
            field = value
            page = Page.LETTERS
            rows = buildRows()
            invalidate()
        }
    private var page = Page.LETTERS
    private var rows: List<List<Key>> = buildRows()

    private fun buildRows() = if (glideLayout) glideLayout(page) else layout()

    /** 修飾の状態: 次の1打だけ（ONCE）か、固定（LOCKED） */
    private enum class Latch { ONCE, LOCKED }
    private val mods = HashMap<Mod, Latch>()
    private var lastModTap: Pair<Mod, Long>? = null
    /** 押したままの修飾キーと、押している間に別のキーを打ったか（打ったなら、離しても1打だけ効く状態にしない） */
    private val held = HashMap<Mod, Boolean>()

    /** 日本語入力中か（あ/A のキーの表示） */
    var japanese = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    /** 色は Material 3 の役割に合わせる（端末の壁紙の色。設定画面と同じ） */
    private val palette = KeyboardPalette.of(context)
    private val icons = HashMap<Int, android.graphics.drawable.Drawable>()

    /** 押したときのピル型への変形（0: ふつうの角、1: 両端が丸いピル型）と、その動き */
    private val pill = HashMap<Key, Float>()
    private val pillAnimators = HashMap<Key, android.animation.ValueAnimator>()

    /**
     * 修飾キー（真ん中か払った先の修飾）が効いている間（1打だけ・固定・押したまま）は、離してもピル型のままにする。
     * 修飾の状態が変わるたびに呼ぶ
     */
    private fun syncLatchedPills() {
        for (key in rows.flatten()) {
            val keyMods = (listOfNotNull(key.center) + key.corners.values).mapNotNull { (it as? Action.Modifier)?.mod }
            if (keyMods.isEmpty() || touches.values.any { it.key === key }) continue
            animatePill(key, pressed = keyMods.any { mods.containsKey(it) || held.containsKey(it) })
        }
    }

    /** 押したら素早くピル型に、離したらゆっくり元の形へ戻す（Material 3 Expressive の形の変化） */
    private fun animatePill(key: Key, pressed: Boolean) {
        pillAnimators.remove(key)?.cancel()
        val from = pill[key] ?: 0f
        val to = if (pressed) 1f else 0f
        if (from == to) return
        pillAnimators[key] = android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = if (pressed) PILL_IN_MS else PILL_OUT_MS
            interpolator = if (pressed) android.view.animation.DecelerateInterpolator() else android.view.animation.OvershootInterpolator(1.2f)
            addUpdateListener {
                val v = it.animatedValue as Float
                if (v <= 0f && !pressed) pill.remove(key) else pill[key] = v
                invalidate()
            }
            start()
        }
    }

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.text
        textAlign = Paint.Align.CENTER
        textSize = 19 * scaledDensity
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.dim
        textAlign = Paint.Align.CENTER
        textSize = 11 * scaledDensity
    }

    /** 高さの倍率（設定の「画面キーボードの高さ」、% で 60〜300） */
    var heightPercent = 100
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }
    private val rowHeight get() = 52 * density * heightPercent / 100

    /** 下に足す余白（dp、設定の「下の余白」）。ナビゲーションバーの分が取れない端末用 */
    var extraBottomDp = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** 聞き続けるモード中（🎤 のキーの色を変える） */
    var voiceActive = false
        set(value) {
            field = value
            invalidate()
        }

    private val gap = 3 * density
    /** 払いと見なす指の距離（キーの幅に対する %）。小さいと打ちたい文字が記号・数字になりやすい（設定で変えられる） */
    var swipePercent = DEFAULT_SWIPE_PERCENT

    /** 払いと見なす指の距離（px）。キーの幅に合わせるが、小さいキーでも最低これだけは要る */
    private val swipeThreshold: Float
        get() = maxOf(MIN_SWIPE_DP * density, (width - gap) / 10f * swipePercent / 100f)

    /** 押している指ごとの状態（速く打つときに前の指を離す前に次を押せるように） */
    private class Touch(val key: Key, val rect: RectF, val x0: Float, val y0: Float) {
        var dir: Dir? = null
        var repeated = false
        /** この指で押したままにしている修飾 */
        var heldMod: Mod? = null
        /** Space を横にドラッグして、ここまでに動かしたカーソルの数（右が正） */
        var dragSteps = 0
        /** グライドの配列で文字のキーから押したときの軌跡（なぞり始めたら [gliding]） */
        var xs: ArrayList<Float>? = null
        var ys: ArrayList<Float>? = null
        var gliding = false
    }

    /** なぞった軌跡の表示（離したあと薄れて消える） */
    private var trail: Touch? = null
    private var trailAlpha = 0f
    private var trailAnimator: android.animation.ValueAnimator? = null
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val touches = HashMap<Int, Touch>()
    private val keyRects = HashMap<Key, RectF>()
    private val handler = Handler(Looper.getMainLooper())

    /** 画面の下のナビゲーションバー（ジェスチャーの線・切り替えボタン）の高さ。その分だけ下を空ける */
    private var bottomInset = 0

    /** キーの下に空けている高さ（絵文字パネルも同じだけ空ける） */
    val bottomSpace: Int get() = if (floating) 0 else bottomInset + (extraBottomDp * density).toInt()

    /** 浮かせているとき（下の余白を取らない） */
    var floating = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    init {
        setOnApplyWindowInsetsListener { _, insets ->
            updateBottomInset(insets)
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rootWindowInsets?.let { updateBottomInset(it) }
        requestApplyInsets()
    }

    /** ナビゲーションバー（ボタン・ジェスチャーの線）と、押せる要素の分の下の高さを取る */
    private fun updateBottomInset(insets: android.view.WindowInsets) {
        val t = android.view.WindowInsets.Type.navigationBars() or android.view.WindowInsets.Type.tappableElement()
        val bottom = maxOf(insets.getInsets(t).bottom, insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom)
        if (bottom != bottomInset) {
            bottomInset = bottom
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (rows.size * rowHeight + gap * 2).toInt() + bottomSpace)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.background)
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
        drawTrail(canvas)
    }

    private fun drawTrail(canvas: Canvas) {
        val t = trail ?: return
        val xs = t.xs ?: return
        val ys = t.ys ?: return
        if (xs.size < 2 || trailAlpha <= 0f) return
        val path = android.graphics.Path()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until xs.size) path.lineTo(xs[i], ys[i])
        trailPaint.color = palette.accent
        trailPaint.alpha = (200 * trailAlpha).toInt()
        trailPaint.strokeWidth = 6 * density
        canvas.drawPath(path, trailPaint)
    }

    /** 離したあと、軌跡を薄くして消す */
    private fun fadeTrail() {
        trailAnimator?.cancel()
        trailAnimator = android.animation.ValueAnimator.ofFloat(trailAlpha, 0f).apply {
            duration = TRAIL_FADE_MS
            addUpdateListener {
                trailAlpha = it.animatedValue as Float
                if (trailAlpha <= 0f) trail = null
                invalidate()
            }
            start()
        }
    }

    /**
     * タップの位置の補い（グライドの配列の文字のキー）。触ったキーと、近くの文字のキーを候補にして [tapCorrector] に選んでもらう。
     * 選ばれたのが触ったキーなら null
     */
    var tapCorrector: ((List<dev.mooma.kanade.core.touch.TapCorrection.Option>) -> Int)? = null

    private fun correctTap(t: Touch): Char? {
        val corrector = tapCorrector ?: return null
        if (!glideLayout || t.dir != null) return null
        val touched = (t.key.center as? Action.Text)?.char?.takeIf { it in 'a'..'z' } ?: return null
        val kw = (width - gap) / 10f
        val kh = rowHeight
        fun distance(rect: RectF) = hypot((t.x0 - rect.centerX()) / kw, (t.y0 - rect.centerY()) / kh)
        val options = ArrayList<dev.mooma.kanade.core.touch.TapCorrection.Option>()
        options += dev.mooma.kanade.core.touch.TapCorrection.Option(touched, distance(t.rect))
        for ((key, rect) in keyRects) {
            val c = (key.center as? Action.Text)?.char ?: continue
            if (c !in 'a'..'z' || c == touched) continue
            val d = distance(rect)
            if (d <= NEIGHBOR_RADIUS) options += dev.mooma.kanade.core.touch.TapCorrection.Option(c, d)
        }
        val chosen = options.getOrNull(corrector(options)) ?: return null
        return chosen.char.takeIf { it != touched }
    }

    /** 文字のキー（a〜z）の中心 */
    private fun letterCenters(): Map<Char, Pair<Float, Float>> = buildMap {
        for ((key, rect) in keyRects) {
            val c = (key.center as? Action.Text)?.char ?: continue
            if (c in 'a'..'z') put(c, rect.centerX() to rect.centerY())
        }
    }

    private fun drawKey(canvas: Canvas, key: Key, rect: RectF) {
        // なぞっている指は、押し始めたキーを押した色にしない
        val pressed = touches.values.any { it.key === key && !it.gliding }
        val center = key.center!!
        val keyMods = (listOf(center) + key.corners.values).mapNotNull { (it as? Action.Modifier)?.mod }
        // 地と文字の色（Material 3 の役割: 文字のキー・機能のキー・強調（Enter・固定した修飾）・1打だけの修飾）
        val (fill, ink) = when {
            pressed || keyMods.any { it in held } -> palette.pressed to palette.text
            center == Action.Voice && voiceActive -> palette.voice to palette.onVoice
            keyMods.any { mods[it] == Latch.LOCKED } -> palette.accent to palette.onAccent
            keyMods.any { mods[it] == Latch.ONCE } -> palette.modOnce to palette.onModOnce
            center is Action.Code && center.keyCode == KeyEvent.KEYCODE_ENTER -> palette.accent to palette.onAccent
            isFunctionKey(center) -> palette.functionKey to palette.text
            else -> palette.key to palette.text
        }
        keyPaint.color = fill
        val base = KEY_RADIUS_DP * density
        val radius = base + (minOf(rect.width(), rect.height()) / 2 - base) * (pill[key] ?: 0f).coerceAtLeast(0f)
        canvas.drawRoundRect(rect, radius, radius, keyPaint)
        val cx = rect.centerX()
        val icon = iconOf(center)
        if (icon != null) {
            val size = minOf(22 * density, rect.height() * 0.45f)
            drawIcon(canvas, icon, cx, rect.centerY(), size, ink, mirrored = center is Action.Code && center.keyCode == KeyEvent.KEYCODE_FORWARD_DEL)
        } else {
            centerPaint.color = ink
            val cy = rect.centerY() - (centerPaint.descent() + centerPaint.ascent()) / 2
            canvas.drawText(label(center, center = true), cx, cy, centerPaint)
        }
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
            val cornerIcon = iconOf(action)
            if (cornerIcon != null) {
                drawIcon(
                    canvas, cornerIcon, cx + ox, rect.centerY() + oy, 12 * density, palette.dim,
                    mirrored = action is Action.Code && action.keyCode == KeyEvent.KEYCODE_FORWARD_DEL,
                )
            } else {
                val y = rect.centerY() + oy - (cornerPaint.descent() + cornerPaint.ascent()) / 2
                canvas.drawText(label(action, center = false), cx + ox, y, cornerPaint)
            }
        }
    }

    /** 修飾・Esc・Tab・⌫・あ/A・🎤 は機能のキーの色にする */
    private fun isFunctionKey(action: Action) = when (action) {
        is Action.Modifier, Action.ToggleJapanese, Action.Voice, Action.Emoji, Action.Clipboard, Action.SwitchLayout, Action.Float, is Action.ToPage -> true
        is Action.Code -> true
        is Action.Text -> false
    }

    /** Material Symbols で描くキー。文字で書くもの（Ctrl・Alt・Fn・Esc・あ/A・英数字）は null */
    private fun iconOf(action: Action): Int? = when (action) {
        is Action.Code -> when (action.keyCode) {
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> R.drawable.sym_backspace
            KeyEvent.KEYCODE_ENTER -> R.drawable.sym_keyboard_return
            KeyEvent.KEYCODE_TAB -> R.drawable.sym_keyboard_tab
            KeyEvent.KEYCODE_DPAD_LEFT -> R.drawable.sym_arrow_back
            KeyEvent.KEYCODE_DPAD_RIGHT -> R.drawable.sym_arrow_forward
            KeyEvent.KEYCODE_DPAD_UP -> R.drawable.sym_arrow_upward
            KeyEvent.KEYCODE_DPAD_DOWN -> R.drawable.sym_arrow_downward
            KeyEvent.KEYCODE_MOVE_HOME -> R.drawable.sym_first_page
            KeyEvent.KEYCODE_MOVE_END -> R.drawable.sym_last_page
            KeyEvent.KEYCODE_PAGE_UP -> R.drawable.sym_keyboard_double_arrow_up
            KeyEvent.KEYCODE_PAGE_DOWN -> R.drawable.sym_keyboard_double_arrow_down
            else -> null
        }
        is Action.Modifier -> when (action.mod) {
            Mod.SHIFT -> if (mods[Mod.SHIFT] == Latch.LOCKED) R.drawable.sym_shift_lock else R.drawable.sym_shift
            Mod.META -> R.drawable.sym_keyboard_command_key
            else -> null
        }
        Action.Voice -> R.drawable.sym_mic
        Action.Emoji -> R.drawable.sym_mood
        Action.Clipboard -> R.drawable.sym_content_paste
        Action.SwitchLayout -> R.drawable.sym_dialpad
        Action.Float -> R.drawable.sym_picture_in_picture_alt
        is Action.Text -> if (action.char == ' ') R.drawable.sym_space_bar else null
        Action.ToggleJapanese, is Action.ToPage -> null
    }

    private fun drawIcon(canvas: Canvas, id: Int, cx: Float, cy: Float, size: Float, tint: Int, mirrored: Boolean = false) {
        val d = icons.getOrPut(id) { context.getDrawable(id)!!.mutate() }
        val half = (size / 2).toInt()
        d.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
        d.setTint(tint)
        if (mirrored) {
            canvas.save()
            canvas.scale(-1f, 1f, cx, cy)
            d.draw(canvas)
            canvas.restore()
        } else {
            d.draw(canvas)
        }
    }

    private fun label(action: Action, center: Boolean): String = when (action) {
        is Action.Text -> {
            val c = action.char
            when {
                c == ' ' -> "␣"
                center && c.isLetter() && isOn(Mod.SHIFT) -> c.uppercase()
                else -> c.toString()
            }
        }
        is Action.Code -> action.label
        is Action.Modifier -> action.mod.label
        Action.ToggleJapanese -> if (japanese) "あ" else "A"
        Action.Voice -> "🎤"
        Action.Emoji -> "☺"
        Action.Clipboard -> "📋"
        Action.SwitchLayout -> "⌨"
        Action.Float -> "▭"
        is Action.ToPage -> when (action.page) {
            Page.LETTERS -> "ABC"
            Page.SYMBOLS -> "?123"
            Page.MORE -> "=\\<"
        }
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
                animatePill(key, pressed = true)
                (key.center as? Action.Modifier)?.let {
                    t.heldMod = it.mod
                    held[it.mod] = false
                }
                if (key.center == Action.Voice) onVoice(true)
                // グライドの配列の文字のキーなら、軌跡を取り始める（なぞったらグライド、動かさず離したらその文字）
                if (glideLayout && (key.center as? Action.Text)?.char?.let { it in 'a'..'z' } == true) {
                    t.xs = arrayListOf(x)
                    t.ys = arrayListOf(y)
                }
                scheduleRepeat(id, t)
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until event.pointerCount) {
                    val t = touches[event.getPointerId(p)] ?: continue
                    val xs = t.xs
                    val ys = t.ys
                    if (xs != null && ys != null) {
                        for (h in 0 until event.historySize) {
                            xs += event.getHistoricalX(p, h)
                            ys += event.getHistoricalY(p, h)
                        }
                        xs += event.getX(p)
                        ys += event.getY(p)
                        if (!t.gliding && hypot(event.getX(p) - t.x0, event.getY(p) - t.y0) > GLIDE_START_DP * density) {
                            // なぞり始めた: 長押しの繰り返しをやめ、キーを離した形に戻して、軌跡を出す
                            t.gliding = true
                            handler.removeCallbacksAndMessages(event.getPointerId(p))
                            animatePill(t.key, pressed = false)
                            trailAnimator?.cancel()
                            trail = t
                            trailAlpha = 1f
                        }
                        if (t.gliding) {
                            invalidate()
                            continue
                        }
                    }
                    if (isSpace(t.key)) {
                        dragSpace(event.getPointerId(p), t, event.getX(p))
                        continue
                    }
                    val dir = direction(t, event.getX(p), event.getY(p))
                    if (dir != t.dir) {
                        t.dir = dir
                        // 修飾キーを払って別の修飾（Alt→Meta）にしたら、押したままの修飾もそれに替える
                        val mod = (actionOf(t) as? Action.Modifier)?.mod
                        val old = t.heldMod
                        if (mod != null && old != null && mod != old) {
                            held[mod] = held.remove(old) ?: false
                            t.heldMod = mod
                            invalidate()
                        } else if (mod == null && old != null) {
                            // 修飾キーを払って修飾でないもの（Ctrl→配列の切り替えなど）にしたら、押したままの修飾は外す
                            held.remove(old)
                            t.heldMod = null
                            invalidate()
                        }
                        // 払う方向が変わったら、長押しの繰り返しもその方向の動きで数え直す
                        scheduleRepeat(event.getPointerId(p), t)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val t = touches.remove(id) ?: return true
                handler.removeCallbacksAndMessages(id)
                if (touches.values.none { it.key === t.key }) animatePill(t.key, pressed = false)
                if (t.gliding) {
                    val xs = t.xs!!
                    val ys = t.ys!!
                    xs += event.getX(i)
                    ys += event.getY(i)
                    val unit = (width - gap) / 10f
                    onGlide(GlideInput(xs.toFloatArray(), ys.toFloatArray(), letterCenters(), unit, rowHeight, isOn(Mod.SHIFT)))
                    consumeOnceModifiers()
                    fadeTrail()
                    syncLatchedPills()
                    invalidate()
                    return true
                }
                t.dir = direction(t, event.getX(i), event.getY(i))
                val mod = t.heldMod
                if (t.key.center == Action.Voice) {
                    onVoice(false)
                } else if (mod != null) {
                    // 押している間に別のキーを打った（同時押し）なら、離すだけ。打たなかったら1打だけ効く・固定の切り替え
                    if (held.remove(mod) == false) toggleModifier(mod)
                } else if (t.repeated) {
                    consumeOnceModifiers()
                } else if (isSpace(t.key) && t.dir.let { it == Dir.W || it == Dir.E }) {
                    // 短く払った（フリック）なら、その向きに1文字だけ動かす
                    fire(arrow(if (t.dir == Dir.W) -1 else 1))
                } else {
                    val corrected = correctTap(t)
                    if (corrected != null) fire(Action.Text(corrected)) else actionOf(t)?.let { fire(it) }
                }
                syncLatchedPills()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                touches.keys.forEach { handler.removeCallbacksAndMessages(it) }
                touches.values.forEach { animatePill(it.key, pressed = false) }
                if (touches.values.any { it.key.center == Action.Voice }) onVoice(false)
                touches.clear()
                held.clear()
                trail = null
                syncLatchedPills()
                invalidate()
            }
        }
        return true
    }

    private fun isSpace(key: Key) = (key.center as? Action.Text)?.char == ' '

    private fun arrow(sign: Int) = code(if (sign < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT, "")

    /**
     * Space を横にドラッグすると、指の動きに合わせてカーソルを動かす（払い始めの分を除き、[DRAG_STEP_DP] ごとに1文字）。
     * 修飾（Shift など）が効いていれば、そのまま選択になる
     */
    private fun dragSpace(id: Int, t: Touch, x: Float) {
        val dx = x - t.x0
        val travel = kotlin.math.abs(dx) - swipeThreshold
        // 動かし始めたら、長押しの空白の繰り返しはやめる
        if (travel > 0) handler.removeCallbacksAndMessages(id)
        val target = if (travel <= 0) 0 else ((travel / (DRAG_STEP_DP * density)).toInt()) * (if (dx < 0) -1 else 1)
        if (target != t.dragSteps) {
            val sign = if (target > t.dragSteps) 1 else -1
            repeat(kotlin.math.abs(target - t.dragSteps)) { fire(arrow(sign), consume = false) }
            t.dragSteps = target
            // ドラッグでカーソルを動かしたら、離しても空白は入れない（1打だけの修飾は離したときに外す）
            t.repeated = true
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        t.dir = direction(t, x, t.y0)
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
        // Backspace・矢印などと、文字のキーを長押ししたとき（払っていない真ん中の文字。Space も）に繰り返す
        val repeatable = (action as? Action.Code)?.repeat == true || (action is Action.Text && t.dir == null)
        if (!repeatable) return
        val tick = object : Runnable {
            override fun run() {
                if (touches[id] !== t) return
                // 繰り返している間は1打だけの修飾を残す（Shift を1回押して ← を長押しで、続けて選択できるように）。離したときに外す
                actionOf(t)?.let { fire(it, consume = false) }
                t.repeated = true
                handler.postAtTime(this, id, android.os.SystemClock.uptimeMillis() + REPEAT_MS)
            }
        }
        handler.postAtTime(tick, id, android.os.SystemClock.uptimeMillis() + REPEAT_DELAY_MS)
    }

    private fun fire(action: Action, consume: Boolean = true) {
        when (action) {
            is Action.Modifier -> toggleModifier(action.mod)
            Action.ToggleJapanese -> onToggleJapanese()
            Action.Voice -> Unit
            Action.Emoji -> onEmoji()
            Action.Clipboard -> onClipboard()
            Action.SwitchLayout -> onSwitchLayout()
            Action.Float -> onFloat()
            is Action.ToPage -> {
                page = action.page
                rows = buildRows()
                pill.clear()
            }
            is Action.Code -> {
                onKey(action.keyCode, currentMeta())
                afterKey(consume)
            }
            is Action.Text -> {
                sendText(action.char)
                afterKey(consume)
            }
        }
        invalidate()
    }

    /** 文字を、物理キーボードと同じキーコードと Shift に直して渡す */
    private fun sendText(c0: Char) {
        val shift = isOn(Mod.SHIFT)
        val c = if (shift && c0.isLetter()) c0.uppercaseChar() else c0
        // Fn を押しているときは、数字と - = を F1〜F12 にする
        if (isOn(Mod.FN)) {
            FN_KEYS[c]?.let {
                onKey(it, currentMeta())
                return
            }
        }
        val events = KEY_MAP.getEvents(charArrayOf(c))
        val down = events?.firstOrNull { it.action == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(it.keyCode) }
        if (down == null) {
            // キーイベントにできない文字（「」・€ など）は、文字のまま入れる
            onText(c.toString())
            return
        }
        onKey(down.keyCode, (down.metaState and KeyEvent.META_SHIFT_MASK) or (currentMeta() and KeyEvent.META_SHIFT_MASK.inv()))
    }

    private fun isOn(mod: Mod) = mods.containsKey(mod) || held.containsKey(mod)

    private fun currentMeta(): Int = (mods.keys + held.keys).fold(0) { acc, m -> acc or m.meta }

    private fun afterKey(consume: Boolean) {
        // 押したままの修飾キーは「同時押しに使った」印を付ける
        held.replaceAll { _, _ -> true }
        if (consume) consumeOnceModifiers()
    }

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
        /** Space のドラッグで1文字動かす指の距離 */
        private const val DRAG_STEP_DP = 12f
        /** キーの角の丸み（Material 3 の medium に近い） */
        private const val KEY_RADIUS_DP = 10f
        private const val PILL_IN_MS = 90L
        private const val PILL_OUT_MS = 280L
        private const val REPEAT_MS = 50L
        private const val DOUBLE_TAP_MS = 350L
        /** タップの補いで候補にする近くのキー（中心までの距離。キーの大きさを 1 として） */
        private const val NEIGHBOR_RADIUS = 1.1f
        /** 払いと見なす距離の既定（キーの幅の 45%）と、最低の距離 */
        const val DEFAULT_SWIPE_PERCENT = 45
        private const val MIN_SWIPE_DP = 18f
        /** これだけ指が動いたら、文字のキーのタップではなくグライドにする */
        private const val GLIDE_START_DP = 14f
        private const val TRAIL_FADE_MS = 220L

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
        /**
         * グライドの配列（Gboard に近い）。文字のキーには払いを置かない（なぞるとグライドになるため）。
         * 左下の ?123 で記号のページ、上に払うと配列の切り替え、下に払うとフロート。あ/A は上で絵文字・下でクリップボード
         */
        fun glideLayout(page: Page): List<List<Key>> {
            val bottom = listOf(
                Key(
                    1.3f, Action.ToPage(if (page == Page.LETTERS) Page.SYMBOLS else Page.LETTERS),
                    mapOf(Dir.N to Action.SwitchLayout, Dir.S to Action.Float),
                ),
                Key(1f, Action.ToggleJapanese, mapOf(Dir.N to Action.Emoji, Dir.S to Action.Clipboard)),
                Key(0.9f, Action.Voice),
                Key(
                    3.4f, t(' '),
                    mapOf(
                        Dir.W to code(KeyEvent.KEYCODE_DPAD_LEFT, "←", repeat = true),
                        Dir.E to code(KeyEvent.KEYCODE_DPAD_RIGHT, "→", repeat = true),
                    ),
                ),
                Key(0.8f, t(','), mapOf(Dir.N to t('?'))),
                Key(0.8f, t('.'), mapOf(Dir.N to t('!'))),
                Key(1.8f, code(KeyEvent.KEYCODE_ENTER, "⏎")),
            )
            val backspace = Key(
                1.5f, code(KeyEvent.KEYCODE_DEL, "⌫", repeat = true),
                mapOf(Dir.E to code(KeyEvent.KEYCODE_FORWARD_DEL, "⌦", repeat = true)),
            )
            fun keys(s: String) = s.map { Key(1f, t(it)) }
            return when (page) {
                Page.LETTERS -> listOf(
                    keys("qwertyuiop"),
                    listOf(Key(0.5f, null)) + keys("asdfghjkl") + listOf(Key(0.5f, null)),
                    listOf(Key(1.5f, Action.Modifier(Mod.SHIFT))) + keys("zxcvbnm") + listOf(backspace),
                    bottom,
                )
                Page.SYMBOLS -> listOf(
                    keys("1234567890"),
                    keys("@#$%&-+()/"),
                    listOf(Key(1.5f, Action.ToPage(Page.MORE))) + keys("*\"':;!?") + listOf(backspace),
                    bottom,
                )
                Page.MORE -> listOf(
                    keys("~`|\\{}[]<>"),
                    keys("^_=€£¥°…・※"),
                    listOf(Key(1.5f, Action.ToPage(Page.SYMBOLS))) + keys("「」『』〜♪→") + listOf(backspace),
                    bottom,
                )
            }
        }

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
                // 上に払うと配列の切り替え、下に払うとフロート（フリックの「あa1」と同じ位置・向き）
                Key(1.1f, Action.Modifier(Mod.CTRL), mapOf(Dir.N to Action.SwitchLayout, Dir.S to Action.Float)),
                Key(0.8f, Action.Modifier(Mod.FN)),
                // Meta（Windows キー・⌘ にあたる。DeX のショートカットなど）は Alt を上に払う
                Key(0.8f, Action.Modifier(Mod.ALT), mapOf(Dir.N to Action.Modifier(Mod.META))),
                // Esc のキーを上に払うと、フリック配列に切り替える
                Key(1f, code(KeyEvent.KEYCODE_ESCAPE, "Esc")),
                Key(
                    2.4f, t(' '),
                    mapOf(
                        Dir.W to code(KeyEvent.KEYCODE_DPAD_LEFT, "←", repeat = true),
                        Dir.E to code(KeyEvent.KEYCODE_DPAD_RIGHT, "→", repeat = true),
                    ),
                ),
                Key(
                    1.1f, code(KeyEvent.KEYCODE_TAB, "⇥"),
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
                // あ/A のキーを上に払うと絵文字パネル、下に払うとクリップボード
                Key(0.9f, Action.ToggleJapanese, mapOf(Dir.N to Action.Emoji, Dir.S to Action.Clipboard)),
                Key(0.8f, Action.Voice),
                Key(1.1f, code(KeyEvent.KEYCODE_ENTER, "⏎")),
            )
            return listOf(row1, row2, row3, row4)
        }
    }
}
