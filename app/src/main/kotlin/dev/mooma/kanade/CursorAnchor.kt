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

    /**
     * 画面キーボードを出しているとき、その上端（画面の座標）。重ねる窓は IME の窓の下になって隠れるので、ここより上に置く
     */
    var keyboardTop: () -> Int? = { null }

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
     * カーソルが分からなければ（Termux など）画面下の中央。画面キーボードを出していれば、その上端を画面の下として扱う
     */
    fun place(context: Context, wm: WindowManager, w: Int, h: Int, a: RectF?): Pair<Int, Int> {
        val screen = wm.currentWindowMetrics.bounds
        val density = context.resources.displayMetrics.density
        val gap = (4 * density).toInt()
        val keyboard = keyboardTop()
        val bottom = keyboard ?: screen.height()
        if (a == null) {
            val margin = if (keyboard != null) gap else (48 * density).toInt()
            return maxOf(0, (screen.width() - w) / 2) to maxOf(0, bottom - h - margin)
        }
        val x = a.left.toInt().coerceIn(0, maxOf(0, screen.width() - w))
        val below = a.bottom.toInt() + gap
        val y = if (below + h <= bottom) below else maxOf(0, minOf(a.top.toInt(), bottom) - h - gap)
        return x to y
    }
}
