package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.EngineSettings
import java.io.File
import kotlin.random.Random

/** 共通試験の場面の種（docs/GOLDEN.md の「場面の種」）と、記録・確かめの入り口 */
object GoldenSeeds {
    private fun prelude(client: Int = 1, field: String = "normal") = Golden.Step(
        client = client,
        send = listOf(
            Protocol.encode("hello", 1, 1234 + client, "fake"),
            Protocol.encode("focus", 1),
            Protocol.encode("field", field, "", ""),
        ),
    )

    private fun scene(
        id: String,
        spec: String,
        note: String,
        vararg inputs: String,
        settings: EngineSettings = EngineSettings(),
        keyStyle: KeyStyle = KeyStyle.PC,
        startMode: String = "LAST",
        toggles: Set<WinToggle> = WinToggle.entries.toSet(),
        snippets: String = "",
        field: String = "normal",
    ) = Golden.Scene(
        id, spec, note, settings = settings, keyStyle = keyStyle, startMode = startMode, toggles = toggles, snippets = snippets,
        steps = listOf(prelude(field = field)) + inputs.map { Golden.Step(input = it) },
    )

    private val JA = "{Ctrl+Space}"

    fun seeds(): List<Golden.Scene> = listOf(
        scene("direct-passthrough", "2-1", "日本語オフではキーをアプリへ通す", "Hello, world{BS}D"),
        scene("romaji-enter", "3-1", "ローマ字をかなにして Enter で確定。入力中でない Enter はアプリへ", JA + "nihongo", "{Enter}", "{Enter}"),
        scene("romaji-n-rules", "3-1", "ん・っ・小さい字の綴り", JA + "konnnichiha", "{Enter}", "kin'en", "{Enter}", "sanpo", "{Enter}", "xaltuxya", "{Enter}"),
        scene("romaji-symbols-full", "3-1", "記号は全角（既定）", JA + "hai,sou.", "{Enter}", "[-]", "{Enter}", "123", "{Enter}"),
        scene("romaji-symbols-half", "3-1", "記号を半角にする設定", JA + "hai,sou.", "{Enter}", "123", "{Enter}", settings = EngineSettings(fullWidthSymbols = false)),
        scene("backspace-cancel", "3-2", "Backspace・Esc で入力中の文字を消す", JA + "ni{BS}{BS}", "nihon{Esc}", "ka{BS}ki"),
        scene("convert-space", "4-1", "Space で変換して候補を選ぶ", JA + "abcd", "{Space}", "{Space}", "{Enter}"),
        scene("convert-nav", "4-3", "候補の窓の上下・Shift+Space・番号", JA + "abcdef", "{Space}", "{Down}", "{Down}", "{Up}", "{Shift+Space}", "2", "{Enter}"),
        scene("convert-segments", "4-2", "文節の移動と伸び縮み", JA + "abcdefgh", "{Space}", "{Right}", "{Shift+Right}", "{Left}", "{Shift+Left}", "{Enter}"),
        scene("live-off", "3-1", "ライブ変換を切る", JA + "abcd", "{Space}", "{Enter}", settings = EngineSettings(liveConversion = false)),
        scene("width-keys", "3-4", "F6〜F10 で文字種を替える（続けて押す）", JA + "kana", "{F7}", "{F8}", "{F9}", "{F10}", "{F6}", "{Enter}"),
        scene("ctrl-keys-pc", "3-4", "PC の Ctrl+U/I/O/P/T", JA + "kana{Ctrl+I}", "{Ctrl+T}", "{Ctrl+K}"),
        scene("ctrl-keys-mac", "3-4", "macOS の Ctrl+J/K/L", JA + "kana{Ctrl+K}", "{Ctrl+L}", "{Ctrl+J}", "{Enter}", keyStyle = KeyStyle.MAC),
        scene("brackets", "5-1", "括弧は対で入れる", JA + "[", "{Enter}", "abc", "[", "{Enter}"),
        scene("emoji-ja", "6-5", "日本語入力中の絵文字ショートコード（サロゲートペアの文字）", JA + ":rocket:", "{Enter}", ":sm", "{Down}", "{Enter}"),
        scene("emoji-direct", "6-5", "直接入力中の絵文字ショートコード", ":tada:", "{Space}", ":rocket", "{Esc}"),
        scene("emoji-twice", "6-5", "入り口を2回続けたときだけ絵文字", ":smile", "::smile:", settings = EngineSettings(emojiPrefixTwice = true)),
        scene("snippets", "6-2", "スニペット", JA + "->", "{Enter}", "@@", "{Enter}", snippets = "-> →\n@@ me@example.com\n"),
        scene("toggle-keys", "2-1", "日本語のオン/オフのキー（Ctrl+Space・右 Alt の単押し・F13）", JA + "a", "{Ctrl+Space}", "{RAlt}", "a", "{F13}", "a", "{F13}"),
        scene("toggle-none", "2-1", "切り替えのキーを全部外す", "{Ctrl+Space}a{RAlt}a", toggles = emptySet()),
        scene("learn-pinned", "6-3", "選んだ候補を覚える（同じ読みの次の変換で先頭）", JA + "abcd", "{Space}", "{Down}", "{Enter}", "abcd", "{Space}", "{Enter}"),
        scene("secret-field", "1-4", "パスワード欄でも打つ動きは同じ（記録は別）", JA + "ni", "{Enter}", field = "secret"),
        Golden.Scene(
            "start-mode-japanese", "2-4", "入力欄に入るたびに日本語へそろえる", startMode = "JAPANESE",
            steps = listOf(
                prelude(), Golden.Step(input = "a"), Golden.Step(send = listOf(Protocol.encode("focus", 0))), Golden.Step(send = listOf(Protocol.encode("focus", 1))),
                Golden.Step(input = "ni{Enter}"),
            ),
        ),
        Golden.Scene(
            "ctx-and-commitall", "3-3", "入力前の文脈（ctx）と、入力中の commitall・reset", settings = EngineSettings(),
            steps = listOf(
                prelude(), Golden.Step(send = listOf(Protocol.encode("ctx", "今日は", "です"))), Golden.Step(input = JA + "abcd"),
                Golden.Step(send = listOf(Protocol.encode("commitall", 900))), Golden.Step(input = "ni"), Golden.Step(send = listOf(Protocol.encode("reset"))),
                Golden.Step(input = "ka{Enter}"),
            ),
        ),
        Golden.Scene(
            "two-clients", "1-1", "別の入力欄（別の DLL）からキーが来たら前の入力は捨てる", steps = listOf(
                prelude(1), prelude(2), Golden.Step(client = 1, input = JA + "ni"), Golden.Step(client = 2, input = "ka"), Golden.Step(client = 1, input = "{Enter}"),
            ),
        ),
        Golden.Scene(
            "prediction-on", "3-5", "予測変換（入力の途中の候補）", settings = EngineSettings(prediction = true),
            steps = listOf(prelude(), Golden.Step(input = JA + "ab"), Golden.Step(input = "{Tab}"), Golden.Step(input = "{Esc}")),
        ),
    )

    private val FUZZ_TOKENS: List<String> = buildList {
        for (c in "aiueokstnhmyrwgzdbpcjfvlxq-,.'[]/~1239!@>:") add(c.toString())
        addAll(listOf("{Space}", "{Shift+Space}", "{BS}", "{Del}", "{Enter}", "{Esc}", "{Up}", "{Down}", "{Left}", "{Right}", "{Home}", "{End}", "{Shift+Left}", "{Shift+Right}", "{Tab}", "{Ctrl+Space}", "{F6}", "{F7}", "{F8}", "{F9}", "{F10}", "{Ctrl+I}", "{Ctrl+T}"))
    }

    /** 固定した種の乱数で作ったでたらめなキー列（回数は docs/GOLDEN.md） */
    fun fuzz(count: Int = 100, keysEach: Int = 30, seed: Int = 20261123): List<Golden.Scene> {
        val rnd = Random(seed)
        return (0 until count).map { n ->
            val keys = (0 until keysEach).map { FUZZ_TOKENS[rnd.nextInt(FUZZ_TOKENS.size)] }
            Golden.Scene(
                "fuzz-%03d".format(n), "", "でたらめなキー列", settings = EngineSettings(liveConversion = n % 3 != 0, prediction = n % 5 == 0),
                snippets = "->\t→\n",
                steps = listOf(prelude(), Golden.Step(input = "{Ctrl+Space}")) + keys.map { Golden.Step(input = it) },
            )
        }
    }

    /** 場面を流して記録の行にする。片割れの代用対が入っていたら失敗 */
    fun recordLines(scenes: List<Golden.Scene>): List<String> = scenes.map { scene ->
        val results = Golden.run(scene)
        for (r in results) for (s in r.send + r.out) require(!Golden.hasBrokenSurrogate(s)) { "${scene.id}: 片割れの代用対が入った: $s" }
        Golden.toJson(scene, results)
    }

    fun record(dir: File) {
        dir.mkdirs()
        File(dir, "seeds.jsonl").writeText(recordLines(seeds()).joinToString("\n", postfix = "\n"))
        File(dir, "fuzz.jsonl").writeText(recordLines(fuzz()).joinToString("\n", postfix = "\n"))
    }
}
