package dev.mooma.kanade.core

/**
 * ローマ字→ひらがなの対応表。Google 日本語入力・MS-IME の既定に近い綴りを集めた。
 * 「n」単独と子音の重ね（っ）は表ではなく [RomajiConverter] の規則で扱う。
 */
object RomajiTable {
    val entries: Map<String, String> = buildMap {
        // 母音
        put("a", "あ"); put("i", "い"); put("u", "う"); put("e", "え"); put("o", "お")

        fun row(consonant: String, kana: String) {
            // kana は「あいうえお」順の5文字
            "aiueo".forEachIndexed { i, v -> put(consonant + v, kana[i].toString()) }
        }
        row("k", "かきくけこ")
        row("s", "さしすせそ")
        row("t", "たちつてと")
        row("n", "なにぬねの")
        row("h", "はひふへほ")
        row("m", "まみむめも")
        row("r", "らりるれろ")
        row("g", "がぎぐげご")
        row("z", "ざじずぜぞ")
        row("d", "だぢづでど")
        row("b", "ばびぶべぼ")
        row("p", "ぱぴぷぺぽ")
        row("c", "かしくせこ")
        row("l", "ぁぃぅぇぉ")
        row("x", "ぁぃぅぇぉ")
        put("ya", "や"); put("yu", "ゆ"); put("yo", "よ")
        put("wa", "わ"); put("wo", "を")

        // 例外的な綴り
        put("shi", "し"); put("chi", "ち"); put("tsu", "つ"); put("fu", "ふ"); put("ji", "じ")
        put("wi", "うぃ"); put("we", "うぇ"); put("wu", "う")
        put("ye", "いぇ"); put("yi", "い")
        put("qa", "くぁ"); put("qi", "くぃ"); put("qu", "く"); put("qe", "くぇ"); put("qo", "くぉ")
        put("va", "ゔぁ"); put("vi", "ゔぃ"); put("vu", "ゔ"); put("ve", "ゔぇ"); put("vo", "ゔぉ")

        // 拗音（子音+y+母音）
        fun youon(prefix: String, base: String) {
            put(prefix + "a", base + "ゃ"); put(prefix + "u", base + "ゅ"); put(prefix + "o", base + "ょ")
            put(prefix + "i", base + "ぃ"); put(prefix + "e", base + "ぇ")
        }
        youon("ky", "き"); youon("sy", "し"); youon("ty", "ち"); youon("cy", "ち"); youon("ny", "に")
        youon("hy", "ひ"); youon("my", "み"); youon("ry", "り"); youon("gy", "ぎ"); youon("zy", "じ")
        youon("jy", "じ"); youon("dy", "ぢ"); youon("by", "び"); youon("py", "ぴ"); youon("vy", "ゔ")
        youon("fy", "ふ")
        put("sha", "しゃ"); put("shu", "しゅ"); put("sho", "しょ"); put("she", "しぇ")
        put("cha", "ちゃ"); put("chu", "ちゅ"); put("cho", "ちょ"); put("che", "ちぇ")
        put("ja", "じゃ"); put("ju", "じゅ"); put("jo", "じょ"); put("je", "じぇ")

        // f 行・外来音
        put("fa", "ふぁ"); put("fi", "ふぃ"); put("fe", "ふぇ"); put("fo", "ふぉ")
        put("tsa", "つぁ"); put("tsi", "つぃ"); put("tse", "つぇ"); put("tso", "つぉ")
        put("tha", "てゃ"); put("thi", "てぃ"); put("thu", "てゅ"); put("the", "てぇ"); put("tho", "てょ")
        put("dha", "でゃ"); put("dhi", "でぃ"); put("dhu", "でゅ"); put("dhe", "でぇ"); put("dho", "でょ")
        put("twu", "とぅ"); put("dwu", "どぅ")
        put("wha", "うぁ"); put("whi", "うぃ"); put("whu", "う"); put("whe", "うぇ"); put("who", "うぉ")
        put("kwa", "くぁ"); put("gwa", "ぐぁ")

        // 小書き
        for (p in listOf("x", "l")) {
            put(p + "ya", "ゃ"); put(p + "yu", "ゅ"); put(p + "yo", "ょ")
            put(p + "tu", "っ"); put(p + "tsu", "っ"); put(p + "wa", "ゎ")
            put(p + "ka", "ゕ"); put(p + "ke", "ゖ")
        }
        put("xn", "ん"); put("nn", "ん"); put("n'", "ん")
    }

    /** 表のキーの接頭辞すべて。入力途中の綴りが続きうるかの判定に使う */
    val prefixes: Set<String> = buildSet {
        for (key in entries.keys) for (i in 1 until key.length) add(key.substring(0, i))
    }

    /** ひらがな1文字からローマ字1通りへの逆引き（F9/F10 で部分的に消えた綴りを復元するため） */
    val reverse: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        // 短い綴りを優先する（"si" より "shi" ではなく、表に先に入った方）
        for ((k, v) in entries.entries.sortedBy { it.key.length }) m.putIfAbsent(v, k)
        m["っ"] = "xtu"
        m
    }
}
