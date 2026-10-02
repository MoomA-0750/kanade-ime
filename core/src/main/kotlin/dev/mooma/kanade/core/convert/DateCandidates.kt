package dev.mooma.kanade.core.convert

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * 日付・時刻の候補（Mozc の DateRewriter と同じ考え方）。「きょう」「あした」「きのう」などで日付、「いま」「じこく」で時刻を出す。
 * 文節の候補の後ろの方に足す（1位にはしない）。[now] は試験のために差し替えられる
 */
class DateCandidates(private val now: () -> LocalDateTime = { LocalDateTime.now() }) {
    private val dayOffsets = mapOf(
        "きょう" to 0L, "ほんじつ" to 0L, "あした" to 1L, "あす" to 1L, "みょうにち" to 1L, "あさって" to 2L,
        "きのう" to -1L, "さくじつ" to -1L, "おととい" to -2L, "いっさくじつ" to -2L,
    )

    fun lookup(reading: String): List<String> {
        val t = now()
        dayOffsets[reading]?.let { return dates(t.toLocalDate().plusDays(it)) }
        return when (reading) {
            "いま", "じこく", "げんざいじこく" -> times(t)
            "ことし" -> listOf("${t.year}年", reiwaYear(t.year))
            "らいねん" -> listOf("${t.year + 1}年", reiwaYear(t.year + 1))
            "きょねん" -> listOf("${t.year - 1}年", reiwaYear(t.year - 1))
            "こんげつ" -> listOf("${t.monthValue}月", "${t.year}年${t.monthValue}月")
            else -> emptyList()
        }
    }

    private fun dates(d: LocalDate): List<String> {
        val dow = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.JAPANESE)
        return listOf(
            "%d/%02d/%02d".format(d.year, d.monthValue, d.dayOfMonth),
            "%d-%02d-%02d".format(d.year, d.monthValue, d.dayOfMonth),
            "${d.year}年${d.monthValue}月${d.dayOfMonth}日",
            "${d.monthValue}月${d.dayOfMonth}日（$dow）",
            "${d.monthValue}/${d.dayOfMonth}（$dow）",
            "${reiwaYear(d.year)}${d.monthValue}月${d.dayOfMonth}日",
        )
    }

    private fun times(t: LocalDateTime): List<String> = listOf(
        "%d:%02d".format(t.hour, t.minute),
        "${t.hour}時${t.minute}分",
        "%s%d時%d分".format(if (t.hour < 12) "午前" else "午後", if (t.hour % 12 == 0) 12 else t.hour % 12, t.minute),
    )

    /** 令和（2019 年が元年） */
    private fun reiwaYear(year: Int): String = if (year == 2019) "令和元年" else "令和${year - 2018}年"
}
