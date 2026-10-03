package dev.mooma.kanade.settings

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** 設定画面のテーマ。Material 3 Expressive（端末の壁紙の色に合わせる。Android 12 より前は固定の色） */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KanadeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFFB8C4FF), secondary = Color(0xFFC3C5DD), tertiary = Color(0xFFE5BAD8))
        else -> lightColorScheme(primary = Color(0xFF4C5C92), secondary = Color(0xFF5A5D72), tertiary = Color(0xFF75546F))
    }
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), content = content)
}
