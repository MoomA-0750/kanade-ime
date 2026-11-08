package dev.mooma.kanade.core

import dev.mooma.kanade.core.convert.LanguageModel
import dev.mooma.kanade.core.convert.Reranker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** azooKey・Zenzai にならった工夫（並べ替えの覚え・学習した語を守る・Zenzai の直し・予測の並べ替え） */
class ZenzaiTest {
    private val table = mapOf(
        "はし" to listOf("橋", "箸", "端"),
        "きどう" to listOf("起動", "軌道"),
    )

    /** 文全体は1文節として返す偽の辞書。[learned] は選び直して覚えた語 */
    private class Source(val table: Map<String, List<String>>, val learned: Map<String, String> = emptyMap()) : CandidateSource {
        override fun convert(reading: String, n: Int) =
            (table[reading] ?: listOf(reading)).map { Conversion(listOf(Segment(reading, it))) }

        override fun segmentCandidates(reading: String, n: Int) = table[reading] ?: listOf(reading)
        override fun isLearned(reading: String, surface: String) = learned[reading] == surface
        override fun convertWithPrefix(reading: String, prefix: String) =
            if (reading == "はし" && "は市".startsWith(prefix) && prefix.length == 2) {
                Conversion(listOf(Segment("は", "は"), Segment("し", "市")))
            } else {
                convert(reading, 10).firstOrNull { it.text.startsWith(prefix) }
            }

        override fun predictWithReadings(reading: String, n: Int) =
            if (reading == "きー") listOf("きーぼーど" to "キーボード", "きーほるだー" to "キーホルダー") else emptyList()
        override fun predict(reading: String, n: Int) = predictWithReadings(reading, n).map { it.second }
    }

    private fun engine(source: CandidateSource = Source(table), settings: EngineSettings = EngineSettings()) =
        InputEngine(settings, source).apply { handle(KeyInput.SetJapanese(true)) }

    private fun InputEngine.type(s: String) = s.map { handle(KeyInput.Text(it)) }.flatMap { it.commands }

    @Test
    fun backspaceToAnEarlierReadingUsesTheRememberedRanking() {
        val e = engine()
        val req = e.type("hashi").filterIsInstance<ImeCommand.RequestRerank>().single()
        assertTrue(req.sentence)
        e.onReranked(req.id, listOf("箸", "橋", "端"))
        e.type("de")
        // 「はし」に戻ったら、言語モデルに頼まずに前の結果を出す
        val again = e.handle(KeyInput.Backspace).commands
        assertTrue(again.none { it is ImeCommand.RequestRerank })
        assertEquals("箸", e.composingText)
    }

    @Test
    fun choosingSettingsForgetsTheRememberedRanking() {
        val e = engine()
        val req = e.type("hashi").filterIsInstance<ImeCommand.RequestRerank>().single()
        e.onReranked(req.id, listOf("箸", "橋", "端"))
        e.reset()
        e.settings = EngineSettings()
        assertEquals(1, e.type("hashi").filterIsInstance<ImeCommand.RequestRerank>().size)
    }

    @Test
    fun noRerankWhileARomajiSpellingIsPending() {
        val e = engine()
        e.type("hashi")
        // 「k」は綴りの途中。読みは変わらないので並べ替えを頼まない
        assertTrue(e.type("k").none { it is ImeCommand.RequestRerank })
    }

    @Test
    fun aLearnedWordIsNotMovedByTheModel() {
        val e = engine(Source(table, learned = mapOf("きどう" to "起動")))
        assertTrue(e.type("kidou").none { it is ImeCommand.RequestRerank })
        assertEquals("起動", e.composingText)
    }

    @Test
    fun aCorrectedTopOutsideTheNBestIsSplitIntoSegments() {
        val e = engine()
        val req = e.type("hashi").filterIsInstance<ImeCommand.RequestRerank>().single()
        // N-best にない文が1位になった（Zenzai の直し）。変換を始めると、探し直した文節に分かれる
        e.onReranked(req.id, listOf("は市", "橋", "箸", "端"))
        e.handle(KeyInput.Space)
        assertEquals("は市", e.composingText)
        e.handle(KeyInput.Right)
        val shown = e.handle(KeyInput.Space).commands.filterIsInstance<ImeCommand.ShowCandidates>().last()
        assertEquals(1, shown.anchorOffset)
    }

    /** 候補を表で決めた偽のモデル: 「はし」は「箸」が正しいと考える */
    private class Model : LanguageModel {
        var verifies = 0
        override fun score(leftContext: String, reading: String, candidates: List<String>, rightContext: String) =
            FloatArray(candidates.size) { if (candidates[it] == "箸") 0f else -5f }
        override fun generate(leftContext: String, reading: String, rightContext: String): String? = null
        override fun cancel() {}
        override fun verify(leftContext: String, reading: String, candidate: String, rightContext: String): String {
            verifies++
            return if (candidate == "箸") "" else "箸"
        }
        override fun scoreReadings(leftContext: String, items: List<Pair<String, String>>) =
            FloatArray(items.size) { if (items[it].second == "キーホルダー") 0f else -5f }
    }

    @Test
    fun zenzaiCorrectsTheTopBySearchingAgainFromTheModelsPrefix() {
        val model = Model()
        val source = Source(table)
        val r = Reranker(model).correct("", "はし", "橋", limit = 3) { prefix -> source.convertWithPrefix("はし", prefix)?.text }
        assertEquals("箸", r)
        // 1回目で「箸」を求められ、2回目で合格
        assertEquals(2, model.verifies)
    }

    @Test
    fun zenzaiStopsAtTheLimit() {
        val model = Model()
        val r = Reranker(model).correct("", "はし", "橋", limit = 1) { "箸" }
        assertEquals("箸", r)
        assertEquals(1, model.verifies)
    }

    @Test
    fun zenzaiKeepsTheDraftWhenNoPathHasThePrefix() {
        val r = Reranker(Model()).correct("", "はし", "橋", limit = 3) { null }
        assertEquals("橋", r)
    }

    @Test
    fun correctModeOnlyAppliesToWholeSentences() {
        val reranker = Reranker(Model())
        val sentence = ImeCommand.RequestRerank(1, "", "はし", listOf("橋", "箸", "端"), sentence = true)
        assertEquals(listOf("箸", "橋", "端"), reranker.rerank(sentence, 3) { prefix -> listOf("橋", "箸", "端").firstOrNull { it.startsWith(prefix) } })
        // 文節の並べ替えは、直さずに採点で並べる
        val segment = sentence.copy(sentence = false)
        assertEquals("箸", reranker.rerank(segment, 3) { null }!!.first())
    }

    @Test
    fun predictionsAreReorderedByTheModel() {
        val e = engine(settings = EngineSettings(prediction = true))
        val commands = e.type("ki-")
        val shown = commands.filterIsInstance<ImeCommand.ShowCandidates>().last()
        assertEquals(listOf("キーボード", "キーホルダー"), shown.candidates)
        val req = commands.filterIsInstance<ImeCommand.RequestPredictionRank>().single()
        val ranked = Reranker(Model(), beta = 0f).rankPredictions(req.leftContext, req.items)!!
        val after = e.onPredictionsRanked(req.id, ranked).filterIsInstance<ImeCommand.ShowCandidates>().single()
        assertEquals(listOf("キーホルダー", "キーボード"), after.candidates)
        assertTrue(after.prediction)
    }

    @Test
    fun stalePredictionRankingIsIgnored() {
        val e = engine(settings = EngineSettings(prediction = true))
        val req = e.type("ki-").filterIsInstance<ImeCommand.RequestPredictionRank>().single()
        e.handle(KeyInput.Backspace)
        assertEquals(emptyList(), e.onPredictionsRanked(req.id, listOf("キーホルダー", "キーボード")))
    }

    @Test
    fun escapeHidesThePredictionWindow() {
        val e = engine(settings = EngineSettings(prediction = true))
        assertTrue(e.type("ki-").any { it is ImeCommand.ShowCandidates })
        // 取り消すと予測の候補は消える。窓も隠す（隠し忘れると、空の入力欄の下に前の予測が残る）
        assertTrue(ImeCommand.HideCandidates in e.handle(KeyInput.Escape).commands)
    }

    @Test
    fun aTypoCorrectedTopIsNotSentToTheModel() {
        val source = object : CandidateSource {
            override fun convert(reading: String, n: Int) = listOf(
                Conversion(listOf(Segment(reading, "写真")), corrected = true),
                Conversion(listOf(Segment(reading, "視野心"))),
            )
            override fun segmentCandidates(reading: String, n: Int) = listOf("写真", "視野心")
        }
        val e = engine(source)
        assertTrue(e.type("siyasinn").none { it is ImeCommand.RequestRerank })
        assertEquals("写真", e.composingText)
    }
}
