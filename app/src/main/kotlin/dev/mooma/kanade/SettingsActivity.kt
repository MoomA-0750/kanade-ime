package dev.mooma.kanade

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import dev.mooma.kanade.settings.KanadeTheme
import dev.mooma.kanade.settings.SettingsApp

/**
 * 設定画面（Jetpack Compose、Material 3 Expressive）。中身は settings パッケージ。
 * 画面に戻るたびに権限・有効化の状態を読み直す（設定アプリで許可して戻ってきたとき）
 */
class SettingsActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KanadeTheme {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    SettingsApp(resumeTick.intValue)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}
