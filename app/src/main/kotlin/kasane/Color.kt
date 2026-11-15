// 書き出したもの。直すのは kasane の tokens.css と themes/personal.css（scripts/build.mjs）
// kasane 1.1.0  source abfa0d9adc4d4ff1  seed #4C5C92 #4C5C92
package kasane

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** 動的色が無いとき（Android 11 以前）の控えの色。kasane の personal を種の色から導いたもの */
fun kasaneLightColorScheme(): ColorScheme = lightColorScheme(
    primary = Color(0xFF4C5C92),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E6F0),
    onPrimaryContainer = Color(0xFF3A487C),
    error = Color(0xFFC5293E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF7E7EB),
    onErrorContainer = Color(0xFFC5293E),
    background = Color(0xFFF2F4FA),
    onBackground = Color(0xFF272B35),
    surface = Color(0xFFFDFDFF),
    onSurface = Color(0xFF272B35),
    surfaceVariant = Color(0xFFE9EDF6),
    onSurfaceVariant = Color(0xFF555A6A),
    outline = Color(0xFF7F8494),
    outlineVariant = Color(0xFFD9DCE4),
)

fun kasaneDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = Color(0xFF899CD7),
    onPrimary = Color(0xFF121620),
    primaryContainer = Color(0xFF2D313D),
    onPrimaryContainer = Color(0xFF9EB2EE),
    error = Color(0xFFFF757B),
    onError = Color(0xFF2B0B09),
    errorContainer = Color(0xFF35282D),
    onErrorContainer = Color(0xFFFF757B),
    background = Color(0xFF16171B),
    onBackground = Color(0xFFE5E8EE),
    surface = Color(0xFF1E1F24),
    onSurface = Color(0xFFE5E8EE),
    surfaceVariant = Color(0xFF24262D),
    onSurfaceVariant = Color(0xFFADB1BB),
    outline = Color(0xFF62656D),
    outlineVariant = Color(0xFF2C2D32),
)
