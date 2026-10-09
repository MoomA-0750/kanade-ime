package dev.mooma.kanade

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View

/** View の performHapticFeedback には無い震え方 */
object Haptics {
    /**
     * 鈍く重い「ボコン」。端末が対応していれば THUD の素片、無ければ重いクリック、それも無ければ長押しの震え。
     * 端末の「タッチ操作時のバイブ」を切っているときは震わせない（performHapticFeedback と合わせる）
     */
    fun thud(view: View) {
        val vibrator = view.context.getSystemService(Vibrator::class.java)
        val touchFeedback = android.provider.Settings.System.getInt(
            view.context.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1,
        ) != 0
        if (vibrator == null || !vibrator.hasVibrator() || !touchFeedback) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            return
        }
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD)
        ) {
            VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD).compose()
        } else {
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
        }
        vibrator.vibrate(effect)
    }
}
