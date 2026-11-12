package dev.mooma.kanade

import android.graphics.Rect
import android.view.View

/**
 * 折りたたみの折り目（画面の座標）。
 * - [vertical]: 折り目が縦（本のように開いた Fold の縦向き）。画面キーボードを折り目の左右に分ける
 * - [halfOpened]: 半開き（L 字）。折り目が横なら、画面キーボードを画面の下から折り目まで伸ばす
 * [windowBottom] は IME の窓の下端（画面の座標）
 */
data class Hinge(val screen: Rect, val vertical: Boolean, val halfOpened: Boolean, val windowBottom: Int) {
    /** 縦の折り目がこのビューを横切るとき、ビューの中での折り目の左端と右端（px）。横切らなければ null */
    fun splitIn(view: View): Pair<Float, Float>? {
        if (!vertical || view.width == 0) return null
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
