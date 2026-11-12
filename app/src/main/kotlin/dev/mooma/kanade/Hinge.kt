package dev.mooma.kanade

import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * 折りたたみの折り目（画面の座標）。
 * - [vertical]: 折り目が縦（本のように開いた Fold の縦向き）。半開きなら、画面キーボードを折り目の左右に分ける
 * - [halfOpened]: 半開き（L 字）。折り目が横なら、画面キーボードを画面の下から折り目まで伸ばす
 * 平らに開いているときは、どちらもしない
 * [windowBottom] は IME の窓の下端（画面の座標）
 */
data class Hinge(val screen: Rect, val vertical: Boolean, val halfOpened: Boolean, val windowBottom: Int) {
    /** 半開きで縦の折り目がこのビューを横切るとき、ビューの中での折り目の左端と右端（px）。そうでなければ null */
    fun splitIn(view: View): Pair<Float, Float>? {
        if (!vertical || !halfOpened || view.width == 0) return null
        val at = IntArray(2)
        view.getLocationOnScreen(at)
        val left = (screen.left - at[0]).toFloat()
        val right = (screen.right - at[0]).toFloat()
        if (right < 0f || left > view.width) return null
        return left to right
    }

    /** 半開きで折り目が横のとき、窓の下端から折り目までの高さ（px）。そうでなければ null */
    val heightBelow: Int?
        get() = if (!vertical && halfOpened) (windowBottom - screen.bottom).takeIf { it > 0 } else null

    companion object {
        /** 折り目の左右に空ける幅（dp）。折り目のすぐ横は、半開きで持つと押しにくい */
        const val MARGIN_DP = 12

        /** 左右に分けたとき、片側の幅の上限（画面の幅に対する割合）。真ん中は親指が届きにくい */
        const val MAX_SIDE_FRACTION = 0.42f

        /** 折り目まで伸ばしても、キーの高さはふだんの高さのこの割合より低くしない */
        const val MIN_FIT_ROW_FRACTION = 0.6f
    }
}

/**
 * 折り目に合わせた並びへの移り変わり。[split]・[stretch] は 0 がふだんの並び、1 が分けた・伸ばした並びで、
 * 折り曲げる・広げるとばね（Material 3 Expressive の空間の既定: 硬さ 380・減衰 0.8）で動く。
 * 動いている間は 1 を少し超えることがある（行き過ぎて戻る）
 */
class FoldMotion(private val onFrame: (stretching: Boolean) -> Unit) {
    var split = 0f
        private set
    var stretch = 0f
        private set

    /** 最後に分けたときの折り目の位置（広げて戻る途中もこの位置を使う） */
    var lastSplit: Pair<Float, Float>? = null
        private set

    /** 最後に伸ばしたときの高さ（窓の下から折り目まで、px） */
    var lastFit: Int? = null
        private set

    private inner class Channel(val set: (Float) -> Unit, val stretching: Boolean) {
        val holder = FloatValueHolder(0f)
        var target = 0f
        val anim = SpringAnimation(holder).apply {
            spring = SpringForce(0f).setStiffness(STIFFNESS).setDampingRatio(DAMPING)
            minimumVisibleChange = DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE
            addUpdateListener { _, value, _ ->
                set(value)
                onFrame(stretching)
            }
            addEndListener { _, _, _, _ -> onFrame(stretching) }
        }

        fun go(to: Float, animate: Boolean) {
            if (to == target && (anim.isRunning || holder.value == to)) return
            target = to
            if (animate) {
                anim.animateToFinalPosition(to)
            } else {
                anim.cancel()
                holder.value = to
                set(to)
                onFrame(stretching)
            }
        }
    }

    private val splitChannel = Channel({ split = it }, stretching = false)
    private val stretchChannel = Channel({ stretch = it }, stretching = true)

    /**
     * 伸ばした高さの窓にしておくか（伸ばしている・伸ばす途中・戻る途中）。窓の高さは毎コマ変えず、
     * 伸ばすときは先に高くして中のキーを下から伸ばし、戻すときはキーを縮め終えてから低くする（毎コマ窓を変えると、アプリの画面が跳び、空いたコマが出る）
     */
    val stretchTall get() = lastFit != null && (stretchChannel.target == 1f || (stretchChannel.anim.isRunning && stretch > SETTLED))

    /** 分けた並びを描くか（分けている・分ける途中・戻る途中） */
    val splitting get() = lastSplit != null && (split != 0f || splitChannel.anim.isRunning)

    /** 折り目の今の様子を渡す。[animate] が false なら（キーボードを出したばかりなど）すぐその並びにする */
    fun update(targetSplit: Pair<Float, Float>?, targetFit: Int?, animate: Boolean) {
        if (targetSplit != null) lastSplit = targetSplit
        if (targetFit != null) lastFit = targetFit
        splitChannel.go(if (targetSplit != null) 1f else 0f, animate)
        stretchChannel.go(if (targetFit != null) 1f else 0f, animate)
    }

    fun cancel() {
        splitChannel.anim.cancel()
        stretchChannel.anim.cancel()
    }

    companion object {
        const val STIFFNESS = 380f
        const val DAMPING = 0.8f

        /** 戻す途中、残りがこれより小さくなったら窓を低くする（ばねが止まりきるのを待つと、空いた帯が残って見える） */
        const val SETTLED = 0.02f

        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        fun lerp(a: RectF, b: RectF, t: Float) =
            RectF(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))
    }
}
