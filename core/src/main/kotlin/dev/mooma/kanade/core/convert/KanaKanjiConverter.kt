package dev.mooma.kanade.core.convert

import dev.mooma.kanade.core.dict.ConnectionMatrix
import dev.mooma.kanade.core.dict.SystemDictionary
import dev.mooma.kanade.core.dict.UserDictionary
import java.util.PriorityQueue

/** 変換結果の1語（ラティス上の1ノード） */
data class Word(val reading: String, val surface: String, val lid: Int, val rid: Int, val cost: Int)

/** 変換候補。[cost] は文頭から文末までの総コスト（小さいほど良い） */
data class Candidate(val words: List<Word>, val cost: Int) {
    val text: String get() = words.joinToString("") { it.surface }
}

/**
 * 辞書と接続コストによるかな漢字変換。ラティスを作り、前向きのビタビで最小コストを求めてから、
 * 後ろ向きの A* で N-best を取る（前向きの最小コストがそのまま正確な見積もりになる）。
 */
class KanaKanjiConverter(
    private val dictionary: SystemDictionary,
    private val connection: ConnectionMatrix,
    /** 辞書にない1文字を「名詞,一般」として置くときの品詞とコスト。経路が切れないための逃げ道 */
    private val unknownPosId: Int = 1851,
    private val unknownCost: Int = 12000,
) {
    /** ユーザー辞書。名詞,一般として、システム辞書の語より強く優先するコストで入れる */
    @Volatile var userDictionary: UserDictionary = UserDictionary.EMPTY

    /** 学習した語（前に選んだ候補）。ユーザー辞書と辞書の間のコストで入れる */
    @Volatile var learnedDictionary: UserDictionary = UserDictionary.EMPTY
    private class Node(
        val begin: Int,
        val end: Int,
        val word: Word,
    ) {
        /** 文頭からこのノードまで（このノードのコストを含む）の最小コスト */
        var best = Int.MAX_VALUE
    }

    private class Lattice(val beginAt: Array<ArrayList<Node>>, val endAt: Array<ArrayList<Node>>, val bos: Node, val eos: Node)

    private fun lattice(input: String): Lattice {
        val len = input.length
        val beginAt = Array(len + 1) { ArrayList<Node>() }
        val endAt = Array(len + 1) { ArrayList<Node>() }

        val bos = Node(0, 0, Word("", "", 0, 0, 0)).apply { best = 0 }
        endAt[0] += bos

        for (pos in 0 until len) {
            var hasSingle = false
            for ((l, readingIndex) in dictionary.prefixSearch(input, pos)) {
                val reading = input.substring(pos, pos + l)
                for (e in dictionary.entriesOf(readingIndex)) {
                    val node = Node(
                        pos, pos + l,
                        Word(reading, dictionary.surface(e, reading), dictionary.lid(e), dictionary.rid(e), dictionary.cost(e)),
                    )
                    beginAt[pos] += node
                    endAt[pos + l] += node
                }
                if (l == 1) hasSingle = true
            }
            for ((extra, cost) in listOf(userDictionary to USER_WORD_COST, learnedDictionary to LEARNED_WORD_COST)) {
                for (l in 1..minOf(extra.maxReadingLength, len - pos)) {
                    val reading = input.substring(pos, pos + l)
                    for (surface in extra.lookup(reading)) {
                        val node = Node(pos, pos + l, Word(reading, surface, unknownPosId, unknownPosId, cost))
                        beginAt[pos] += node
                        endAt[pos + l] += node
                        if (l == 1) hasSingle = true
                    }
                }
            }
            if (!hasSingle) {
                val c = input.substring(pos, pos + 1)
                val node = Node(pos, pos + 1, Word(c, c, unknownPosId, unknownPosId, unknownCost))
                beginAt[pos] += node
                endAt[pos + 1] += node
            }
        }
        val eos = Node(len, len, Word("", "", 0, 0, 0))
        beginAt[len] += eos
        return Lattice(beginAt, endAt, bos, eos)
    }

    fun convert(input: String, n: Int = 10, maxExpansions: Int = 20000): List<Candidate> {
        if (input.isEmpty()) return emptyList()
        val len = input.length
        val l = lattice(input)
        val beginAt = l.beginAt
        val endAt = l.endAt
        val bos = l.bos
        val eos = l.eos

        // 前向き: 各ノードの最小コスト
        for (pos in 0..len) {
            val prevs = endAt[pos]
            for (node in beginAt[pos]) {
                var best = Int.MAX_VALUE
                for (prev in prevs) {
                    if (prev.best == Int.MAX_VALUE) continue
                    val c = prev.best + connection.cost(prev.word.rid, node.word.lid)
                    if (c < best) best = c
                }
                if (best != Int.MAX_VALUE) node.best = best + node.word.cost
            }
        }
        if (eos.best == Int.MAX_VALUE) return emptyList()

        // 後ろ向き A*: f = 前向きの最小コスト + 文末側の確定済みコスト
        class Path(val node: Node, val back: Int, val next: Path?, val f: Int)
        val queue = PriorityQueue<Path>(compareBy { it.f })
        queue += Path(eos, 0, null, eos.best)
        val results = ArrayList<Candidate>()
        val seen = HashSet<String>()
        var expansions = 0
        while (queue.isNotEmpty() && results.size < n && expansions++ < maxExpansions) {
            val p = queue.poll()
            if (p.node === bos) {
                val words = ArrayList<Word>()
                var q = p.next
                while (q != null && q.node !== eos) {
                    words += q.node.word
                    q = q.next
                }
                val candidate = Candidate(words, p.f)
                if (seen.add(candidate.text)) results += candidate
                continue
            }
            val back = p.back + p.node.word.cost
            for (prev in endAt[p.node.begin]) {
                if (prev.best == Int.MAX_VALUE) continue
                val g = back + connection.cost(prev.word.rid, p.node.word.lid)
                queue += Path(prev, g, p, prev.best + g)
            }
        }
        return results
    }

    /**
     * 表記が [prefix] で始まる経路のうち、コストが最小のもの（Zenzai の制約付きの探し直し）。なければ null。
     * 前向きのビタビで、各ノードについて「[prefix] の何文字目まで合ったか」ごとに最小コストを持つ
     */
    fun convertWithPrefix(input: String, prefix: String): Candidate? {
        if (input.isEmpty()) return null
        val l = lattice(input)
        val p = prefix.length
        // ノードごとに、合った文字数 m（0..p）ごとの最小コストと、ひとつ前のノード・その m
        class State(val cost: IntArray, val prev: Array<Node?>, val prevMatched: IntArray)
        val states = HashMap<Node, State>()
        fun stateOf(node: Node) = states.getOrPut(node) {
            State(IntArray(p + 1) { Int.MAX_VALUE }, arrayOfNulls(p + 1), IntArray(p + 1))
        }
        stateOf(l.bos).cost[0] = 0
        // 合った文字数 m の経路にこの表記を足したときの新しい m。合わなければ -1
        fun advance(m: Int, surface: String): Int {
            if (m == p) return p
            val rest = p - m
            return if (surface.length <= rest) {
                if (prefix.regionMatches(m, surface, 0, surface.length)) m + surface.length else -1
            } else {
                if (surface.regionMatches(0, prefix, m, rest)) p else -1
            }
        }
        for (pos in 0..input.length) {
            val prevs = l.endAt[pos]
            for (node in l.beginAt[pos]) {
                for (prev in prevs) {
                    val ps = states[prev] ?: continue
                    val conn = connection.cost(prev.word.rid, node.word.lid)
                    for (m in 0..p) {
                        val c = ps.cost[m]
                        if (c == Int.MAX_VALUE) continue
                        val next = advance(m, node.word.surface)
                        if (next < 0) continue
                        val total = c + conn + node.word.cost
                        val ns = stateOf(node)
                        if (total < ns.cost[next]) {
                            ns.cost[next] = total
                            ns.prev[next] = prev
                            ns.prevMatched[next] = m
                        }
                    }
                }
            }
        }
        val end = states[l.eos] ?: return null
        if (end.cost[p] == Int.MAX_VALUE) return null
        val words = ArrayList<Word>()
        var node: Node? = end.prev[p]
        var m = end.prevMatched[p]
        while (node != null && node !== l.bos) {
            words += node.word
            val s = states.getValue(node)
            val prev = s.prev[m]
            m = s.prevMatched[m]
            node = prev
        }
        words.reverse()
        return Candidate(words, end.cost[p])
    }

    /** [reading] とちょうど同じ読みの辞書の語（コストの小さい順）。候補を全部出すときに使う */
    fun exactWords(reading: String): List<String> {
        val hit = dictionary.prefixSearch(reading, 0, reading.length).lastOrNull { it.first == reading.length } ?: return emptyList()
        return dictionary.entriesOf(hit.second).map { dictionary.surface(it, reading) }.distinct()
    }

    /**
     * [prefix] で始まり、それより長い読みの辞書の語（予測の候補）。コストに、読みが長くなった分の重みを足して小さい順。
     * 品詞が [predictable] でない語（活用の途中の形。辞書には「軌道に乗っ」のような形も1語として入っている）は出さない。
     * 1文字の読みや、当てはまる読みが多すぎるときは引かない（短い読みから全部を見ると遅く、候補も当たらない）
     */
    fun predictWords(prefix: String, n: Int, predictable: (Int) -> Boolean = { true }): List<Pair<String, String>> {
        if (prefix.length < PREDICT_MIN_LENGTH) return emptyList()
        val range = dictionary.prefixRange(prefix)
        if (range.isEmpty() || range.last - range.first > PREDICT_MAX_READINGS) return emptyList()
        val best = PriorityQueue<Triple<Int, String, String>>(compareByDescending { it.first })
        for (r in range) {
            val extra = dictionary.readingLengthOf(r) - prefix.length
            if (extra <= 0) continue
            var reading: String? = null
            for (e in dictionary.entriesOf(r)) {
                if (!predictable(dictionary.rid(e))) continue
                val score = dictionary.cost(e) + extra * PREDICT_LENGTH_PENALTY
                if (best.size >= n * 2 && score >= best.peek().first) continue
                val rd = reading ?: dictionary.reading(r).also { reading = it }
                best += Triple(score, rd, dictionary.surface(e, rd))
                if (best.size > n * 2) best.poll()
            }
        }
        // 表記が同じものは、よいほうだけ残す
        return best.sortedBy { it.first }.distinctBy { it.third }.take(n).map { it.second to it.third }
    }

    /**
     * フリック入力の打ち間違い（濁点・半濁点・小書きの付け忘れや付けすぎ）を直した読みの変換のうち、
     * 直した数×[TYPO_PENALTY] を足しても、打ったままの読みの変換よりコストが小さいもの（小さい順）。
     * 直すのは1か所まで、読みが [TYPO_TWO_MAX_LENGTH] 文字以下なら2か所まで（「ちよつと」→ ちょっと）。
     * azooKey の誤り訂正の表のうちフリック用のもの（タ→ダ、ツ→ッ など）にならう。返す候補のコストは、直したぶんを足したもの
     */
    fun typoCorrections(reading: String, n: Int = 3): List<Candidate> {
        if (reading.length < 2 || reading.length > TYPO_MAX_LENGTH) return emptyList()
        val original = convert(reading, 1).firstOrNull()?.cost ?: Int.MAX_VALUE
        val found = HashMap<String, Int>()
        val words = HashMap<String, Candidate>()
        // 直した読みを変換し、打ったままより良ければ覚える。直したぶんを足したコストを返す（良くなければ null）
        fun tryVariant(variant: String, changes: Int): Long? {
            val best = convert(variant, 1).firstOrNull() ?: return null
            val score = best.cost.toLong() + changes * TYPO_PENALTY
            if (score >= original) return null
            val prev = found[best.text]
            if (prev == null || score < prev) {
                found[best.text] = score.toInt()
                words[best.text] = best
            }
            return score
        }
        var bestOne: Pair<String, Long>? = null
        for (i in reading.indices) {
            for (a in FlickTypos.alternatives(reading[i])) {
                val one = reading.substring(0, i) + a + reading.substring(i + 1)
                tryVariant(one, 1)?.let { if (bestOne == null || it < bestOne!!.second) bestOne = one to it }
                if (reading.length > TYPO_TWO_MAX_LENGTH) continue
                for (j in i + 1 until reading.length) {
                    for (b in FlickTypos.alternatives(reading[j])) tryVariant(one.substring(0, j) + b + one.substring(j + 1), 2)
                }
            }
        }
        // 長い読みでは、一番よかった1か所の直しに、もう1か所だけ足してみる（全部の組を試すと遅いので）
        bestOne?.takeIf { reading.length > TYPO_TWO_MAX_LENGTH }?.let { (one, _) ->
            for (j in one.indices) {
                if (one[j] != reading[j]) continue
                for (b in FlickTypos.alternatives(one[j])) tryVariant(one.substring(0, j) + b + one.substring(j + 1), 2)
            }
        }
        return found.entries.sortedBy { it.value }.take(n).map { words.getValue(it.key).copy(cost = it.value) }
    }

    companion object {
        /** 打ち間違いを1か所直すときに足すコスト（Mozc のコストで。名詞の語のコストの差がおよそ数千） */
        const val TYPO_PENALTY = 2500

        /** 2か所の直しまで探す読みの長さ */
        const val TYPO_TWO_MAX_LENGTH = 6

        /** これより長い読みでは打ち間違いを探さない（読みの字ごとに変換し直すので） */
        const val TYPO_MAX_LENGTH = 12

        const val PREDICT_MIN_LENGTH = 2
        /** これより多くの読みが当てはまる接頭辞では予測しない */
        const val PREDICT_MAX_READINGS = 30000
        /** 予測で、読みが1文字長くなるごとに足すコスト（短い語を先に出す） */
        const val PREDICT_LENGTH_PENALTY = 400

        /** ユーザー辞書の語のコスト。Mozc の一般名詞（多くは 3000〜8000）より十分小さくする */
        const val USER_WORD_COST = 1000

        /** 学習した語のコスト。ユーザー辞書よりは弱く、たいていの辞書の語よりは強い */
        const val LEARNED_WORD_COST = 2500
    }
}
