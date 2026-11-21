package dev.mooma.kanade.core.log

/**
 * 入力の記録の1件。JSON Lines の1行になる（形は docs/INPUT-LOG.md の 2）。
 * [fields] は出す順に並べる（`t`・`ev` は [toJson] が先頭に付ける）
 */
class InputEvent(val t: Long, val ev: String, val fields: Map<String, Any?> = emptyMap()) {
    fun toJson(): String = JsonLine.encode(LinkedHashMap<String, Any?>().apply {
        put("t", t)
        put("ev", ev)
        putAll(fields)
    })
}
