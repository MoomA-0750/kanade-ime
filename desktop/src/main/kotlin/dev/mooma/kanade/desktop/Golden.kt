package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.CandidateSource
import dev.mooma.kanade.core.Conversion
import dev.mooma.kanade.core.EngineSettings
import dev.mooma.kanade.core.ImeCommand
import dev.mooma.kanade.core.InputEngine
import dev.mooma.kanade.core.Segment
import dev.mooma.kanade.core.dict.LearningHistory
import dev.mooma.kanade.core.dict.Snippets
import org.json.JSONArray
import org.json.JSONObject

/**
 * 共通試験（golden）。形は docs/GOLDEN.md。Kotlin のエンジンに送る行を流し、返った行を記録する。
 * 規範は `send`・`out`・`req`。`in`・`view` は人が読む参考
 */
object Golden {
    const val VERSION = 1

    /** 偽の辞書（docs/GOLDEN.md の決まり）。Rust でも同じものを実装する */
    class FakeSource : dev.mooma.kanade.core.CandidateSource {
        private val learned = LearningHistory()
        override fun convert(reading: String, n: Int): List<Conversion> = listOf(
            Conversion(reading.chunked(2).map { Segment(it, "{$it}") }),
            Conversion(listOf(Segment(reading, "<$reading>"))),
        )
        override fun segmentCandidates(reading: String, n: Int) = listOf("{$reading}", "<$reading>", reading)
        override fun pinned(reading: String) = listOfNotNull(learned.lookup(reading))
        override fun learn(reading: String, surface: String) {
            learned.learn(reading, surface)
        }
        override fun labelFor(reading: String, candidate: String) = if (candidate == reading) "ひらがな" else null
    }

    /** 1つのステップ。`input`（KeyScript）か `send`（生の行）のどちらかで作る */
    data class Step(val client: Int = 1, val input: String? = null, val send: List<String> = emptyList())

    class Scene(
        val id: String,
        val spec: String = "",
        val note: String = "",
        val dict: String = "fake",
        val settings: EngineSettings = EngineSettings(),
        val keyStyle: KeyStyle = KeyStyle.PC,
        val startMode: String = "LAST",
        val toggles: Set<WinToggle> = WinToggle.entries.toSet(),
        val snippets: String = "",
        val steps: List<Step>,
    )

    class StepResult(val client: Int, val input: String?, val send: List<String>, val out: List<String>, val req: List<JSONObject>, val view: JSONObject?)

    /** 場面1つ分の流す環境 */
    private class Run(val scene: Scene) {
        val engine = InputEngine(scene.settings, FakeSource()).apply {
            if (scene.snippets.isNotEmpty()) snippets = Snippets.parse(scene.snippets)
        }
        val host = EngineHost(engine).also {
            it.keyStyle = scene.keyStyle
            it.startMode = scene.startMode
            it.toggles = scene.toggles
        }
        val requests = mutableListOf<JSONObject>()
        val out = mutableListOf<String>()
        val clients = HashMap<Int, Pair<Client, FakeDll>>()
        val sent = mutableListOf<String>()

        init {
            require(scene.dict == "fake") { "辞書 ${scene.dict} はまだ流せない" }
            host.requestListener = { c ->
                requests += when (c) {
                    is ImeCommand.RequestRerank -> JSONObject().put("kind", "rerank").put("id", c.id).put("left", c.leftContext)
                        .put("reading", c.reading).put("candidates", JSONArray(c.candidates)).put("right", c.rightContext)
                    is ImeCommand.RequestPredictionRank -> JSONObject().put("kind", "predRank").put("id", c.id).put("left", c.leftContext)
                        .put("items", JSONArray(c.items.map { JSONArray(listOf(it.first, it.second)) }))
                    else -> error("知らない依頼")
                }
            }
        }

        fun client(id: Int): Pair<Client, FakeDll> = clients.getOrPut(id) {
            lateinit var dll: FakeDll
            val c = object : Client {
                override val id = id
                override fun send(lines: List<String>) = lines.forEach { line ->
                    out += line
                    dll.deliver(line)
                }
            }
            dll = FakeDll { line ->
                sent += line
                host.onLine(c, line)
            }
            c to dll
        }

        fun step(s: Step): StepResult {
            out.clear(); requests.clear(); sent.clear()
            val (client, dll) = client(s.client)
            if (s.input != null) dll.type(s.input) else s.send.forEach { host.onLine(client, it) }
            val send = if (s.input != null) sent.toList() else s.send
            val view = if (s.input != null) view(dll) else null
            return StepResult(s.client, s.input, send, out.toList(), requests.toList(), view)
        }

        fun view(dll: FakeDll) = JSONObject().put("text", dll.document.toString()).put("comp", dll.composition)
            .put("cands", JSONArray(if (dll.candidates.isEmpty()) emptyList<Any>() else listOf<Any>(dll.selectedCandidate) + dll.candidates))
    }

    /** 場面を流して結果を返す。`input` の手順は FakeDll で送る行にし、`send` の手順は行をそのまま送る */
    fun run(scene: Scene): List<StepResult> {
        val r = Run(scene)
        return try { scene.steps.map { r.step(it) } } finally { r.host.close() }
    }

    /** 記録した場面の `send` だけをそのまま流して、`out` と `req` を返す（Rust 版が行うのと同じ流し方） */
    fun replay(scene: Scene): List<StepResult> = run(Scene(
        scene.id, scene.spec, scene.note, scene.dict, scene.settings, scene.keyStyle, scene.startMode, scene.toggles, scene.snippets,
        scene.steps.map { Step(it.client, null, it.send) },
    ))

    // ---- JSON ----

    private fun settingsJson(s: EngineSettings) = JSONObject().put("fullWidthSymbols", s.fullWidthSymbols).put("liveConversion", s.liveConversion)
        .put("candidateCount", s.candidateCount).put("punctuation", s.punctuation).put("pairBrackets", s.pairBrackets)
        .put("prediction", s.prediction).put("predictionCount", s.predictionCount).put("emojiShortcodes", s.emojiShortcodes)
        .put("emojiPrefix", s.emojiPrefix.toString()).put("emojiPrefixTwice", s.emojiPrefixTwice)

    private fun settingsOf(j: JSONObject) = EngineSettings(
        fullWidthSymbols = j.getBoolean("fullWidthSymbols"), liveConversion = j.getBoolean("liveConversion"),
        candidateCount = j.getInt("candidateCount"), punctuation = j.getString("punctuation"), pairBrackets = j.getBoolean("pairBrackets"),
        prediction = j.getBoolean("prediction"), predictionCount = j.getInt("predictionCount"), emojiShortcodes = j.getBoolean("emojiShortcodes"),
        emojiPrefix = j.getString("emojiPrefix")[0], emojiPrefixTwice = j.getBoolean("emojiPrefixTwice"),
    )

    /** 場面と結果を1行の JSON にする（記録） */
    fun toJson(scene: Scene, results: List<StepResult>): String {
        val steps = JSONArray()
        for (r in results) {
            val o = JSONObject()
            if (r.input != null) o.put("in", r.input)
            o.put("client", r.client).put("send", JSONArray(r.send)).put("out", JSONArray(r.out)).put("req", JSONArray(r.req))
            if (r.view != null) o.put("view", r.view)
            steps.put(o)
        }
        return JSONObject().put("v", VERSION).put("id", scene.id).put("spec", scene.spec).put("note", scene.note).put("layer", "host")
            .put("dict", JSONObject().put("kind", scene.dict)).put("settings", settingsJson(scene.settings))
            .put("host", JSONObject().put("keyStyle", scene.keyStyle.name).put("startMode", scene.startMode)
                .put("toggles", JSONArray(scene.toggles.map { it.name }.sorted())))
            .put("snippets", JSONArray(scene.snippets.lines().filter { it.isNotEmpty() })).put("userDict", JSONArray())
            .put("steps", steps).toString()
    }

    /** 記録の1行を、`send` だけの場面と、記録した結果に分ける */
    fun parse(line: String): Pair<Scene, List<StepResult>> {
        val j = JSONObject(line)
        require(j.getInt("v") == VERSION) { "版が違う: ${j.getInt("v")}" }
        require(j.getString("layer") == "host") { "層 ${j.getString("layer")} は未対応" }
        val h = j.getJSONObject("host")
        val recorded = mutableListOf<StepResult>()
        val steps = mutableListOf<Step>()
        val arr = j.getJSONArray("steps")
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val send = s.getJSONArray("send").strings()
            steps += Step(s.optInt("client", 1), null, send)
            recorded += StepResult(s.optInt("client", 1), s.optString("in").takeIf { it.isNotEmpty() }, send, s.getJSONArray("out").strings(),
                s.getJSONArray("req").objects(), s.optJSONObject("view"))
        }
        val scene = Scene(
            j.getString("id"), j.optString("spec"), j.optString("note"), j.getJSONObject("dict").getString("kind"), settingsOf(j.getJSONObject("settings")),
            KeyStyle.valueOf(h.getString("keyStyle")), h.getString("startMode"), h.getJSONArray("toggles").strings().map { WinToggle.valueOf(it) }.toSet(),
            j.getJSONArray("snippets").strings().joinToString("\n"), steps,
        )
        return scene to recorded
    }

    /** 記録のとき、`in` を持つ元の場面（`input` つきの手順）が、記録から再現できるかを確かめるために `in` を取り出す */
    fun inputs(line: String): List<String?> {
        val arr = JSONObject(line).getJSONArray("steps")
        return (0 until arr.length()).map { arr.getJSONObject(it).optString("in").takeIf { s -> s.isNotEmpty() } }
    }

    /** 記録の片割れの代用対（JSON にできない文字）が入っていないか */
    fun hasBrokenSurrogate(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length || !Character.isLowSurrogate(s[i + 1])) return true
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c)) return true
            i++
        }
        return false
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}
