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

    fun convert(input: String, n: Int = 10, maxExpansions: Int = 20000): List<Candidate> {
        if (input.isEmpty()) return emptyList()
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

    /** [reading] とちょうど同じ読みの辞書の語（コストの小さい順）。候補を全部出すときに使う */
    fun exactWords(reading: String): List<String> {
        val hit = dictionary.prefixSearch(reading, 0, reading.length).lastOrNull { it.first == reading.length } ?: return emptyList()
        return dictionary.entriesOf(hit.second).map { dictionary.surface(it, reading) }.distinct()
    }

    companion object {
        /** ユーザー辞書の語のコスト。Mozc の一般名詞（多くは 3000〜8000）より十分小さくする */
        const val USER_WORD_COST = 1000

        /** 学習した語のコスト。ユーザー辞書よりは弱く、たいていの辞書の語よりは強い */
        const val LEARNED_WORD_COST = 2500
    }
}
