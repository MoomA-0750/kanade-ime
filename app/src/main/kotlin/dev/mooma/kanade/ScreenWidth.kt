package dev.mooma.kanade

import android.content.Context

/**
 * 画面キーボードを「広い画面」の並び（フリックを左右に分ける、絵文字の検索をタブの横に、クリップボードを4列に）にするか。
 * dp だけで決めると、表示の大きさを小さくした細い画面（Fold の閉じた画面など）でも広いことになるので、実際の幅（インチ）も見る
 */
object ScreenWidth {
    private const val WIDE_DP = 600
    private const val WIDE_INCHES = 3.6f

    fun isWide(context: Context, widthPx: Int = context.resources.displayMetrics.widthPixels): Boolean {
        val m = context.resources.displayMetrics
        val dp = widthPx / m.density
        val inches = widthPx / m.xdpi
        return dp >= WIDE_DP && inches >= WIDE_INCHES
    }
}
