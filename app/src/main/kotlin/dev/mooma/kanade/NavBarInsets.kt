package dev.mooma.kanade

import android.view.WindowInsets

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

    fun describe(insets: WindowInsets): String {
        val nav = WindowInsets.Type.navigationBars()
        return "nav=${insets.getInsets(nav).bottom} navAll=${insets.getInsetsIgnoringVisibility(nav).bottom}" +
            " navVisible=${insets.isVisible(nav)} tap=${insets.getInsets(WindowInsets.Type.tappableElement()).bottom}"
    }
}
