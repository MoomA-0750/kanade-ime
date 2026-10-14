package dev.mooma.kanade

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 物理キーボードのときのクリップボード。画面キーボードと同じ [ClipboardPanelView] を、候補の窓と同じ「重ねる窓」に出す
 * （物理キーボードをつないだ Galaxy では IME の窓が出ないことがあるため。[OverlayBar] と同じ理由）。
 * フォーカスは取らないので、キーは IME に届いたまま。選ぶ・貼る・閉じるのキーは IME から [panel] を動かす。
 * 指でも画面キーボードのときと同じに使える（押して貼り付け、長押しでピン留め・編集・切り抜き・削除）
 *
 * [context] は IME の窓の Context（DeX では IME がいる画面に出すため）。
 */
class ClipboardOverlay(private val context: Context, private val anchor: CursorAnchor, val panel: ClipboardPanelView) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val frame = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            setColor(KeyboardPalette.of(context).background)
            cornerRadius = 16 * density
        }
        clipToOutline = true
        elevation = 8 * density
        addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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

    /** カーソルの近く（入らなければ上、カーソルが分からなければ画面下の中央）に出す */
    fun show() {
        val screen = wm.currentWindowMetrics.bounds
        // 画面キーボードのパネルと同じくらいの大きさ（広い画面では横に広げすぎない）
        val w = minOf(screen.width() - dp(16), dp(720))
        val maxH = minOf((screen.height() * 0.55f).toInt(), dp(340))
        panel.contentWidth = w
        panel.keyboardMode = true
        panel.refresh()
        // 項目が少なければ、その分だけの高さにする
        val h = minOf(maxH, panel.contentHeight(w))
        val (x, y) = anchor.place(context, wm, w, h, anchor.rect(0))
        params.width = w
        params.height = h
        params.x = x
        params.y = y
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

    private fun dp(v: Int) = (v * density).toInt()

    companion object {
        fun permitted(context: Context) = OverlayBar.permitted(context)
    }
}
