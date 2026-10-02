package dev.mooma.kanade

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo

/**
 * アプリから届くカーソルの位置（CursorAnchorInfo）を持ち、重ねる窓をカーソルの近くに置く。
 * 候補の窓と、日本語オン/オフの表示が使う。
 */
class CursorAnchor {
    private var info: CursorAnchorInfo? = null

    fun update(info: CursorAnchorInfo?) {
        this.info = info
    }

    fun clear() {
        info = null
    }

    /** 未確定文字列の [offset] 文字目の枠（画面の座標）。未確定がなければ挿入位置。分からなければ null */
    fun rect(offset: Int = 0): RectF? {
        val i = info ?: return null
        val local = i.composingTextStart.takeIf { it >= 0 }?.let { i.getCharacterBounds(it + offset) ?: i.getCharacterBounds(it) }
            ?: i.insertionMarkerHorizontal.takeIf { !it.isNaN() }?.let {
                RectF(it, i.insertionMarkerTop, it, i.insertionMarkerBottom)
            }
        return local?.let { RectF(it).also { r -> Matrix(i.matrix).mapRect(r) } }
    }

    /**
     * 大きさ [w]×[h] の窓の左上の位置。カーソルの行の下に出し、はみ出すなら行の上に出す。
     * カーソルが分からなければ画面下の中央
     */
    fun place(context: Context, wm: WindowManager, w: Int, h: Int, a: RectF?): Pair<Int, Int> {
        val screen = wm.currentWindowMetrics.bounds
        val density = context.resources.displayMetrics.density
        val gap = (4 * density).toInt()
        if (a == null) {
            return maxOf(0, (screen.width() - w) / 2) to screen.height() - h - (48 * density).toInt()
        }
        val x = a.left.toInt().coerceIn(0, maxOf(0, screen.width() - w))
        val below = a.bottom.toInt() + gap
        val y = if (below + h <= screen.height()) below else maxOf(0, a.top.toInt() - h - gap)
        return x to y
    }
}
