package dev.mooma.kanade.settings

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kasane.KasaneShapes
import kasane.kasaneDarkColorScheme
import kasane.kasaneLightColorScheme

/**
 * 設定画面のテーマ。Material 3 Expressive（端末の壁紙の色に合わせる）。
 * Android 12 より前は kasane の控えの色（アイコンの色が種）。角丸は kasane（KasaneShapes）
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KanadeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> kasaneDarkColorScheme()
        else -> kasaneLightColorScheme()
    }
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), shapes = KasaneShapes, content = content)
}
