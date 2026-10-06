package dev.mooma.kanade

/**
 * 画面キーボードの「配列×言語」（iOS の「キーボード」の一覧にあたる）。配列ごとに使う言語（日本語・英語・数字/記号）を設定で選べ、
 * あ/A（テンキーは「あa1」）で次へ、長押しで一覧から選ぶ
 */
data class KeyboardSource(val layout: String, val mode: String)

object KeyboardSources {
    /** 言語（JA: 日本語、EN: 英語、NUM: 数字/記号） */
    val MODES = listOf("JA", "EN", "NUM")

    /** その配列で選べる言語（フルキーは数字・記号を払いで出すので、数字/記号は選ばない） */
    fun choosableModes(layout: String): List<String> = if (layout == "QWERTY") listOf("JA", "EN") else MODES

    /**
     * あ/A で回る言語。グライド・フルキーは日本語と英語（数字/記号は ?123 のページ）、テンキーは日本語・英語・数字/記号の配列
     */
    fun cycleModes(settings: KanadeSettings, layout: String): List<String> {
        val modes = settings.layoutModes(layout)
        val cycle = if (layout == "FLICK") MODES else listOf("JA", "EN")
        return cycle.filter { it in modes }
    }

    /** 使える「配列×言語」の一覧（配列の順、言語の順） */
    fun all(settings: KanadeSettings): List<KeyboardSource> {
        val list = LAYOUTS.filter { it in settings.enabledLayouts }.flatMap { l -> cycleModes(settings, l).map { KeyboardSource(l, it) } }
        return list.ifEmpty { listOf(KeyboardSource(LAYOUTS.first { it in settings.enabledLayouts }, "JA")) }
    }

    /**
     * あ/A を押したときの次。いまの配列で回れる言語が2つ以上ならその中で次へ、1つだけなら次の配列へ
     * （例: テンキーは日本語だけ・グライドは英語だけなら、あa1 でグライドの英語、あ/A でテンキーの日本語）
     */
    fun next(settings: KanadeSettings, current: KeyboardSource): KeyboardSource {
        val within = cycleModes(settings, current.layout)
        if (within.size >= 2) {
            val i = within.indexOf(current.mode)
            return KeyboardSource(current.layout, within[(i + 1).mod(within.size)])
        }
        val all = all(settings)
        // いまの配列の最後の項目の次（いまの配列が一覧に無ければ最初）
        val last = all.indexOfLast { it.layout == current.layout }
        return all[(last + 1).mod(all.size)]
    }

    /** 日本語（または英語）を使える最初の「配列×言語」。いまの配列で使えるならそれ */
    fun forMode(settings: KanadeSettings, currentLayout: String, mode: String): KeyboardSource? {
        if (mode in cycleModes(settings, currentLayout)) return KeyboardSource(currentLayout, mode)
        return all(settings).firstOrNull { it.mode == mode }
    }
}
