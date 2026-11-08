package dev.mooma.kanade.core.convert

import dev.mooma.kanade.core.ImeCommand
import kotlin.math.ln

/**
 * 言語モデルによる候補の採点。zenz などの実体は別モジュール（ネイティブ）にあり、core はこの形だけを知る。
 * 実装は1本のスレッドから呼ばれる前提でよい。中断されたら null を返す。
 */
interface LanguageModel {
    /**
     * [leftContext] に続けて [reading]（ひらがな）を各候補に変換するときの対数確率。
     * [rightContext] はカーソルの後ろの文（文の途中を直しているとき。なければ空）
     */
    fun score(leftContext: String, reading: String, candidates: List<String>, rightContext: String = ""): FloatArray?

    /** [reading] をモデルだけで変換した結果（貪欲生成）。中断・失敗なら null */
    fun generate(leftContext: String, reading: String, rightContext: String = ""): String?

    /** 実行中の [score]・[generate] を止める（別スレッドから呼ぶ） */
    fun cancel()

    /**
     * [leftContext] に続けて、読み（ひらがな）とその変換の組 [items] が出てくる対数確率（読みの部分も含む）。
     * 読みが違う候補どうし（グライドの候補）を比べるのに使う。対応しなければ null
     */
    fun scoreReadings(leftContext: String, items: List<Pair<String, String>>): FloatArray? = null

    /**
     * [candidate] を左から確かめる（Zenzai の検証）。各位置でモデルが一番に選ぶ文字が [candidate] と合えば ""。
     * 最初にずれたら、そこまでの [candidate] の表記＋モデルが選んだ文字を返す（辞書をその接頭辞で探し直すのに使う）。
     * 中断なら null。対応しなければ ""（直さない）
     */
    fun verify(leftContext: String, reading: String, candidate: String, rightContext: String = ""): String? = ""
}

/**
 * 辞書の N-best を言語モデルで並べ替える。辞書側の順位を事前知識として残し、
 * 「モデルの対数確率 − β·ln(1+元の順位)」で並べる（skk-zenz の評価で良好だった式）。
 * 上位 [topK] 件だけを採点し、残りは元の順で後ろに付ける。
 */
class Reranker(
    private val model: LanguageModel,
    private val topK: Int = 10,
    private val beta: Float = 1.0f,
) {
    data class Result(val candidates: List<String>, val generated: String?)

    companion object {
        /** Zenzai の方式で、1回の変換で確かめる回数の上限（評価セットで 3 と 5 は同じ正解率だった） */
        const val CORRECT_LIMIT = 3
    }

    /**
     * IME からの依頼をこなす。[correctLimit] が 0 より大きく、文全体の依頼で [constrained] があれば、Zenzai の方式で
     * 辞書の1位を直し、それを先頭にして辞書の順のまま返す（並べ替えはしない）。そうでなければ上位を並べ替える。中断なら null
     */
    fun rerank(request: ImeCommand.RequestRerank, correctLimit: Int, constrained: ((String) -> String?)?): List<String>? {
        val first = request.candidates.firstOrNull()
        if (correctLimit > 0 && request.sentence && constrained != null && first != null) {
            val top = correct(request.leftContext, request.reading, first, correctLimit, request.rightContext, constrained) ?: return null
            return (listOf(top) + request.candidates).distinct()
        }
        return rerank(request.leftContext, request.reading, request.candidates, rightContext = request.rightContext)?.candidates
    }

    /**
     * Zenzai の方式で1位を直す。[start] をモデルに確かめさせ、ずれたら、モデルが選んだ接頭辞で辞書を探し直した文
     * （[constrained]。その接頭辞で始まる経路がなければ null）を次の候補にする。合格するか、[limit] 回確かめたら止める。
     * 出す文はいつも辞書の経路なので、読みと表記がずれない。中断なら null
     */
    fun correct(
        leftContext: String,
        reading: String,
        start: String,
        limit: Int,
        rightContext: String = "",
        constrained: (String) -> String?,
    ): String? = drafts(leftContext, reading, start, limit, rightContext, constrained)?.last()

    /** [correct] で試した文（最初の [start] から、最後に出すものまで）。中断なら null */
    fun drafts(
        leftContext: String,
        reading: String,
        start: String,
        limit: Int,
        rightContext: String = "",
        constrained: (String) -> String?,
    ): List<String>? {
        val tried = arrayListOf(start)
        repeat(limit) {
            val prefix = model.verify(leftContext, reading, tried.last(), rightContext) ?: return null
            if (prefix.isEmpty()) return tried
            val next = constrained(prefix) ?: return tried
            if (next in tried) return tried
            tried += next
        }
        return tried
    }

    /**
     * 予測の候補（読みと表記の組）を、左の文脈に続けて出てくる確率で並べ替える。辞書の順位も
     * 「− β·ln(1+元の順位)」で残す。モデルが対応しない・中断なら null
     */
    fun rankPredictions(leftContext: String, items: List<Pair<String, String>>): List<String>? {
        if (items.size < 2) return items.map { it.second }
        val scores = model.scoreReadings(leftContext, items) ?: return null
        return items.indices.sortedByDescending { scores[it] - beta * ln(1f + it) }.map { items[it].second }.distinct()
    }

    fun rerank(
        leftContext: String,
        reading: String,
        candidates: List<String>,
        useGeneration: Boolean = false,
        rightContext: String = "",
    ): Result? {
        // 生成が空・途中切れなら、生成なしで続ける（中断なら次の score が null を返す）
        val generated = if (useGeneration) model.generate(leftContext, reading, rightContext) else null
        val head = (candidates.take(topK) + listOfNotNull(generated)).distinct()
        if (head.isEmpty()) return Result(candidates, generated)
        val scores = model.score(leftContext, reading, head, rightContext) ?: return null
        val ranked = head.indices.sortedByDescending { i ->
            // 辞書にない生成結果は、辞書の最下位と同じ扱いにする
            val rank = candidates.indexOf(head[i]).let { if (it < 0) topK else it }
            scores[i] - beta * ln(1f + rank)
        }.map { head[it] }
        return Result((ranked + candidates).distinct(), generated)
    }
}
