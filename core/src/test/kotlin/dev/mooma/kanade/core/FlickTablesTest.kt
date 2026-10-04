package dev.mooma.kanade.core

import dev.mooma.kanade.core.flick.FlickTables
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull

class FlickTablesTest {
    @Test
    fun directions() {
        val ka = FlickTables.KANA[1]
        assertEquals("か", ka.char(FlickTables.Dir.TAP))
        assertEquals("き", ka.char(FlickTables.Dir.LEFT))
        assertEquals("く", ka.char(FlickTables.Dir.UP))
        assertEquals("け", ka.char(FlickTables.Dir.RIGHT))
        assertEquals("こ", ka.char(FlickTables.Dir.DOWN))
        // わ の下は空
        assertNull(FlickTables.KANA[10].char(FlickTables.Dir.DOWN))
    }

    @Test
    fun dakutenCycle() {
        assertEquals('が', FlickTables.nextDakuten('か'))
        assertEquals('か', FlickTables.nextDakuten('が'))
        assertEquals('ば', FlickTables.nextDakuten('は'))
        assertEquals('ぱ', FlickTables.nextDakuten('ば'))
        assertEquals('は', FlickTables.nextDakuten('ぱ'))
        assertEquals('っ', FlickTables.nextDakuten('つ'))
        assertEquals('づ', FlickTables.nextDakuten('っ'))
        assertEquals('ぁ', FlickTables.nextDakuten('あ'))
        assertNull(FlickTables.nextDakuten('ん'))
    }

    @Test
    fun toggleInput() {
        val a = FlickTables.KANA[0]
        assertEquals("い", FlickTables.toggle(a, "あ"))
        assertEquals("あ", FlickTables.toggle(a, "お"))
        assertEquals("お", FlickTables.toggle(a, "あ", forward = false))
    }

    @Test
    fun replaceLastInEngine() {
        val e = InputEngine().apply { handle(KeyInput.SetJapanese(true)) }
        e.handle(KeyInput.Text('か'))
        e.handle(KeyInput.Text('あ'))
        // 一瞬も空にならずに置き換わる
        val r = e.handle(KeyInput.ReplaceLast("い"))
        assertEquals(true, r.consumed)
        assertEquals('い', e.lastTypedChar)
        assertEquals(listOf<ImeCommand>(ImeCommand.Commit("かい")), e.commitAll())
        // 入力中でなければ使わない
        assertEquals(false, e.handle(KeyInput.ReplaceLast("う")).consumed)
    }

    @Test
    fun replaceSeveralWithoutEmptying() {
        // グライドの候補の選び直し: 読み全体を置き換えても、途中で空の入力中の文字を出さない
        val e = InputEngine().apply { handle(KeyInput.SetJapanese(true)) }
        "ありがとう".forEach { e.handle(KeyInput.Text(it)) }
        val r = e.handle(KeyInput.ReplaceLast("あいそう", 5))
        assertEquals(true, r.consumed)
        assertTrue(r.commands.none { it is ImeCommand.SetComposing && it.text.isEmpty() })
        assertEquals(listOf<ImeCommand>(ImeCommand.Commit("あいそう")), e.commitAll())
    }
}
