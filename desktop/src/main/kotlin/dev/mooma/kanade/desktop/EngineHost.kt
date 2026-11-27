package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.voice.VoiceEarcon
import dev.mooma.kanade.core.ImeCommand
import dev.mooma.kanade.core.InputEngine
import dev.mooma.kanade.core.KeyInput
import dev.mooma.kanade.core.log.FieldKind
import dev.mooma.kanade.core.log.LogField
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

    /** 予測の候補（読みと表記の組）を並べ替えた表記。できなければ null */
    fun rankPredictions(request: ImeCommand.RequestPredictionRank): List<String>? = null

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
    /** 録音を始めた・終えたときの効果音（試験では null） */
    val earcons: Earcons? = null,
    /** 今の時刻（ms。録音キーの短押しの判定。試験で差し替える） */
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** [ms] 後に動かす（長押しの始めの音。試験で差し替える）。返す関数で取り消す */
    schedule: (Long, () -> Unit) -> (() -> Unit) = ::scheduleLater,
) {
    private val lock = Any()

    /** 録音キーの押し方と音声入力の回（長押し・聞き続けるモード） */
    private val voiceSession = voice?.let { VoiceSession(this, it, lock, clock, schedule) }

    /** 記録（エンジンの engine.log） */
    @Volatile var log: (String) -> Unit = {}

    /** エンジンが出した言語モデルへの依頼を、同じスレッドですぐ知らせる（共通試験用。docs/GOLDEN.md） */
    @Volatile internal var requestListener: ((ImeCommand) -> Unit)? = null

    /** 鳴らした効果音を知らせる（試験用） */
    @Volatile var earconListener: ((VoiceEarcon.Kind) -> Unit)? = null

    /** 声のコマンドを受け取る（設定の voice_commands） */
    var voiceCommands: Boolean
        get() = voiceSession?.commandsEnabled ?: true
        set(value) = synchronized(lock) { voiceSession?.commandsEnabled = value }

    /** 聞き続けるモードで、これだけ話さなければ終える（秒。0 なら終えない） */
    var continuousIdleSeconds: Int
        get() = voiceSession?.idleSeconds ?: 60
        set(value) = synchronized(lock) { voiceSession?.idleSeconds = value }
    private val mapper = WinKeyMapper()

    /** タスクバーの入力の表示の右クリックのメニューで選んだもの（"settings"・"user-dictionary"・"snippets"・"import-android"・"log"） */
    @Volatile var onMenu: (String) -> Unit = {}

    /** 設定の画面からの頼みごと（"import-android"・"export-android"・"clear-learning"）。成功したかと、出す文を返す */
    @Volatile var onAdmin: (String, String) -> Pair<Boolean, String> = { _, _ -> false to Ui.t("できません", "Not available") }

    /** エンジンのロックの中で動かす（設定の読み直しなど、キーの処理と重ならないように） */
    fun <T> locked(block: () -> T): T = synchronized(lock, block)

    /** 日本語のオン/オフに使うキー */
    var toggles: Set<WinToggle>
        get() = mapper.triggers
        set(value) = synchronized(lock) { mapper.triggers = value }

    /** 入力中の Ctrl のキーの割り当て（PC か macOS か） */
    var keyStyle: KeyStyle
        get() = mapper.keyStyle
        set(value) = synchronized(lock) { mapper.keyStyle = value }
    private var active: Client? = null
    private val rerankExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "rerank").apply { isDaemon = true } }
    private val latestRerank = AtomicInteger()

    /** DLL から届いた1行を処理する。返事が要るものは [client] に送る */
    fun onLine(client: Client, line: String) {
        val f = Protocol.decode(line)
        when (f[0]) {
            "hello" -> synchronized(lock) {
                client.send(reply(0, listOf(ImeCommand.ModeChanged(engine.japanese)), false))
                // fcitx5 のアドオンと macOS の入力方式には、どの入力方式でも効く録音キーを教える（Windows の DLL は F12 をキーとして送ってくる）
                if (f.getOrNull(3) == "fcitx5" || f.getOrNull(3) == "macos") {
                    fcitxClients.add(client)
                    client.send(voiceKeysLines())
                }
            }
            "test" -> {
                val key = parseKey(f, 2)
                val relevant = synchronized(lock) { test(client, key) }
                client.send(listOf(Protocol.encode("done", f[1], if (relevant) 1 else 0)))
            }
            "key" -> {
                val key = parseKey(f, 2)
                client.send(synchronized(lock) { key(client, f[1].toInt(), key) })
            }
            "commitall" -> client.send(synchronized(lock) { activate(client); reply(f[1].toInt(), engine.commitAll() + voicePending(client), true) })
            "ctx" -> synchronized(lock) {
                if (!engine.isComposing) {
                    engine.leftContext = f.getOrElse(1) { "" }.takeLast(InputEngine.MAX_CONTEXT)
                    engine.rightContext = f.getOrElse(2) { "" }.take(InputEngine.MAX_CONTEXT)
                }
            }
            "reset" -> synchronized(lock) {
                if (active === client) {
                    engine.reset()
                    // 打っている途中だったので待たせていた音声の文を入れる
                    voicePending(client).takeIf { it.isNotEmpty() }?.let { client.send(reply(0, it, false)) }
                }
            }
            // 入力欄の種類（入力の記録のため）。focus 1 のあとと、入力中に変わったと分かったときに送ってくる。
            // 届くまでは「分からない」で、何も記録しない。field <normal|secret|nolearn> <app> <fld>
            "field" -> synchronized(lock) {
                val field = LogField.of(FieldKind.parse(f.getOrNull(1)), f.getOrElse(2) { "" }, f.getOrElse(3) { "" }, inputLogLimitedApps)
                fields[client] = field
                if (active === client) engine.logField = field
            }
            "focus" -> synchronized(lock) {
                // 入力欄が替わったら、聞き続けるモードは終える
                voiceSession?.blur()
                // 入力欄の種類は、field が届くまで分からない（記録しない）
                fields[client] = LogField()
                if (active === client) engine.logField = LogField() else engine.flushLog()
                if (f.getOrNull(1) == "1") {
                    activate(client)
                    // 入力欄に入るたびに、設定の始めのモード（日本語・英字）にそろえる（LAST は前のまま）
                    val want = when (startMode) { "JAPANESE" -> true; "ENGLISH" -> false; else -> null }
                    if (want != null && engine.japanese != want) {
                        engine.logBy = "focus"
                        val switched = try { engine.handle(KeyInput.SetJapanese(want)).commands } finally { engine.logBy = "key" }
                        client.send(reply(0, run(switched), false))
                    } else {
                        client.send(reply(0, listOf(ImeCommand.ModeChanged(engine.japanese)), false))
                    }
                }
            }
            // 候補の窓で候補をクリックした（数字キーと同じ）。結果はこの DLL へ自分から送る
            "select" -> synchronized(lock) {
                val index = f.getOrNull(1)?.toIntOrNull() ?: return@synchronized
                if (active !== client || !engine.isConverting) return@synchronized
                val commands = run(engine.handle(KeyInput.SelectCandidate(index)).commands) + voicePending(client)
                if (commands.isNotEmpty()) client.send(reply(0, commands, false))
            }
            "mode" -> synchronized(lock) {
                activate(client)
                val on = f.getOrNull(1) == "1"
                if (engine.japanese != on) client.send(reply(0, run(engine.handle(KeyInput.SetJapanese(on)).commands) + voicePending(client), false))
            }
            // 入力方式に関わらない音声入力（fcitx5 のアドオン・macOS の入力方式が、どの入力方式でも録音キーを見て送る）。
            // voice down <ja|en>・voice up・voice toggle <ja|en>・voice cancel・voice blur。結果はこのつなぎへ自分から送る
            "voice" -> synchronized(lock) {
                val session = voiceSession ?: return@synchronized
                val english = f.getOrNull(2) == "en"
                when (f.getOrNull(1)) {
                    // 録音キーを押した・離した（押し方の判定はエンジンが持つ）
                    "down" -> client.send(reply(0, session.down(client, english), false))
                    "up" -> session.up()
                    // 押すたびに聞き続けるモードを始める・終えるキー
                    "toggle" -> client.send(reply(0, session.toggle(client, english), false))
                    // 録音を始めた入力欄から離れた
                    "blur" -> session.blur()
                    "cancel" -> if (!session.cancel(quiet = f.getOrNull(2) == "quiet") && engine.isComposing && active === client) {
                        // 聞き続けるモードで打っている途中の Esc: 入力中の文字のための Esc として扱う
                        client.send(reply(0, run(engine.handle(KeyInput.Escape).commands) + voicePending(client), false))
                    }
                    // 古いフロント: voice start <ja|en> [hold|tap|none]・voice cue <tap|hold>・voice stop（押し方はフロントが決める）
                    "start" -> client.send(reply(0, session.legacyStart(client, english, f.getOrNull(3) ?: "hold"), false))
                    "cue" -> session.legacyCue(f.getOrNull(2) == "tap")
                    "stop" -> session.legacyStop()
                }
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
        fcitxClients.remove(client)
        fields.remove(client)
        voiceSession?.disconnected(client)
        if (active === client) {
            engine.reset()
            active = null
        }
    }

    private fun activate(client: Client) {
        if (active !== client) {
            if (engine.isComposing) engine.reset()
            active = client
            engine.logField = fields[client] ?: LogField()
        }
    }

    /** DLL ごとの、いまの入力欄の種類（入力の記録。`field` 行で届く） */
    private val fields = HashMap<Client, LogField>()

    /** 入力の記録で、確定と切り替えだけ残すアプリ（設定の input_log_limited_apps。名前に含まれていれば当たる） */
    @Volatile var inputLogLimitedApps: String = LogField.DEFAULT_LIMITED_APPS

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
        // 録音中・聞き続けるモード中の Esc はエンジンが取る（取った Esc は離しも取る）
        if (key.vk == VK.ESCAPE && (if (key.down) voiceSession?.active == true && !(voiceSession.state == VoiceSession.State.LISTEN && engine.isComposing) else escTaken)) return true
        if (!key.down) return mapper.onKeyUpWouldToggle(key)
        // 状態（右 Alt と一緒に押したか）は OnKeyDown で同じキーをもう一度通しても同じになるので、ここで進めてよい
        val input = mapper.onKeyDown(key) ?: return false
        return when (input) {
            is KeyInput.SetJapanese, KeyInput.ToggleJapanese -> true
            else -> if (engine.japanese || (active === client && engine.isComposing)) {
                input != KeyInput.Other || (active === client && engine.isComposing)
            } else {
                // 直接入力では、スニペットと絵文字の名前（入り口の文字から）に使う文字だけを回す
                input is KeyInput.Text && (engine.snippets.size > 0 || (input.char == engine.settings.emojiLeadChar && engine.settings.emojiShortcodes))
            }
        }
    }

    private fun key(client: Client, id: Int, key: WinKey): List<String> {
        if (voice != null && key.vk == voiceKey) return voiceKey(client, id, key)
        if (key.vk == VK.ESCAPE) {
            if (key.down && voiceSession?.cancel() == true) {
                escTaken = true
                return reply(id, emptyList(), true)
            }
            if (!key.down && escTaken) {
                escTaken = false
                return reply(id, emptyList(), true)
            }
        }
        val input = if (key.down) mapper.onKeyDown(key) else mapper.onKeyUp(key)
        if (input == null) return reply(id, emptyList(), false)
        activate(client)
        val result = engine.handle(input)
        return reply(id, run(result.commands) + voicePending(client), result.consumed)
    }

    /** 録音を取り消した Esc（離しもアプリへ渡さない） */
    private var escTaken = false

    /** 聞き続けるモードで、打っている途中だったので待たせていた文（打ち終えたら入れる） */
    private fun voicePending(client: Client): List<ImeCommand> = voiceSession?.flushPending(client).orEmpty()

    /** fcitx5 のアドオンのつなぎ（録音キーを教える相手） */
    private val fcitxClients = LinkedHashSet<Client>()

    /**
     * どの入力方式でも効く録音キー（fcitx5 のキーの名前。設定の voice_hold_keys・voice_toggle_key）。
     * 押している間だけ録音するキー（カンマ区切り）と、押すたびに録音の開始と終わりを替えるキー
     */
    var voiceKeys: Pair<String, String> = DesktopSettings().let { it.voiceHoldKeys to it.voiceToggleKey }
        set(value) = synchronized(lock) {
            if (field == value) return
            field = value
            fcitxClients.forEach { it.send(voiceKeysLines()) }
        }

    /** 入力欄に入ったときの始めのモード（LAST・JAPANESE・ENGLISH）。設定の start_mode */
    @Volatile var startMode: String = "LAST"

    /** macOS の版: 日本語⇔英字の Control+Space・🌐 を Kanade が取るか（KANADE）、macOS に任せるか（MACOS）。録音キーと一緒に macOS の入力方式へ教える */
    var macSwitchKeys: String = "KANADE"
        set(value) = synchronized(lock) {
            if (field == value) return
            field = value
            fcitxClients.forEach { it.send(voiceKeysLines()) }
        }

    /** macOS の版: キーを素通しにするアプリ（リモートデスクトップなど）の bundle id（カンマ区切り。標準の分に足す）。設定の remote_apps */
    var remoteApps: String = ""
        set(value) = synchronized(lock) {
            if (field == value) return
            field = value
            fcitxClients.forEach { it.send(voiceKeysLines()) }
        }

    private fun voiceKeysLines() = listOf(Protocol.encode("c", "0", "voicekeys", voiceKeys.first, voiceKeys.second, macSwitchKeys, remoteApps), Protocol.encode("done", 0, 0))

    /** 録音キー（Windows の DLL から F12 がキーとして届く）: 長押しは押している間だけ録音、短く押すと聞き続けるモード */
    private fun voiceKey(client: Client, id: Int, key: WinKey): List<String> {
        val session = voiceSession ?: return reply(id, emptyList(), false)
        if (!key.down) {
            session.up()
            return reply(id, emptyList(), true)
        }
        if (key.repeat) return reply(id, emptyList(), true)
        return reply(id, session.down(client, english = false), true)
    }

    // ---- VoiceSession から使うもの（ロックの中で呼ぶ） ----

    internal fun activateClient(client: Client) = activate(client)

    internal fun runCommands(commands: List<ImeCommand>) = run(commands)

    /** エンジンから自分から送る命令の行 */
    internal fun replyLines(commands: List<ImeCommand>) = reply(0, commands, false)

    internal fun voiceEarcon(kind: VoiceEarcon.Kind) {
        earcons?.play(kind)
        earconListener?.invoke(kind)
    }

    /** 音声入力の様子（off・hold・listen）を fcitx5 と macOS のつなぎへ知らせる（Esc を取るかを決めるため） */
    internal fun publishVoiceState(state: String) {
        val lines = listOf(Protocol.encode("c", "0", "voicestate", state), Protocol.encode("done", 0, 0))
        fcitxClients.forEach { it.send(lines) }
    }

    /** 命令のうち、DLL に送らずエンジンで済ませるもの（候補の窓・並べ替え）を片付け、残りを返す */
    private fun run(commands: List<ImeCommand>): List<ImeCommand> = commands.filter { c ->
        when (c) {
            is ImeCommand.RequestRerank -> {
                requestListener?.invoke(c)
                requestRerank(c)
                false
            }
            is ImeCommand.RequestPredictionRank -> {
                requestListener?.invoke(c)
                requestPredictionRank(c)
                false
            }
            else -> true
        }
    }

    /**
     * 音声入力の様子（「🎤」と話している途中の文字、「認識中」）をカーソルの近くに出す。DLL が窓を描く。
     * [durationMs] が 0 なら消すまで出しておく。null なら消す
     */
    internal fun indicate(text: String?, durationMs: Int = 0) {
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

    private fun requestPredictionRank(request: ImeCommand.RequestPredictionRank) {
        val service = reranker ?: return
        rerankExecutor.execute {
            val ranked = runCatching { service.rankPredictions(request) }.getOrNull() ?: return@execute
            synchronized(lock) {
                val target = active ?: return@execute
                val commands = run(engine.onPredictionsRanked(request.id, ranked))
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

/** [ms] 後に [block] を動かす（録音キーの長押しの音） */
private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { Thread(it, "voice-timer").apply { isDaemon = true } }

private fun scheduleLater(ms: Long, block: () -> Unit): () -> Unit {
    val f = timer.schedule(block, ms, java.util.concurrent.TimeUnit.MILLISECONDS)
    return { f.cancel(false) }
}
