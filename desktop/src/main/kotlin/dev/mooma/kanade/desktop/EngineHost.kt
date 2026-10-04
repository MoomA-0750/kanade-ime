package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.ImeCommand
import dev.mooma.kanade.core.InputEngine
import dev.mooma.kanade.core.KeyInput
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** つながっている DLL（アプリのプロセスごとに1つ） */
interface Client {
    val id: Int
    /** 1行ずつ送る（改行は送る側が足す） */
    fun send(lines: List<String>)
}

/** 候補の窓（エンジンが自前で出す）。届いた順にエンジンのロックの中から呼ぶ */
interface CandidateView {
    fun show(candidates: List<String>, selected: Int, labels: List<String?>, anchorOffset: Int) {}
    fun hide() {}
    /** 入力中の文字の、画面上の位置（DLL が ITfContextView::GetTextExt で取ったもの） */
    fun caret(left: Int, top: Int, right: Int, bottom: Int) {}
    fun mode(japanese: Boolean) {}
}

/** 言語モデルでの並べ替え（zenz）。重いので裏のスレッドから呼ぶ。できなければ null */
fun interface RerankService {
    fun rerank(request: ImeCommand.RequestRerank): List<String>?
}

/**
 * 1つの [InputEngine] を、すべての DLL で共有して動かす（日本語のオン/オフもアプリをまたいで共通。Android 版の IME と同じ）。
 * 入力中の文字は、いまフォーカスのある DLL（[active]）だけが持つ。別の DLL からキーが来たら、前の入力は捨てる
 * （前のアプリでは、フォーカスが外れたときに TSF が入力中の文字をそのまま確定させている）。
 *
 * 命令の形は docs/WINDOWS-PROTOCOL.md。
 */
class EngineHost(
    val engine: InputEngine,
    private val view: CandidateView = object : CandidateView {},
    private val reranker: RerankService? = null,
) {
    private val lock = Any()
    private val mapper = WinKeyMapper()
    private var active: Client? = null
    private val rerankExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "rerank").apply { isDaemon = true } }
    private val latestRerank = AtomicInteger()

    /** DLL から届いた1行を処理する。返事が要るものは [client] に送る */
    fun onLine(client: Client, line: String) {
        val f = Protocol.decode(line)
        when (f[0]) {
            "hello" -> synchronized(lock) { client.send(reply(0, listOf(ImeCommand.ModeChanged(engine.japanese)), false)) }
            "test" -> {
                val key = parseKey(f, 2)
                val relevant = synchronized(lock) { test(client, key) }
                client.send(listOf(Protocol.encode("done", f[1], if (relevant) 1 else 0)))
            }
            "key" -> {
                val key = parseKey(f, 2)
                client.send(synchronized(lock) { key(client, f[1].toInt(), key) })
            }
            "commitall" -> client.send(synchronized(lock) { activate(client); reply(f[1].toInt(), engine.commitAll(), true) })
            "ctx" -> synchronized(lock) {
                if (!engine.isComposing) {
                    engine.leftContext = f.getOrElse(1) { "" }.takeLast(InputEngine.MAX_CONTEXT)
                    engine.rightContext = f.getOrElse(2) { "" }.take(InputEngine.MAX_CONTEXT)
                }
            }
            "reset" -> synchronized(lock) {
                if (active === client) {
                    engine.reset()
                    view.hide()
                }
            }
            "focus" -> synchronized(lock) {
                if (f.getOrNull(1) == "1") {
                    activate(client)
                    client.send(reply(0, listOf(ImeCommand.ModeChanged(engine.japanese)), false))
                } else if (active === client) {
                    view.hide()
                }
            }
            "caret" -> synchronized(lock) {
                if (active === client) view.caret(f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt())
            }
            "mode" -> synchronized(lock) {
                activate(client)
                val on = f.getOrNull(1) == "1"
                if (engine.japanese != on) client.send(reply(0, run(engine.handle(KeyInput.SetJapanese(on)).commands), false))
            }
            "bye" -> disconnected(client)
        }
    }

    fun disconnected(client: Client) = synchronized(lock) {
        if (active === client) {
            engine.reset()
            view.hide()
            active = null
        }
    }

    private fun activate(client: Client) {
        if (active !== client) {
            if (engine.isComposing) engine.reset()
            view.hide()
            active = client
        }
    }

    private fun parseKey(f: List<String>, at: Int) = WinKey(
        down = f[at] == "d",
        vk = f[at + 1].toInt(),
        char = f[at + 2].toInt(),
        mods = f[at + 3].toInt(),
        repeat = f.getOrNull(at + 4) == "1",
    )

    /**
     * OnTestKeyDown/Up の答え: このキーを OnKeyDown/Up まで回してほしいか。
     * 回したキーでも、エンジンが使わなければ OnKeyDown で素通しにする（そのときアプリへ届く）ので、多めに答えてよい
     */
    private fun test(client: Client, key: WinKey): Boolean {
        if (!key.down) return mapper.onKeyUpWouldToggle(key)
        // 状態（右 Alt と一緒に押したか）は OnKeyDown で同じキーをもう一度通しても同じになるので、ここで進めてよい
        val input = mapper.onKeyDown(key) ?: return false
        return when (input) {
            is KeyInput.SetJapanese, KeyInput.ToggleJapanese -> true
            else -> if (engine.japanese) {
                input != KeyInput.Other || (active === client && engine.isComposing)
            } else {
                input is KeyInput.Text && engine.snippets.size > 0
            }
        }
    }

    private fun key(client: Client, id: Int, key: WinKey): List<String> {
        val input = if (key.down) mapper.onKeyDown(key) else mapper.onKeyUp(key)
        if (input == null) return reply(id, emptyList(), false)
        activate(client)
        val result = engine.handle(input)
        return reply(id, run(result.commands), result.consumed)
    }

    /** 命令のうち、DLL に送らずエンジンで済ませるもの（候補の窓・並べ替え）を片付け、残りを返す */
    private fun run(commands: List<ImeCommand>): List<ImeCommand> = commands.filter { c ->
        when (c) {
            is ImeCommand.ShowCandidates -> view.show(c.candidates, c.selected, c.labels, c.anchorOffset)
            ImeCommand.HideCandidates -> view.hide()
            is ImeCommand.RequestRerank -> requestRerank(c)
            is ImeCommand.ModeChanged -> {
                view.mode(c.japanese)
                return@filter true
            }
            else -> return@filter true
        }
        false
    }

    private fun requestRerank(request: ImeCommand.RequestRerank) {
        val service = reranker ?: return
        val ticket = latestRerank.incrementAndGet()
        rerankExecutor.execute {
            if (ticket != latestRerank.get()) return@execute
            val ranked = runCatching { service.rerank(request) }.getOrNull() ?: return@execute
            synchronized(lock) {
                val target = active ?: return@execute
                val commands = run(engine.onReranked(request.id, ranked))
                if (commands.isNotEmpty()) target.send(reply(0, commands, false))
            }
        }
    }

    /** 命令の行と、最後に `done`。[id] が 0 なら、エンジンから DLL へ自分から送るもの */
    private fun reply(id: Int, commands: List<ImeCommand>, consumed: Boolean): List<String> =
        commands.mapNotNull { encode(it)?.let { body -> Protocol.encode(listOf("c", id.toString()) + body) } } +
            Protocol.encode("done", id, if (consumed) 1 else 0)

    private fun encode(c: ImeCommand): List<String>? = when (c) {
        is ImeCommand.SetComposing -> listOf("compose", c.text, c.cursor.toString(), c.focused.toString(), c.segmentLengths.joinToString(","))
        is ImeCommand.Commit -> listOf("commit", c.text)
        is ImeCommand.ModeChanged -> listOf("mode", if (c.japanese) "1" else "0")
        is ImeCommand.ReplaceBeforeCursor -> listOf("replace", c.expected, c.typed.toString(), c.replacement)
        else -> null
    }

    fun close() {
        rerankExecutor.shutdownNow()
    }
}
