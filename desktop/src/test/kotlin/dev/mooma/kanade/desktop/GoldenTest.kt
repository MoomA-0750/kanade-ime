package dev.mooma.kanade.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 共通試験（docs/GOLDEN.md）。記録（testdata/golden）と今のエンジンの出力を比べる。記録が無ければ失敗（飛ばさない） */
class GoldenTest {
    private val dir = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "testdata/golden") }.first { it.isDirectory }

    private fun lines(): List<String> = dir.listFiles { f -> f.name.endsWith(".jsonl") }!!.sortedBy { it.name }.flatMap { f -> f.readLines().filter { it.isNotBlank() } }

    @Test
    fun replayRawSendMatchesRecording() {
        var scenes = 0
        for (line in lines()) {
            val (scene, recorded) = Golden.parse(line)
            val now = Golden.replay(scene)
            assertEquals(recorded.size, now.size, scene.id)
            for ((i, pair) in recorded.zip(now).withIndex()) {
                assertEquals(pair.first.out, pair.second.out, "${scene.id} step $i out")
                assertEquals(pair.first.req.map { it.toString() }, pair.second.req.map { it.toString() }, "${scene.id} step $i req")
            }
            scenes++
        }
        assertTrue(scenes >= 100, "場面が少ない: $scenes")
    }

    @Test
    fun inputsReproduceSendAndView() {
        for (line in lines()) {
            val (scene, recorded) = Golden.parse(line)
            val inputs = Golden.inputs(line)
            val steps = scene.steps.mapIndexed { i, s -> if (inputs[i] != null) Golden.Step(s.client, inputs[i], emptyList()) else s }
            val now = Golden.run(Golden.Scene(scene.id, scene.spec, scene.note, scene.dict, scene.settings, scene.keyStyle, scene.startMode, scene.toggles, scene.snippets, steps))
            for ((i, pair) in recorded.zip(now).withIndex()) {
                assertEquals(pair.first.send, pair.second.send, "${scene.id} step $i send")
                assertEquals(pair.first.view?.toString(), pair.second.view?.toString(), "${scene.id} step $i view")
            }
        }
    }

    @Test
    fun recordingIsStable() {
        // 記録を作り直しても同じ行になる（スレッド・時刻に左右されない）
        val again = GoldenSeeds.recordLines(GoldenSeeds.seeds()) + GoldenSeeds.recordLines(GoldenSeeds.fuzz())
        fun byId(ls: List<String>) = ls.map { org.json.JSONObject(it) }.associate { it.getString("id") to it.toString() }
        val recorded = byId(lines())
        val now = byId(again)
        assertEquals(recorded.keys, now.keys)
        for ((id, json) in recorded) assertEquals(json, now[id], "記録が毎回同じにならない: $id")
    }
}
