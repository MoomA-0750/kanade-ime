package dev.mooma.kanade

import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/** 画面キーボードの下に空ける高さ（ナビゲーションバー・押せる要素）を窓の余白から決める */
object NavBarInsets {
    /** 余白の値が変わったときに知らせる先（キー記録に残して、端末ごとの挙動を調べる） */
    var onChanged: ((String) -> Unit)? = null

    /**
     * ナビゲーションバーは「見えていない」と報告されているときも、その高さを空ける。
     * 3ボタンのとき、表示の切り替えの途中などで見えていない扱いの余白が届くことがあり、
     * そのまま 0 にするとバーがキーの下の段に重なったままになるため（隠したときに少し余るのは許す）
     */
    fun bottom(insets: WindowInsets): Int {
        val nav = WindowInsets.Type.navigationBars()
        return maxOf(
            insets.getInsets(nav or WindowInsets.Type.tappableElement()).bottom,
            insets.getInsetsIgnoringVisibility(nav).bottom,
        )
    }

    /**
     * 窓の余白の知らせを待たずに、今の画面の大きさから求める。回転などで画面キーボードを作り直したとき、
     * 最初に 0 で並べると、あとで正しい値が届いても窓が広がらず、下がナビゲーションバーの下で切れることがあった（Fold 7）
     */
    fun current(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        val wm = context.getSystemService(WindowManager::class.java) ?: return 0
        return runCatching { bottom(wm.currentWindowMetrics.windowInsets) }.getOrDefault(0)
    }

    fun describe(insets: WindowInsets): String {
        val nav = WindowInsets.Type.navigationBars()
        return "nav=${insets.getInsets(nav).bottom} navAll=${insets.getInsetsIgnoringVisibility(nav).bottom}" +
            " navVisible=${insets.isVisible(nav)} tap=${insets.getInsets(WindowInsets.Type.tappableElement()).bottom}"
    }
}

/**
 * 画面キーボードの下に空ける高さを、窓の余白の知らせから保つ（QWERTY とフリックで共通）。
 * 増えたときはすぐに使い、減ったときは少し待っても同じなら使う。Fold 7 では回転の直後に
 * 「ナビゲーションバーなし（0）」の知らせが一瞬だけ届き、それで 0 で並べると、すぐ正しい値が届いても
 * 窓が広がらず、キーの下の段がバーの下に入ったままになった
 */
class BottomInsetTracker(private val view: View, private val name: String) {
    var value = NavBarInsets.current(view.context)
        private set
    private var pending = 0
    private val drop = Runnable { set(pending, "") }

    fun update(insets: WindowInsets) {
        val bottom = NavBarInsets.bottom(insets)
        view.removeCallbacks(drop)
        when {
            bottom > value -> set(bottom, NavBarInsets.describe(insets))
            bottom < value -> {
                NavBarInsets.onChanged?.invoke("$name bottom $value->$bottom wait ${NavBarInsets.describe(insets)}")
                pending = bottom
                view.postDelayed(drop, DROP_DELAY_MS)
            }
        }
    }

    /** 窓の今の余白を読み直す（付いたとき・キーボードを出すたびに。古い値が残らないように） */
    fun refresh() {
        view.rootWindowInsets?.let { update(it) }
        view.requestApplyInsets()
    }

    private fun set(bottom: Int, detail: String) {
        if (bottom == value) return
        NavBarInsets.onChanged?.invoke("$name bottom $value->$bottom $detail")
        value = bottom
        view.requestLayout()
    }

    private companion object {
        const val DROP_DELAY_MS = 500L
    }
}
