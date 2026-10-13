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

/** 言語モデルでの並べ替え（zenz）。重いので裏のスレッドから呼ぶ。できなければ null */
fun interface RerankService {
    fun rerank(request: ImeCommand.RequestRerank): List<String>?

    /** 計算中のものを止める（新しい依頼が来たとき。別のスレッドから呼ぶ） */
    fun cancel() {}
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
    private val reranker: RerankService? = null,
    private val voice: VoiceService? = null,
    /** 録音キー（押している間だけ録音）。Android 版と同じ F12 */
    private val voiceKey: Int = VK.F12,
) {
    private val lock = Any()
    private val mapper = WinKeyMapper()

    /** タスクバーの入力の表示の右クリックのメニューで選んだもの（"settings"・"user-dictionary"・"snippets"・"import-android"・"log"） */
    @Volatile var onMenu: (String) -> Unit = {}

    /** 設定の画面からの頼みごと（"import-android"・"export-android"・"clear-learning"）。成功したかと、出す文を返す */
    @Volatile var onAdmin: (String, String) -> Pair<Boolean, String> = { _, _ -> false to "できません" }

    /** エンジンのロックの中で動かす（設定の読み直しなど、キーの処理と重ならないように） */
    fun <T> locked(block: () -> T): T = synchronized(lock, block)

    /** 日本語のオン/オフに使うキー */
    var toggles: Set<WinToggle>
        get() = mapper.triggers
        set(value) = synchronized(lock) { mapper.triggers = value }
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
                if (active === client) engine.reset()
            }
            "focus" -> synchronized(lock) {
                if (f.getOrNull(1) == "1") {
                    activate(client)
                    client.send(reply(0, listOf(ImeCommand.ModeChanged(engine.japanese)), false))
                }
            }
            // 候補の窓で候補をクリックした（数字キーと同じ）。結果はこの DLL へ自分から送る
            "select" -> synchronized(lock) {
                val index = f.getOrNull(1)?.toIntOrNull() ?: return@synchronized
                if (active !== client || !engine.isConverting) return@synchronized
                val commands = run(engine.handle(KeyInput.SelectCandidate(index)).commands)
                if (commands.isNotEmpty()) client.send(reply(0, commands, false))
            }
            "mode" -> synchronized(lock) {
                activate(client)
                val on = f.getOrNull(1) == "1"
                if (engine.japanese != on) client.send(reply(0, run(engine.handle(KeyInput.SetJapanese(on)).commands), false))
            }
            "menu" -> f.getOrNull(1)?.let { action -> Thread({ runCatching { onMenu(action) } }, "menu").start() }
            // 設定の画面（windows/settings）からの頼みごと。時間のかかるものもあるので別のスレッドで
            "admin" -> {
                val id = f.getOrElse(1) { "0" }
                Thread({
                    val (ok, message) = runCatching { onAdmin(f.getOrElse(2) { "" }, f.getOrElse(3) { "" }) }
                        .getOrElse { false to (it.message ?: it.toString()) }
                    client.send(listOf(Protocol.encode("admin", id, if (ok) 1 else 0, message)))
                }, "admin").start()
            }
            "bye" -> disconnected(client)
        }
    }

    fun disconnected(client: Client) = synchronized(lock) {
        if (active === client) {
            engine.reset()
            active = null
        }
    }

    private fun activate(client: Client) {
        if (active !== client) {
            if (engine.isComposing) engine.reset()
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
        if (voice != null && key.vk == voiceKey) return true
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
        if (voice != null && key.vk == voiceKey) return voiceKey(client, id, key)
        val input = if (key.down) mapper.onKeyDown(key) else mapper.onKeyUp(key)
        if (input == null) return reply(id, emptyList(), false)
        activate(client)
        val result = engine.handle(input)
        return reply(id, run(result.commands), result.consumed)
    }

    private var recording = false

    /** 録音キー: 押したら（入力中の文字を確定して）録音を始め、離したら文字にして、そのときの相手の DLL へ確定を送る */
    private fun voiceKey(client: Client, id: Int, key: WinKey): List<String> {
        val v = voice ?: return reply(id, emptyList(), false)
        if (key.down) {
            if (key.repeat || recording) return reply(id, emptyList(), true)
            activate(client)
            val commands = run(engine.commitAll())
            recording = v.start { partial -> synchronized(lock) { if (recording) indicate("🎤 $partial") } }
            indicate(if (recording) "🎤" else "マイクを使えません", if (recording) 0 else 2000)
            return reply(id, commands, true)
        }
        if (!recording) {
            indicate(null)
            return reply(id, emptyList(), true)
        }
        recording = false
        indicate("🎤 認識中")
        v.stop { text ->
            synchronized(lock) {
                indicate(null)
                val target = active ?: return@synchronized
                if (text.isNullOrEmpty()) return@synchronized
                // 認識しているあいだに打ち始めていたら、その文字を先に確定する
                val commands = run(engine.commitAll()) + ImeCommand.Commit(text)
                engine.leftContext = (engine.leftContext + text).takeLast(InputEngine.MAX_CONTEXT)
                target.send(reply(0, commands, false))
            }
        }
        return reply(id, emptyList(), true)
    }

    /** 命令のうち、DLL に送らずエンジンで済ませるもの（候補の窓・並べ替え）を片付け、残りを返す */
    private fun run(commands: List<ImeCommand>): List<ImeCommand> = commands.filter { c ->
        if (c is ImeCommand.RequestRerank) {
            requestRerank(c)
            false
        } else {
            true
        }
    }

    /**
     * 音声入力の様子（「🎤」と話している途中の文字、「認識中」）をカーソルの近くに出す。DLL が窓を描く。
     * [durationMs] が 0 なら消すまで出しておく。null なら消す
     */
    private fun indicate(text: String?, durationMs: Int = 0) {
        val target = active ?: return
        val command = if (text == null) listOf("hideindicator") else listOf("indicator", text, durationMs.toString())
        target.send(listOf(Protocol.encode(listOf("c", "0") + command), Protocol.encode("done", 0, 0)))
    }

    private fun requestRerank(request: ImeCommand.RequestRerank) {
        val service = reranker ?: return
        val ticket = latestRerank.incrementAndGet()
        service.cancel()
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
        // 候補の窓は DLL が描く。選んでいる位置・数・候補と説明（説明がなければ空）を並べる
        is ImeCommand.ShowCandidates -> listOf("cands", c.selected.toString(), c.candidates.size.toString()) +
            c.candidates.flatMapIndexed { i, cand -> listOf(cand, c.labels.getOrNull(i).orEmpty()) }
        ImeCommand.HideCandidates -> listOf("hidecands")
        else -> null
    }

    fun close() {
        rerankExecutor.shutdownNow()
    }
}
