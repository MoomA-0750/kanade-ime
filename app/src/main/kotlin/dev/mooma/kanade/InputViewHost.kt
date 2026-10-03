package dev.mooma.kanade

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * IME の画面（入力欄の下に出す部分）の入れ物。上に「パネル（絵文字・クリップボード）」、下に「キーボード（QWERTY かフリック）」を置き、
 * 今の場面に合わせて出し分ける（ツールバーは置かない。絵文字などはキーを払って開く）。
 * - キーボード: キーボードだけ
 * - 絵文字・クリップボード: パネルだけ（キーボードと同じ高さ）
 * - 絵文字の検索: パネル（検索の欄と結果の1行）＋キーボード
 */
@SuppressLint("ViewConstructor")
class InputViewHost(
    context: Context,
    private val qwerty: SoftKeyboardView,
    private val flick: FlickKeyboardView,
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
            show(mode)
        }

    private val panels = FrameLayout(context)
    private val keyboards = FrameLayout(context)
    private var lastKeyboardHeight = 0

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
        addView(keyboards, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        show(Mode.KEYBOARD)
    }

    /** キーボードの下に空けている高さ（ナビゲーションバーなど）。パネルも同じだけ空ける */
    private fun bottomSpace() = if (flickLayout) flick.bottomSpace else qwerty.bottomSpace

    fun show(next: Mode) {
        // パネルの高さは、キーボードの高さ（出ている今の高さを使う）
        if (mode == Mode.KEYBOARD && keyboards.height > 0) lastKeyboardHeight = keyboards.height
        mode = next
        qwerty.visibility = if (flickLayout) GONE else VISIBLE
        flick.visibility = if (flickLayout) VISIBLE else GONE
        when (next) {
            Mode.KEYBOARD -> {
                panels.visibility = GONE
                keyboards.visibility = VISIBLE
            }
            Mode.EMOJI, Mode.CLIPBOARD -> {
                emoji.visibility = if (next == Mode.EMOJI) VISIBLE else GONE
                clipboard.visibility = if (next == Mode.CLIPBOARD) VISIBLE else GONE
                panels.visibility = VISIBLE
                panels.setPadding(0, 0, 0, bottomSpace())
                panels.layoutParams = panels.layoutParams.apply { height = lastKeyboardHeight.takeIf { it > 0 } ?: LayoutParams.WRAP_CONTENT }
                keyboards.visibility = GONE
            }
            Mode.EMOJI_SEARCH -> {
                emoji.visibility = VISIBLE
                clipboard.visibility = GONE
                panels.visibility = VISIBLE
                panels.setPadding(0, 0, 0, 0)
                panels.layoutParams = panels.layoutParams.apply { height = LayoutParams.WRAP_CONTENT }
                keyboards.visibility = VISIBLE
            }
        }
    }
}
