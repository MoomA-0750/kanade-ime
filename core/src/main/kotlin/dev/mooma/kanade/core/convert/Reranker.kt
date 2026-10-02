package dev.mooma.kanade.core.convert

import kotlin.math.ln

/**
 * 言語モデルによる候補の採点。zenz などの実体は別モジュール（ネイティブ）にあり、core はこの形だけを知る。
 * 実装は1本のスレッドから呼ばれる前提でよい。中断されたら null を返す。
 */
interface LanguageModel {
    /** [leftContext] に続けて [reading]（ひらがな）を各候補に変換するときの対数確率 */
    fun score(leftContext: String, reading: String, candidates: List<String>): FloatArray?

    /** [reading] をモデルだけで変換した結果（貪欲生成）。中断・失敗なら null */
    fun generate(leftContext: String, reading: String): String?

    /** 実行中の [score]・[generate] を止める（別スレッドから呼ぶ） */
    fun cancel()
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

    fun rerank(
        leftContext: String,
        reading: String,
        candidates: List<String>,
        useGeneration: Boolean = false,
    ): Result? {
        // 生成が空・途中切れなら、生成なしで続ける（中断なら次の score が null を返す）
        val generated = if (useGeneration) model.generate(leftContext, reading) else null
        val head = (candidates.take(topK) + listOfNotNull(generated)).distinct()
        if (head.isEmpty()) return Result(candidates, generated)
        val scores = model.score(leftContext, reading, head) ?: return null
        val ranked = head.indices.sortedByDescending { i ->
            // 辞書にない生成結果は、辞書の最下位と同じ扱いにする
            val rank = candidates.indexOf(head[i]).let { if (it < 0) topK else it }
            scores[i] - beta * ln(1f + rank)
        }.map { head[it] }
        return Result((ranked + candidates).distinct(), generated)
    }
}
