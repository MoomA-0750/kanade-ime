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
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * クリップボードのパネル。ピン留めしたものと最近コピーしたものを並べる。
 * 押すと貼り付け、長押しでメニュー（ピン留め・外す、画像なら切り抜き、削除）。切り抜きはパネルの中で行い、新しい項目として足す。
 */
@SuppressLint("ViewConstructor")
class ClipboardPanelView(
    context: Context,
    private val history: ClipboardHistory,
    private val onPaste: (ClipboardHistory.Item) -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {
    private val palette = KeyboardPalette.of(context)
    private val density = resources.displayMetrics.density
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val message = TextView(context)

    private fun dp(v: Int) = (v * density).toInt()

    init {
        setBackgroundColor(palette.background)
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // 見出し: 戻る・題・最近のものを消す
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.sym_arrow_back, "キーボードに戻る") { onClose() }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(TextView(context).apply {
                text = "クリップボード"
                setTextColor(palette.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(icon(R.drawable.sym_delete_sweep, "ピン留めしていないものを消す") {
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
        val items = history.all()
        if (items.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "コピーした文字や画像が、ここに出ます。押すと貼り付け、長押しでピン留め・切り抜き・削除ができます。"
                setTextColor(palette.dim)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
            return
        }
        val pinned = items.filter { it.pinned }
        val recent = items.filter { !it.pinned }
        if (pinned.isNotEmpty()) section("ピン留め", pinned)
        if (recent.isNotEmpty()) section("最近", recent)
    }

    private fun section(title: String, items: List<ClipboardHistory.Item>) {
        list.addView(TextView(context).apply {
            text = title
            setTextColor(palette.accent)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(16), dp(8), dp(16), dp(4))
        })
        // 幅に合わせて列の数を決める（スマホは2列、広い画面は4列）
        val columns = if (ScreenWidth.isWide(context)) 4 else 2
        val grid = GridLayout(context).apply {
            columnCount = columns
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        for (item in items) {
            // 列の幅は画面の幅から決める（1件だけでも横いっぱいに伸ばさない）
            val cellWidth = (resources.displayMetrics.widthPixels - dp(16)) / columns - dp(8)
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
                text = item.text
                setTextColor(palette.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
        }
        view.background = bg
        view.foreground = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(palette.pressed), null, bg)
        view.setOnClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            onPaste(item)
        }
        view.setOnLongClickListener {
            menu(it, item)
            true
        }
        if (!item.pinned) return view
        // ピン留めの印
        return FrameLayout(context).apply {
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.sym_push_pin_fill)
                setColorFilter(palette.accent)
            }, LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(6), dp(6), 0) })
        }
    }

    private fun menu(anchor: View, item: ClipboardHistory.Item) {
        PopupMenu(context, anchor).apply {
            menu.add(0, 1, 0, if (item.pinned) "ピン留めを外す" else "ピン留めする")
            if (item.image != null) menu.add(0, 2, 1, "切り抜く")
            menu.add(0, 3, 2, "削除")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> history.setPinned(item.id, !item.pinned)
                    2 -> crop(item)
                    3 -> history.delete(item.id)
                }
                refresh()
                true
            }
            show()
        }
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
            addView(textButton("やめる") { this@ClipboardPanelView.removeView(overlay) })
            addView(textButton("保存") {
                history.addBitmap(cropView.cropped())
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
}
