package dev.mooma.kanade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * 画面キーボードの上のツールバー（Gboard と同じ考え方）。絵文字・クリップボード・配列の切り替え（QWERTY／フリック）・
 * 音声入力（押している間だけ録音、短く押すと聞き続ける）・設定。色は画面キーボードと同じ。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class KeyboardToolbar(
    context: Context,
    onEmoji: () -> Unit,
    onClipboard: () -> Unit,
    onSwitchLayout: () -> Unit,
    onVoice: (Boolean) -> Unit,
    onSettings: () -> Unit,
) : LinearLayout(context) {
    private val palette = KeyboardPalette.of(context)
    private val density = resources.displayMetrics.density
    private val layoutButton: ImageView
    private val micButton: ImageView

    /** 今の配列がフリックか（切り替えのアイコンを、切り替え先の形にする） */
    var flick = false
        set(value) {
            field = value
            layoutButton.setImageResource(if (value) R.drawable.sym_keyboard else R.drawable.sym_dialpad)
            layoutButton.contentDescription = if (value) "QWERTY に切り替え" else "フリックに切り替え"
        }

    /** 聞き続けるモード中（マイクの丸を色付きにする） */
    var voiceActive = false
        set(value) {
            field = value
            micButton.background = circle(if (value) palette.voice else 0)
            micButton.setColorFilter(if (value) palette.onVoice else palette.text)
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(palette.background)
        val pad = (6 * density).toInt()
        setPadding(pad, 0, pad, 0)
        button(R.drawable.sym_mood, "絵文字") { onEmoji() }
        button(R.drawable.sym_content_paste, "クリップボード") { onClipboard() }
        layoutButton = button(R.drawable.sym_dialpad, "フリックに切り替え") { onSwitchLayout() }
        button(R.drawable.sym_settings, "Kanade の設定") { onSettings() }
        micButton = button(R.drawable.sym_mic, "音声入力") {}
        micButton.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                    onVoice(true)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onVoice(false)
            }
            true
        }
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun button(icon: Int, description: String, onClick: () -> Unit): ImageView {
        val v = ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(palette.text)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = description
            // 押したときに丸く色が付く（Material の状態の層）
            foreground = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(palette.pressed), null, circle(0xFF000000.toInt()),
            )
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
        addView(v, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        return v
    }

    companion object {
        const val HEIGHT_DP = 44
    }
}
