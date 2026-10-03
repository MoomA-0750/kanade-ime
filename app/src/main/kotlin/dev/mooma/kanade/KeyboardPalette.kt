package dev.mooma.kanade

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build

/**
 * 画面キーボードの色（Material 3 の色の役割に合わせる）。Android 12 以降は端末の壁紙の色（設定画面と同じ動的な色）、
 * それより前は設定画面の固定の色に近いもの。
 */
class KeyboardPalette(
    /** キーボードの地（surfaceContainer にあたる） */
    val background: Int,
    /** 文字のキー */
    val key: Int,
    /** 修飾・Esc・Tab・⌫ などの機能のキー（secondaryContainer にあたる） */
    val functionKey: Int,
    val pressed: Int,
    val text: Int,
    /** 隅の小さな文字 */
    val dim: Int,
    /** 1打だけ効く修飾 */
    val modOnce: Int,
    val onModOnce: Int,
    /** 固定した修飾・Enter */
    val accent: Int,
    val onAccent: Int,
    /** 聞き続けるモードの 🎤 */
    val voice: Int,
    val onVoice: Int,
) {
    companion object {
        fun of(context: Context): KeyboardPalette {
            val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // system_<色>_<明るさ>。0 が白、1000 が黒（Material の tonal palette の 100〜0）
                fun c(id: Int) = context.getColor(id)
                return if (dark) KeyboardPalette(
                    background = c(android.R.color.system_neutral1_900), key = c(android.R.color.system_neutral1_800), functionKey = c(android.R.color.system_accent2_700),
                    pressed = c(android.R.color.system_neutral1_600), text = c(android.R.color.system_neutral1_100), dim = c(android.R.color.system_neutral2_200),
                    modOnce = c(android.R.color.system_accent1_700), onModOnce = c(android.R.color.system_accent1_100),
                    accent = c(android.R.color.system_accent1_200), onAccent = c(android.R.color.system_accent1_800),
                    voice = c(android.R.color.system_accent3_200), onVoice = c(android.R.color.system_accent3_800),
                ) else KeyboardPalette(
                    background = c(android.R.color.system_neutral1_100), key = c(android.R.color.system_neutral1_10), functionKey = c(android.R.color.system_accent2_100),
                    pressed = c(android.R.color.system_neutral1_200), text = c(android.R.color.system_neutral1_900), dim = c(android.R.color.system_neutral2_700),
                    modOnce = c(android.R.color.system_accent1_100), onModOnce = c(android.R.color.system_accent1_900),
                    accent = c(android.R.color.system_accent1_600), onAccent = c(android.R.color.system_neutral1_0),
                    voice = c(android.R.color.system_accent3_600), onVoice = c(android.R.color.system_neutral1_0),
                )
            }
            return if (dark) KeyboardPalette(
                background = Color.rgb(0x1A, 0x1B, 0x21), key = Color.rgb(0x2F, 0x30, 0x36), functionKey = Color.rgb(0x42, 0x45, 0x59),
                pressed = Color.rgb(0x5D, 0x5E, 0x66), text = Color.rgb(0xE3, 0xE1, 0xE9), dim = Color.rgb(0xC6, 0xC5, 0xD0),
                modOnce = Color.rgb(0x34, 0x44, 0x79), onModOnce = Color.rgb(0xDC, 0xE1, 0xFF),
                accent = Color.rgb(0xB8, 0xC4, 0xFF), onAccent = Color.rgb(0x1C, 0x2D, 0x61),
                voice = Color.rgb(0xE5, 0xBA, 0xD8), onVoice = Color.rgb(0x44, 0x26, 0x3F),
            ) else KeyboardPalette(
                background = Color.rgb(0xE3, 0xE1, 0xE9), key = Color.rgb(0xFE, 0xFB, 0xFF), functionKey = Color.rgb(0xDF, 0xE1, 0xF9),
                pressed = Color.rgb(0xC7, 0xC6, 0xCD), text = Color.rgb(0x1A, 0x1B, 0x21), dim = Color.rgb(0x45, 0x46, 0x4F),
                modOnce = Color.rgb(0xDC, 0xE1, 0xFF), onModOnce = Color.rgb(0x04, 0x17, 0x4B),
                accent = Color.rgb(0x4C, 0x5C, 0x92), onAccent = Color.WHITE,
                voice = Color.rgb(0x75, 0x54, 0x6F), onVoice = Color.WHITE,
            )
        }
    }
}
