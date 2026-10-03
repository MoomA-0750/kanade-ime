package dev.mooma.kanade

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * IME の画面（入力欄の下に出す部分）の入れ物。上から「パネル（絵文字・クリップボード）」「ツールバー」「キーボード」と並べ、
 * 今の場面に合わせて出し分ける。
 * - キーボード: ツールバー＋キーボード（QWERTY かフリック）
 * - 絵文字・クリップボード: パネルだけ（ツールバー＋キーボードと同じ高さ）
 * - 絵文字の検索: パネル（検索の欄と結果の1行）＋キーボード
 */
@SuppressLint("ViewConstructor")
class InputViewHost(
    context: Context,
    val toolbar: KeyboardToolbar,
    private val qwerty: SoftKeyboardView,
    private val flick: View,
    val emoji: EmojiPanelView,
    val clipboard: View,
) : LinearLayout(context) {
    enum class Mode { KEYBOARD, EMOJI, EMOJI_SEARCH, CLIPBOARD }

    var mode = Mode.KEYBOARD
        private set

    /** フリック配列を使うか（使わなければ QWERTY） */
    var flickLayout = false
        set(value) {
            field = value
            toolbar.flick = value
            show(mode)
        }

    private val panels = FrameLayout(context)
    private val keyboards = FrameLayout(context)

    /** 今のキーボード（QWERTY かフリック） */
    val keyboard: View get() = if (flickLayout) flick else qwerty

    init {
        orientation = VERTICAL
        // パネルの下の余白（ナビゲーションバーの分）も、キーボードと同じ地の色で塗る
        panels.setBackgroundColor(KeyboardPalette.of(context).background)
        panels.addView(emoji, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panels.addView(clipboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        keyboards.addView(qwerty)
        keyboards.addView(flick)
        addView(panels, LayoutParams(LayoutParams.MATCH_PARENT, 0))
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, (KeyboardToolbar.HEIGHT_DP * resources.displayMetrics.density).toInt()))
        addView(keyboards, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        show(Mode.KEYBOARD)
    }

    /** キーボードの下に空けている高さ（ナビゲーションバーなど）。パネルも同じだけ空ける */
    private fun bottomSpace() = qwerty.bottomSpace

    fun show(next: Mode) {
        // パネルの高さは、ツールバー＋キーボードの高さ（出ている今の高さを使う）
        val fullHeight = if (mode == Mode.KEYBOARD && toolbar.height > 0) toolbar.height + keyboards.height else lastFullHeight
        if (fullHeight > 0) lastFullHeight = fullHeight
        mode = next
        qwerty.visibility = if (flickLayout) GONE else VISIBLE
        flick.visibility = if (flickLayout) VISIBLE else GONE
        when (next) {
            Mode.KEYBOARD -> {
                panels.visibility = GONE
                toolbar.visibility = VISIBLE
                keyboards.visibility = VISIBLE
            }
            Mode.EMOJI, Mode.CLIPBOARD -> {
                emoji.visibility = if (next == Mode.EMOJI) VISIBLE else GONE
                clipboard.visibility = if (next == Mode.CLIPBOARD) VISIBLE else GONE
                panels.visibility = VISIBLE
                panels.setPadding(0, 0, 0, bottomSpace())
                panels.layoutParams = panels.layoutParams.apply { height = lastFullHeight.takeIf { it > 0 } ?: LayoutParams.WRAP_CONTENT }
                toolbar.visibility = GONE
                keyboards.visibility = GONE
            }
            Mode.EMOJI_SEARCH -> {
                emoji.visibility = VISIBLE
                clipboard.visibility = GONE
                panels.visibility = VISIBLE
                panels.setPadding(0, 0, 0, 0)
                panels.layoutParams = panels.layoutParams.apply { height = LayoutParams.WRAP_CONTENT }
                toolbar.visibility = GONE
                keyboards.visibility = VISIBLE
            }
        }
    }

    private var lastFullHeight = 0
}
