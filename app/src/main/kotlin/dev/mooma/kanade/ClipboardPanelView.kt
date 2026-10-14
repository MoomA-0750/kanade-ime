package dev.mooma.kanade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * クリップボードのパネル。ピン留めしたものと最近コピーしたものを並べる。
 * 押すと貼り付け、長押しでメニュー（ピン留め・外す、文字なら編集、画像なら切り抜き、削除）。切り抜きはパネルの中で行い、新しい項目として足す。
 * 文字の編集は、キーボードで打てるよう別の画面（[ClipboardEditActivity]）で行う。カードの下にはコピー元のアプリの名前を出す
 */
@SuppressLint("ViewConstructor")
class ClipboardPanelView(
    context: Context,
    private val history: ClipboardHistory,
    private val onPaste: (ClipboardHistory.Item) -> Unit,
    private val onClose: () -> Unit,
    /** いまのコピーが機密の印つき（パスワードなど）なら、その文字。履歴には残さず、伏せて出して貼り付けられるようにする */
    private val currentSensitive: () -> String? = { null },
    private val onEdit: (ClipboardHistory.Item) -> Unit = {},
) : FrameLayout(context) {
    private fun appLabel(packageName: String): String? = history.appLabel(packageName)

    private val palette = KeyboardPalette.of(context)
    private val density = resources.displayMetrics.density
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /**
     * 物理キーボードで開いたとき（重ねる窓に出すとき）の幅。null なら画面の幅。列の数とカードの幅をこれで決める
     */
    var contentWidth: Int? = null

    /** 物理キーボードで開いているか。選んでいるカードに枠を付け、先頭の9枚に数字を出す */
    var keyboardMode = false
        set(value) {
            field = value
            updateSelection()
        }

    /** 並んでいる順のカード（キーで選ぶため）。項目と、枠を付ける外側の入れ物と、数字の札 */
    private class Card(val item: ClipboardHistory.Item, val frame: FrameLayout, val badge: TextView)
    private val cards = ArrayList<Card>()
    private var selected = 0
    private val message = TextView(context)

    private fun dp(v: Int) = (v * density).toInt()

    init {
        setBackgroundColor(palette.background)
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // 見出し: 戻る・題・最近のものを消す
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.sym_arrow_back, context.getString(R.string.ime_clipboard_back_to_keyboard)) { onClose() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(TextView(context).apply {
                text = context.getString(R.string.ime_clipboard_title)
                setTextColor(palette.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(icon(R.drawable.sym_delete_sweep, context.getString(R.string.ime_clipboard_clear_unpinned)) {
                history.clearRecent()
                refresh()
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        message.apply {
            setTextColor(palette.dim)
            setPadding(dp(16), 0, dp(16), dp(4))
            visibility = GONE
        }
        root.addView(message)
        root.addView(ScrollView(context).apply { addView(list) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(root)
    }

    /** 貼り付けられなかったときなどの知らせ（次に開き直すまで出す） */
    fun showMessage(text: String?) {
        message.text = text
        message.visibility = if (text == null) GONE else VISIBLE
    }

    fun refresh() {
        list.removeAllViews()
        cards.clear()
        // パスワード管理アプリ（1Password など）のコピーは機密の印が付いていて履歴に残さないので、いまクリップボードにある間だけ伏せて出す
        currentSensitive()?.let { secret ->
            section(context.getString(R.string.ime_clipboard_section_sensitive), listOf(ClipboardHistory.Item(SENSITIVE_ID, secret, null, null, false, 0L)))
        }
        val items = history.all()
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = context.getString(R.string.ime_clipboard_empty)
                setTextColor(palette.dim)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
            return
        }
        val pinned = items.filter { it.pinned }
        val recent = items.filter { !it.pinned }
        if (pinned.isNotEmpty()) section(context.getString(R.string.ime_clipboard_section_pinned), pinned)
        if (recent.isNotEmpty()) section(context.getString(R.string.ime_clipboard_section_recent), recent)
        // キーで開いたときは、最近のものの先頭（いちばん新しいコピー）から選ぶ
        selected = cards.indexOfFirst { !it.item.pinned && it.item.id != SENSITIVE_ID }.takeIf { it >= 0 } ?: 0
        updateSelection()
    }

    /** 幅 [width] で並べたときの、見出しから最後のカードまでの高さ */
    fun contentHeight(width: Int): Int {
        val spec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        list.measure(spec, unspecified)
        message.measure(spec, unspecified)
        val shownMessage = if (message.visibility == VISIBLE) message.measuredHeight else 0
        return dp(52) + shownMessage + list.measuredHeight + dp(4)
    }

    /** いま選んでいる項目（キーで開いているとき） */
    fun selectedItem(): ClipboardHistory.Item? = cards.getOrNull(selected)?.item

    /** 数字キー（1〜9）の項目 */
    fun numberedItem(n: Int): ClipboardHistory.Item? = cards.getOrNull(n - 1)?.item

    /** 左右は並びの順に1つずつ、上下は画面の上で上下にあるカードへ動く */
    fun moveSelection(dx: Int, dy: Int) {
        if (cards.isEmpty()) return
        if (dy == 0) {
            selected = (selected + dx).coerceIn(0, cards.size - 1)
        } else {
            val rects = cards.map { c -> android.graphics.Rect().also { r -> c.frame.getDrawingRect(r); list.offsetDescendantRectToMyCoords(c.frame, r) } }
            val cur = rects[selected]
            val candidates = rects.indices.filter { i -> if (dy > 0) rects[i].top >= cur.bottom else rects[i].bottom <= cur.top }
            if (candidates.isNotEmpty()) {
                // いちばん近い行の中で、横の位置がいちばん近いもの
                val row = if (dy > 0) candidates.minOf { rects[it].top } else candidates.maxOf { rects[it].bottom }
                selected = candidates.filter { (if (dy > 0) rects[it].top else rects[it].bottom) == row }
                    .minBy { kotlin.math.abs(rects[it].centerX() - cur.centerX()) }
            }
        }
        updateSelection()
    }

    /** 最初・最後のカードへ */
    fun selectEdge(last: Boolean) {
        if (cards.isEmpty()) return
        selected = if (last) cards.size - 1 else 0
        updateSelection()
    }

    private fun updateSelection() {
        for ((i, c) in cards.withIndex()) {
            c.frame.foreground = if (keyboardMode && i == selected) GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setStroke(dp(3), palette.accent)
            } else null
            c.badge.visibility = if (keyboardMode && i < 9) VISIBLE else GONE
        }
        val c = cards.getOrNull(selected) ?: return
        if (keyboardMode) c.frame.post { c.frame.requestRectangleOnScreen(android.graphics.Rect(0, -dp(32), c.frame.width, c.frame.height + dp(8))) }
    }

    private fun section(title: String, items: List<ClipboardHistory.Item>) {
        list.addView(TextView(context).apply {
            text = title
            setTextColor(palette.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(16), dp(8), dp(16), dp(4))
        })
        // 幅に合わせて列の数を決める（スマホは2列、広い画面は4列）
        val panelWidth = contentWidth ?: resources.displayMetrics.widthPixels
        val columns = if (contentWidth != null) (panelWidth / dp(170)).coerceIn(2, 4) else if (ScreenWidth.isWide(context)) 4 else 2
        val grid = GridLayout(context).apply {
            columnCount = columns
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        for (item in items) {
            // 列の幅は画面の幅から決める（1件だけでも横いっぱいに伸ばさない）
            val cellWidth = (panelWidth - dp(16)) / columns - dp(8)
            grid.addView(card(item), GridLayout.LayoutParams().apply {
                width = cellWidth
                height = dp(96)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        list.addView(grid)
    }

    private fun card(item: ClipboardHistory.Item): View {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(palette.key)
        }
        val view: View = if (item.image != null) {
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                history.imageFile(item)?.let { setImageBitmap(decodeThumb(it.path, dp(240))) }
            }
        } else {
            TextView(context).apply {
                // 機密のコピーは中身を出さない
                text = if (item.id == SENSITIVE_ID) "••••••••\n" + context.getString(R.string.ime_clipboard_sensitive_hint, item.text?.length ?: 0) else item.text
                setTextColor(palette.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = if (item.source != null) 3 else 4
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), dp(10), dp(12), if (item.source != null) dp(24) else dp(10))
            }
        }
        view.background = bg
        view.foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(palette.pressed), null, bg)
        view.setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            onPaste(item)
        }
        view.setOnLongClickListener {
            if (item.id != SENSITIVE_ID) menu(item)
            true
        }
        val source = item.source?.let { appLabel(it) ?: it }
        // 物理キーボードで開いたときの数字の札（数字キーでその項目を貼り付ける）
        val badge = TextView(context).apply {
            text = "${cards.size + 1}"
            setTextColor(palette.onAccent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            minWidth = dp(18)
            setPadding(dp(4), 0, dp(4), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(palette.accent)
            }
            visibility = GONE
        }
        return FrameLayout(context).apply {
            cards += Card(item, this, badge)
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, dp(18), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(6), dp(6)) })
            // ピン留めの印
            if (item.pinned) addView(ImageView(context).apply {
                setImageResource(R.drawable.sym_push_pin_fill)
                setColorFilter(palette.accent)
            }, LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
            // コピー元のアプリ（画像の上でも読めるよう、カードの色の下地を付ける）
            if (source != null) addView(TextView(context).apply {
                text = source
                setTextColor(palette.dim)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(6), dp(1), dp(6), dp(1))
                background = GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(palette.key)
                }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(6), 0, dp(6), dp(6))
            })
        }
    }

    /**
     * 長押しのメニュー。パネルの上に重ねて出す（PopupMenu はフォーカスを取るので、Android 12 などでは入力欄の側がフォーカスを失い、キーボードごと閉じてしまう）
     */
    private fun menu(item: ClipboardHistory.Item) {
        val actions = buildList {
            add(context.getString(if (item.pinned) R.string.ime_clipboard_unpin else R.string.ime_clipboard_pin) to { history.setPinned(item.id, !item.pinned); refresh() })
            if (item.text != null) add(context.getString(R.string.ime_clipboard_edit) to { onEdit(item) })
            if (item.image != null) add(context.getString(R.string.ime_clipboard_crop) to { crop(item) })
            add(context.getString(R.string.ime_clipboard_delete) to { history.delete(item.id); refresh() })
        }
        val overlay = FrameLayout(context).apply {
            setBackgroundColor(0x66000000)
            isClickable = true
        }
        overlay.setOnClickListener { removeView(overlay) }
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(palette.key)
            }
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
        }
        for ((label, action) in actions) {
            sheet.addView(TextView(context).apply {
                text = label
                setTextColor(palette.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(palette.pressed), null, null)
                setOnClickListener {
                    this@ClipboardPanelView.removeView(overlay)
                    action()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
        }
        overlay.addView(sheet, LayoutParams(dp(240), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** 画像を切り抜く。四隅をつまんで範囲を決め、「保存」で新しい項目として足す */
    private fun crop(item: ClipboardHistory.Item) {
        val file = history.imageFile(item) ?: return
        val bitmap = BitmapFactory.decodeFile(file.path) ?: return
        val cropView = CropView(context, bitmap)
        val overlay = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            isClickable = true
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            // apply の中の removeView はこの行（LinearLayout）を指すので、パネルを明示する
            addView(textButton(context.getString(R.string.ime_clipboard_cancel)) { this@ClipboardPanelView.removeView(overlay) })
            addView(textButton(context.getString(R.string.ime_clipboard_save)) {
                history.addBitmap(cropView.cropped(), item.source)
                this@ClipboardPanelView.removeView(overlay)
                refresh()
            })
        }
        overlay.addView(cropView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        overlay.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun textButton(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(palette.accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(dp(20), dp(10), dp(20), dp(10))
        setOnClickListener { onClick() }
    }

    private fun icon(id: Int, description: String, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(id)
        setColorFilter(palette.text)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = description
        setOnClickListener { onClick() }
    }

    /** 一覧に出す小さな画像（大きい画像をそのまま読まない） */
    private fun decodeThumb(path: String, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** 切り抜きの範囲を決める部品。画像を収まる大きさで出し、範囲の四隅か中をつまんで動かす */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class CropView(context: Context, private val bitmap: Bitmap) : View(context) {
        private val density = resources.displayMetrics.density
        private val image = RectF()
        /** 切り抜く範囲（画面の座標） */
        private val crop = RectF()
        private val dim = Paint().apply { color = 0x99000000.toInt() }
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            color = 0xFFFFFFFF.toInt()
        }
        private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        /** つまんでいるところ: 0〜3 が四隅、4 が範囲ごと */
        private var grab = -1
        private var lastX = 0f
        private var lastY = 0f

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val pad = 16 * density
            val scale = min((w - 2 * pad) / bitmap.width, (h - 2 * pad) / bitmap.height)
            val iw = bitmap.width * scale
            val ih = bitmap.height * scale
            image.set((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2)
            crop.set(image)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawBitmap(bitmap, null, image, null)
            // 範囲の外を暗くする
            canvas.drawRect(image.left, image.top, image.right, crop.top, dim)
            canvas.drawRect(image.left, crop.bottom, image.right, image.bottom, dim)
            canvas.drawRect(image.left, crop.top, crop.left, crop.bottom, dim)
            canvas.drawRect(crop.right, crop.top, image.right, crop.bottom, dim)
            canvas.drawRect(crop, frame)
            for ((x, y) in corners()) canvas.drawCircle(x, y, 8 * density, handle)
        }

        private fun corners() = listOf(crop.left to crop.top, crop.right to crop.top, crop.left to crop.bottom, crop.right to crop.bottom)

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val reach = 32 * density
                    grab = corners().indexOfFirst { (x, y) -> kotlin.math.hypot(x - e.x, y - e.y) < reach }
                    if (grab < 0 && crop.contains(e.x, e.y)) grab = 4
                    lastX = e.x
                    lastY = e.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x
                    lastY = e.y
                    val minSize = 32 * density
                    when (grab) {
                        0 -> { crop.left = (crop.left + dx).coerceIn(image.left, crop.right - minSize); crop.top = (crop.top + dy).coerceIn(image.top, crop.bottom - minSize) }
                        1 -> { crop.right = (crop.right + dx).coerceIn(crop.left + minSize, image.right); crop.top = (crop.top + dy).coerceIn(image.top, crop.bottom - minSize) }
                        2 -> { crop.left = (crop.left + dx).coerceIn(image.left, crop.right - minSize); crop.bottom = (crop.bottom + dy).coerceIn(crop.top + minSize, image.bottom) }
                        3 -> { crop.right = (crop.right + dx).coerceIn(crop.left + minSize, image.right); crop.bottom = (crop.bottom + dy).coerceIn(crop.top + minSize, image.bottom) }
                        4 -> {
                            val ox = dx.coerceIn(image.left - crop.left, image.right - crop.right)
                            val oy = dy.coerceIn(image.top - crop.top, image.bottom - crop.bottom)
                            crop.offset(ox, oy)
                        }
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> grab = -1
            }
            return true
        }

        /** 範囲を、元の画像の大きさで切り出す */
        fun cropped(): Bitmap {
            val scale = bitmap.width / image.width()
            val x = ((crop.left - image.left) * scale).toInt().coerceIn(0, bitmap.width - 1)
            val y = ((crop.top - image.top) * scale).toInt().coerceIn(0, bitmap.height - 1)
            val w = max(1, (crop.width() * scale).toInt()).coerceAtMost(bitmap.width - x)
            val h = max(1, (crop.height() * scale).toInt()).coerceAtMost(bitmap.height - y)
            return Bitmap.createBitmap(bitmap, x, y, w, h)
        }
    }

    private companion object {
        /** 機密のコピーを伏せて出すときの仮の項目の id（履歴には入れない） */
        const val SENSITIVE_ID = "sensitive"
    }
}
