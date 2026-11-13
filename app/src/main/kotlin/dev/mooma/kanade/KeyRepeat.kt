package dev.mooma.kanade

/**
 * 長押しで繰り返すキー（Backspace・矢印など。画面キーボードと絵文字パネル）の、繰り返し始めるまでの待ちと間隔。
 * 設定（[KanadeSettings.repeatDelayMs]・[KanadeSettings.repeatIntervalMs]）をキーボードを出すたびに写す
 */
object KeyRepeat {
    const val DEFAULT_DELAY_MS = 400
    const val DEFAULT_INTERVAL_MS = 50
    val DELAY_RANGE = 150..1000
    val INTERVAL_RANGE = 20..200

    @Volatile var delayMs = DEFAULT_DELAY_MS.toLong()
    @Volatile var intervalMs = DEFAULT_INTERVAL_MS.toLong()
}
