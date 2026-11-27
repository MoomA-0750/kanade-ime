package dev.mooma.kanade.dictool

import dev.mooma.kanade.core.Brackets
import dev.mooma.kanade.core.CharWidth
import dev.mooma.kanade.core.RomajiConverter
import dev.mooma.kanade.core.RomajiTable
import dev.mooma.kanade.core.convert.Candidate
import dev.mooma.kanade.core.convert.KanaKanjiConverter
import dev.mooma.kanade.core.convert.Segmenter
import dev.mooma.kanade.core.dict.ConnectionMatrix
import dev.mooma.kanade.core.dict.PosTable
import dev.mooma.kanade.core.dict.ReverseDictionary
import dev.mooma.kanade.core.dict.SymbolTable
import dev.mooma.kanade.core.dict.SystemDictionary
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Random

/*
 * Rust 書き直しのフェーズ 1 の物差し（docs/RUST-PHASE1.md）。Kotlin の結果を testdata/nbest・testdata/romaji に記録し、
 * Rust の `kanade-tool nbest-check`・`romaji-check` が同じ入力を流して比べる。
 * 文字列は [esc] で書く（Rust 側も同じ決まりで書いて、文字列として比べる）。
 */

/** 記録の文字列の書き方。`\` タブ 改行 `|` `=` と制御文字・片割れの代用対を `\` で書く（ほかの文字はそのまま UTF-8） */
fun rcEsc(s: String): String = buildString {
    for ((i, c) in s.withIndex()) {
        when {
            c == '\\' -> append("\\\\")
            c == '\t' -> append("\\t")
            c == '\n' -> append("\\n")
            c == '|' -> append("\\|")
            c == '=' -> append("\\=")
            c.code < 0x20 || c.code == 0x7F -> append("\\u%04X".format(c.code))
            Character.isHighSurrogate(c) && !(i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) -> append("\\u%04X".format(c.code))
            Character.isLowSurrogate(c) && !(i > 0 && Character.isHighSurrogate(s[i - 1])) -> append("\\u%04X".format(c.code))
            else -> append(c)
        }
    }
}

private fun rcSha256(f: File): String =
    MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

private fun mapFile(f: File): ByteBuffer =
    FileChannel.open(f.toPath(), StandardOpenOption.READ).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }

/** 乱数の読み（ひらがなが多め。カタカナ・英数・漢字・記号・絵文字・長音を混ぜる） */
private fun randomReadings(seed: Long, count: Int): List<String> {
    val r = Random(seed)
    val pools = listOf(
        "ー" to 6, "、。！？" to 2, "アイウカキクサシスタチツテトナニヌネノハヒフヘホマミムメモラリルレロンーァッャュョヴ" to 4,
        "abcxyzABC019 -" to 2, "日本語漢字変換今朝々〆ヶ" to 2, "＋（）「」" to 1,
    )
    return List(count) {
        val len = 1 + r.nextInt(if (r.nextInt(10) == 0) 40 else 16)
        buildString {
            repeat(len) {
                val p = r.nextInt(100)
                when {
                    p < 80 -> append(('ぁ'.code + r.nextInt('ゖ'.code - 'ぁ'.code + 1)).toChar())
                    p < 82 -> appendCodePoint(0x1F600 + r.nextInt(0x50)) // 絵文字（代用対）
                    else -> {
                        val (pool, _) = pools[r.nextInt(pools.size)]
                        append(pool[r.nextInt(pool.length)])
                    }
                }
            }
        }
    }
}

/** 読みの出どころ。`<tsv>:<列>`（# の行は飛ばす）、`lines:<ファイル>`、`random:<種>:<数>` */
private fun readingsOf(source: String): List<String> = when {
    source.startsWith("random:") -> source.split(':').let { randomReadings(it[1].toLong(), it[2].toInt()) }
    source.startsWith("lines:") -> File(source.removePrefix("lines:")).readLines().filter { it.isNotEmpty() && !it.startsWith("#") }
    else -> {
        val i = source.lastIndexOf(':')
        val col = source.substring(i + 1).toInt()
        File(source.substring(0, i)).readLines().filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split('\t')[col] }
    }
}

private fun wordsField(c: Candidate) = c.words.joinToString("|") { "${it.lid},${it.rid},${it.cost},${rcEsc(it.surface)}" }

private fun segmentsField(segmenter: Segmenter, c: Candidate) = segmenter.segments(c.words).joinToString("|") { rcEsc(it.surface) }

/**
 * `nbest-dump <辞書フォルダ> <出力の tsv> <読みの出どころ> [名前]`。辞書だけの N-best（上位 10 件）と、接頭辞で探し直した結果を書く。
 * 形: `R 読み 件数`、続けて `C コスト 表記 語(lid,rid,cost,表記|…) 文節(表記|…)` を件数ぶん、`P 接頭辞 コスト 表記 語`（無ければコストは -）、
 * `E その読みの辞書の語(|…)`、`W 接頭辞 予測(読み=表記|…)`
 */
fun nbestDump(dictDir: File, out: File, source: String, name: String?) {
    val dict = SystemDictionary(mapFile(File(dictDir, "system.kdic")))
    val conn = ConnectionMatrix(mapFile(File(dictDir, "connection.kcon")))
    val converter = KanaKanjiConverter(dict, conn)
    val segmenter = Segmenter(PosTable.read(ByteBuffer.wrap(File(dictDir, "pos.kpos").readBytes())))
    val readings = readingsOf(source)
    val sb = StringBuilder()
    sb.append("# kanade nbest v1\n")
    sb.append("# source ").append(name ?: source).append('\n')
    sb.append("# n 10\n")
    for (f in listOf("system.kdic", "connection.kcon", "pos.kpos")) sb.append("# sha256 $f ${rcSha256(File(dictDir, f))}\n")
    for (reading in readings) {
        val result = converter.convert(reading, 10)
        sb.append("R\t").append(rcEsc(reading)).append('\t').append(result.size).append('\n')
        for (c in result) {
            sb.append("C\t").append(c.cost).append('\t').append(rcEsc(c.text)).append('\t').append(wordsField(c)).append('\t')
                .append(segmentsField(segmenter, c)).append('\n')
        }
        // 接頭辞で探し直す: 2位・3位の表記の先頭2字、2位の表記全体、どこにも無い接頭辞
        val prefixes = LinkedHashSet<String>()
        for (c in result.drop(1).take(2)) prefixes += c.text.take(2)
        result.getOrNull(1)?.let { prefixes += it.text }
        prefixes += "犬犬犬"
        for (p in prefixes) {
            val c = converter.convertWithPrefix(reading, p)
            sb.append("P\t").append(rcEsc(p)).append('\t')
            if (c == null) sb.append("-\t\t\n") else sb.append(c.cost).append('\t').append(rcEsc(c.text)).append('\t').append(wordsField(c)).append('\n')
        }
        // その読みの辞書の語（exactWords）と、読みの頭 2・3 字と全体からの予測（predictWords、品詞で絞る）
        sb.append("E\t").append(converter.exactWords(reading).joinToString("|") { rcEsc(it) }).append('\n')
        for (p in linkedSetOf(reading.take(2), reading.take(3), reading)) {
            val w = converter.predictWords(p, 10, segmenter::predictable)
            sb.append("W\t").append(rcEsc(p)).append('\t').append(w.joinToString("|") { rcEsc(it.first) + "=" + rcEsc(it.second) }).append('\n')
        }
    }
    out.parentFile?.mkdirs()
    out.writeText(sb.toString())
    // 速さ: 温めてから、全部の読みを 5 回ずつ変換した中央値
    repeat(3) { for (r in readings) converter.convert(r, 10) }
    val times = ArrayList<Double>()
    repeat(5) {
        for (r in readings) {
            val t = System.nanoTime()
            converter.convert(r, 10)
            times += (System.nanoTime() - t) / 1e6
        }
    }
    times.sort()
    println("${out.name}: ${readings.size} 読み。辞書だけの変換（10 件）の中央値 ${"%.3f".format(times[times.size / 2])}ms、95% ${"%.3f".format(times[times.size * 95 / 100])}ms")
}

/** `reverse-dump <辞書フォルダ> <出力> <出どころ>`。逆引き（reverse.kdic）で文の読みを求めた結果。`V 文 読み`（読めなければ -） */
fun reverseDump(dictDir: File, out: File, source: String, name: String?) {
    val rev = ReverseDictionary(SystemDictionary(mapFile(File(dictDir, "reverse.kdic"))))
    val texts = readingsOf(source).flatMap { it.split('|') }
    val sb = StringBuilder("# kanade reverse v1\n# source ${name ?: source}\n# sha256 reverse.kdic ${rcSha256(File(dictDir, "reverse.kdic"))}\n")
    for (t in texts) {
        val p = rev.pieces(t)
        sb.append("V\t").append(rcEsc(t)).append('\t')
        if (p == null) sb.append("-\n") else sb.append(p.joinToString("|") { rcEsc(it.surface) + "=" + rcEsc(it.reading) }).append('\n')
    }
    out.parentFile?.mkdirs()
    out.writeText(sb.toString())
    println("${out.name}: ${texts.size} 文")
}

/** `ksym-dump <辞書フォルダ> <出力>`。記号・絵文字・英語の表と品詞の表の大きさと、全部の読みの引いた結果のハッシュ */
fun ksymDump(dictDir: File, out: File) {
    val sb = StringBuilder("# kanade ksym v1\n")
    for (f in listOf("symbol.ksym", "emoji.ksym", "english.ksym")) {
        val text = File(dictDir, f).readText()
        val t = SymbolTable.parse(text)
        val keys = text.lineSequence().filter { it.isNotEmpty() }.map { it.split('\t')[0] }.distinct().sorted().toList()
        val md = MessageDigest.getInstance("SHA-256")
        for (k in keys) md.update((rcEsc(k) + "\t" + t.lookup(k).joinToString("\t") { rcEsc(it) } + "\n").toByteArray())
        sb.append("S\t$f\t${t.size}\t${md.digest().joinToString("") { "%02x".format(it) }}\n")
    }
    val pos = PosTable.read(ByteBuffer.wrap(File(dictDir, "pos.kpos").readBytes()))
    val n = File(dictDir, "pos.kpos").readBytes().size - 8
    sb.append("K\tpos.kpos\t$n\t")
    sb.append((0 until n + 2).joinToString("") { id -> "${pos.kind(id).ordinal}${if (pos.predictable(id)) "p" else "x"}" }).append('\n')
    out.parentFile?.mkdirs()
    out.writeText(sb.toString())
    println("${out.name}: 書いた")
}

/** ローマ字の入力の出どころ。`lines:<ファイル>` か `random:<種>:<数>`（英小文字が多め。大文字・数字・記号を混ぜる） */
private fun romajiInputs(source: String): List<String> {
    if (source.startsWith("lines:")) return File(source.removePrefix("lines:")).readLines().filter { it.isNotEmpty() && !it.startsWith("#") }
    val (_, seed, count) = source.split(':')
    val r = Random(seed.toLong())
    val letters = "aiueoaiueokstnhmyrwgzdbpcfjlqvx"
    val others = "nnnyyhhAKTN'-,.;:/[]!?1 0~|=\\"
    return List(count.toInt()) {
        val len = 1 + r.nextInt(24)
        buildString { repeat(len) { append(if (r.nextInt(10) < 8) letters[r.nextInt(letters.length)] else others[r.nextInt(others.length)]) } }
    }
}

private fun unitsField(units: List<dev.mooma.kanade.core.KanaUnit>) = units.joinToString("|") { rcEsc(it.text) + "=" + rcEsc(it.raw) }

/**
 * `romaji-dump <出力> <出どころ> [名前]`。1行に1つの入力: `I 入力 確定した単位(かな=ローマ字|…) 1字ごとの途中(|区切り) flush の単位`。
 * 単位は feed の戻り値を順につないだもの
 */
fun romajiDump(out: File, source: String, name: String?) {
    val inputs = romajiInputs(source)
    val sb = StringBuilder("# kanade romaji v1\n# source ${name ?: source}\n")
    for (input in inputs) {
        val conv = RomajiConverter()
        val units = ArrayList<dev.mooma.kanade.core.KanaUnit>()
        val pendings = ArrayList<String>()
        for (c in input) {
            units += conv.feed(c)
            pendings += conv.pending
        }
        val flushed = conv.flush()
        sb.append("I\t").append(rcEsc(input)).append('\t').append(unitsField(units)).append('\t')
            .append(pendings.joinToString("|") { rcEsc(it) }).append('\t').append(unitsField(flushed)).append('\n')
    }
    out.parentFile?.mkdirs()
    out.writeText(sb.toString())
    println("${out.name}: ${inputs.size} 入力")
}

/** `romaji-table-dump <出力>`。ローマ字の表・接頭辞・逆引きの全部（並びは表記の UTF-16 順） */
fun romajiTableDump(out: File) {
    val sb = StringBuilder("# kanade romaji-table v1\n")
    for ((k, v) in RomajiTable.entries.toSortedMap()) sb.append("E\t").append(rcEsc(k)).append('\t').append(rcEsc(v)).append('\n')
    for (p in RomajiTable.prefixes.sorted()) sb.append("X\t").append(rcEsc(p)).append('\n')
    for ((k, v) in RomajiTable.reverse.toSortedMap()) sb.append("V\t").append(rcEsc(k)).append('\t').append(rcEsc(v)).append('\n')
    out.parentFile?.mkdirs()
    out.writeText(sb.toString())
    println("${out.name}: 書いた")
}

/**
 * `width-dump <出力> <種> <数>`。でたらめな文字列に CharWidth と Brackets を当てた結果。
 * `W 入力 カタカナ ひらがな 全角 半角 半角カナ 全角の有無 半角の有無 漢字の印 閉じ括弧 括弧の対の位置 ほかの括弧`
 */
fun widthDump(out: File, seed: Long, count: Int) {
    val r = Random(seed)
    // 境目の前後を多めに入れる
    val edges = listOf(0x3040, 0x3041, 0x3096, 0x3097, 0x30A0, 0x30A1, 0x30F6, 0x30F7, 0x20, 0x21, 0x7E, 0x7F, 0x3000, 0xFF00, 0xFF01, 0xFF5E, 0xFF5F,
        0x4DFF, 0x4E00, 0x9FFF, 0xA000, 0x33FF, 0x3400, 0x4DBF, 0xF8FF, 0xF900, 0xFAFF, 0xFB00, 0x3005, 0x3006, 0x30FC, 0x3002, 0x300C, 0x300D, 0x3001, 0x30FB, 0x30F4)
    val brackets = Brackets.PAIRS.joinToString("")
    val inputs = ArrayList<String>()
    for (b in brackets) inputs += b.toString()
    inputs += Brackets.PAIRS
    repeat(count) {
        val len = 1 + r.nextInt(12)
        inputs += buildString {
            repeat(len) {
                when (r.nextInt(6)) {
                    0 -> append(edges[r.nextInt(edges.size)].toChar())
                    1 -> append((0x21 + r.nextInt(0x5E)).toChar())
                    2 -> append((0x3041 + r.nextInt(0xC0)).toChar())
                    3 -> append((0xFF01 + r.nextInt(0x9F)).toChar())
                    4 -> appendCodePoint(0x1F300 + r.nextInt(0x300))
                    else -> append(brackets[r.nextInt(brackets.length)])
                }
            }
        }
    }
    val sb = StringBuilder("# kanade width v1\n")
    for (s in inputs) {
        val f = listOf(
            CharWidth.toKatakana(s), CharWidth.toHiragana(s), CharWidth.toFullWidthAscii(s), CharWidth.toHalfWidthAscii(s),
            CharWidth.toHalfWidthKatakana(s), CharWidth.hasFullWidthAscii(s).toString(), CharWidth.hasHalfWidthAscii(s).toString(),
            s.map { if (CharWidth.isIdeographic(it)) '1' else '0' }.joinToString(""),
            Brackets.closerOf(s) ?: "-", Brackets.pairIndex(s).toString(), Brackets.alternatives(s).joinToString("|") { rcEsc(it) },
        )
        sb.append("W\t").append(rcEsc(s))
        for ((i, v) in f.withIndex()) sb.append('\t').append(if (i == 10) v else rcEsc(v))
        sb.append('\n')
    }
    out.parentFile?.mkdirs()
    out.writeText(sb.toString())
    println("${out.name}: ${inputs.size} 入力")
}
