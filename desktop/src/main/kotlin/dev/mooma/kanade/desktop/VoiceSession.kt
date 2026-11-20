package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.ImeCommand
import dev.mooma.kanade.core.InputEngine
import dev.mooma.kanade.core.voice.ContinuousSegmenter
import dev.mooma.kanade.core.voice.VoiceCommand
import dev.mooma.kanade.core.voice.VoiceEarcon
import dev.mooma.kanade.whisper.WhisperModel
import java.util.TreeMap

/**
 * 録音キーの押し方と、音声入力の回（SPEC 8-3・8-11・8-13・8-15、docs/CONTINUOUS-PC.md）。
 * 長押し（[TAP_MS] 以上）は押している間だけ録音して、離したらまとめて文字にする。短く押すと聞き続けるモード:
 * 黙るたびにそこまでを文字にして入れ、もう一度押す・Esc・入力欄の移動・話さないままの時間切れ・「止めて」で終える。
 *
 * どのメソッドも [EngineHost] のロックの中で呼ぶ。録音・認識のスレッドから戻るものは、自分でロックを取る
 */
internal class VoiceSession(
    private val host: EngineHost,
    private val voice: VoiceService,
    private val lock: Any,
    private val clock: () -> Long,
    /** [ms] 後に動かす。返す関数で取り消す */
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
) {
    enum class State { OFF, PRESSED, HOLD, LISTEN }

    var state = State.OFF
        private set

    /** 声のコマンドを受け取る（設定の voice_commands） */
    var commandsEnabled = true

    /** 聞き続けるモードで、これだけ話さなければ終える（秒。0 なら終えない。設定の continuous_idle_seconds） */
    var idleSeconds = 60

    private val engine: InputEngine get() = host.engine

    /** 録音を始めたときの相手（文字を入れる先） */
    private var client: Client? = null
    private var english = false
    /** 録音の回の番号（前の回の録音・途中の文字が遅れて届いたら捨てる） */
    private var recording = 0
    private var downAt = 0L
    /** 聞き続けるモードをもう一度押して終えたあと、その押しの離しは見ない */
    private var ignoreUp = false
    private var cancelHoldCue: (() -> Unit)? = null

    private var segmenter: ContinuousSegmenter? = null
    /** 話している途中の文字（確定の文が作れなかったときに使う） */
    private var partial: String? = null
    /** この聞き続けるモードで文字が入ったか（終わりの音を選ぶ） */
    private var gotText = false

    /** 捨てる回の番号。Esc・入力欄の移動で終えたら進め、それより前の区切りの結果は入れない */
    private var generation = 0
    private var nextSeq = 0
    private var nextDeliver = 0
    private val results = TreeMap<Int, Result>()
    private class Result(val generation: Int, val text: String?, val fallback: String?, val candidate: Boolean)

    /** Kanade で打っている途中に届いて、まだ入れられない文 */
    private val pending = StringBuilder()
    /** 直前に音声で入れた文（「取り消して」で消す） */
    private var lastVoice: String? = null

    val active: Boolean get() = state != State.OFF

    // ---- 押し方 ----

    /** 録音キーを押した。打っている途中の文字を確定する命令を返す（マイクが使えなくても確定はする） */
    fun down(c: Client, english: Boolean): List<ImeCommand> {
        when (state) {
            State.LISTEN -> {
                stopListening(keep = true, message = Ui.t("🎤 止めました", "🎤 Stopped"))
                ignoreUp = true
                return emptyList()
            }
            State.PRESSED, State.HOLD -> return emptyList()
            State.OFF -> {}
        }
        ignoreUp = false
        val (commands, started) = begin(c, english)
        if (!started) return commands
        state = State.PRESSED
        val id = recording
        // マイクを開くのに時間がかかることがあるので、短く押したかは録音が始まってから数える
        downAt = clock()
        cancelHoldCue = schedule(TAP_MS) {
            synchronized(lock) {
                if (recording == id && state == State.PRESSED) {
                    state = State.HOLD
                    host.voiceEarcon(VoiceEarcon.Kind.START_HOLD)
                    host.indicate(micLabel())
                }
            }
        }
        return commands
    }

    /** 録音キーを離した */
    fun up() {
        if (ignoreUp) {
            ignoreUp = false
            return
        }
        if (state == State.PRESSED || state == State.HOLD) host.log("voice: up ${clock() - downAt}ms after start")
        when (state) {
            State.PRESSED -> if (clock() - downAt < TAP_MS) startListening() else stopHold()
            State.HOLD -> stopHold()
            else -> {}
        }
    }

    /** 押すたびに始める・終えるキー（Linux・macOS の Control+Alt+Space）: 聞き続けるモードの入り・切り */
    fun toggle(c: Client, english: Boolean): List<ImeCommand> {
        return when (state) {
            State.OFF -> begin(c, english).let { (commands, started) ->
                if (started) startListening()
                commands
            }
            State.LISTEN -> {
                stopListening(keep = true, message = Ui.t("🎤 止めました", "🎤 Stopped"))
                emptyList()
            }
            else -> emptyList()
        }
    }

    /**
     * Esc（録音中・聞き続けるモード中）: 話している途中の音声を捨てて終える。何もしなかったら false。
     * 聞き続けるモードで Kanade で打っている途中なら、Esc は入力中の文字のためのもの（聞き続けるモードは続ける）なので false
     */
    fun cancel(quiet: Boolean = false): Boolean = when (state) {
        State.OFF -> false
        State.LISTEN -> if (engine.isComposing) false else {
            stopListening(keep = false, message = Ui.t("🎤 取り消しました", "🎤 Canceled"))
            true
        }
        State.PRESSED, State.HOLD -> {
            cancelHold(quiet)
            true
        }
    }

    /** 入力欄が替わった: 聞き続けるモードを終える（話している途中の音声と、認識中の結果は捨てる） */
    fun blur() {
        if (state == State.LISTEN) {
            stopListening(keep = false, message = null)
        } else if (state == State.OFF && (nextDeliver < nextSeq || pending.isNotEmpty())) {
            // 終えた直後に移った: まだ届いていない区切りの結果を、新しい入力欄に入れない
            generation++
            pending.clear()
        }
    }

    /** つなぎが切れた（アプリを閉じた・落ちた）: その相手のための録音を止める */
    fun disconnected(c: Client) {
        if (c !== client) return
        when (state) {
            State.LISTEN -> stopListening(keep = false, message = null)
            State.PRESSED, State.HOLD -> cancelHold(quiet = true)
            State.OFF -> {}
        }
        generation++
        pending.clear()
        client = null
    }

    // ---- 古いフロント（voice start/cue/stop） ----

    /** 押している間の録音を始める（押し方はフロントが決める）。[cue] は始めの音（hold・tap・none） */
    fun legacyStart(c: Client, english: Boolean, cue: String): List<ImeCommand> {
        if (state != State.OFF) return emptyList()
        val (commands, started) = begin(c, english)
        if (!started) return commands
        state = State.HOLD
        host.indicate(micLabel())
        when (cue) {
            "tap" -> host.voiceEarcon(VoiceEarcon.Kind.START_TAP)
            "none" -> {}
            else -> host.voiceEarcon(VoiceEarcon.Kind.START_HOLD)
        }
        return commands
    }

    fun legacyCue(tap: Boolean) {
        if (state != State.OFF) host.voiceEarcon(if (tap) VoiceEarcon.Kind.START_TAP else VoiceEarcon.Kind.START_HOLD)
    }

    fun legacyStop() {
        if (state == State.PRESSED || state == State.HOLD) stopHold()
    }

    // ---- 録音 ----

    /** 録音を始める（打っている途中の文字は確定する。その命令と、録音を始められたか） */
    private fun begin(c: Client, english: Boolean): Pair<List<ImeCommand>, Boolean> {
        host.activateClient(c)
        val commands = host.runCommands(engine.commitAll())
        val id = ++recording
        val started = voice.start(
            english,
            onPartial = { text -> synchronized(lock) { onPartial(id, text) } },
            // 長押しのあいだは区切らないので、ロックを取らずに捨てる（録音のスレッドを待たせない）
            onAudio = { chunk -> if (listening == id) synchronized(lock) { onAudio(id, chunk) } },
        )
        if (!started) {
            host.indicate(Ui.t("マイクを使えません", "Microphone unavailable"), 2000)
            // フロントは押したときに録音中と思っているので、戻す
            host.publishVoiceState("off")
            return commands to false
        }
        client = c
        this.english = english
        partial = null
        // 「🎤」は押し方が決まってから出す（Windows の DLL は最初の表示の窓を作るのに 0.3 秒ほどかかり、そのあいだ離したキーが届かず、短く押したのに長押しになるため）
        host.publishVoiceState("hold")
        return commands to true
    }

    /** 聞き続けるモードで区切っている録音の回（録音のスレッドがロックを取らずに見る。0 なら区切らない） */
    @Volatile private var listening = 0

    private fun onPartial(id: Int, text: String) {
        if (id != recording) return
        when (state) {
            State.HOLD -> host.indicate("🎤 $text")
            State.PRESSED -> {}
            State.LISTEN -> if (segmenter?.inSpeech == true) {
                partial = text
                host.indicate("🎤 $text")
            }
            State.OFF -> {}
        }
    }

    private fun onAudio(id: Int, chunk: FloatArray) {
        if (id != recording || state != State.LISTEN) return
        val seg = segmenter ?: return
        for (event in seg.feed(chunk)) {
            when (event) {
                ContinuousSegmenter.Event.Start -> partial = null
                is ContinuousSegmenter.Event.Segment -> {
                    submit(event.pcm)
                    if (state == State.LISTEN) host.indicate(listening())
                }
                ContinuousSegmenter.Event.Idle -> {
                    stopListening(keep = true, message = Ui.t("🎤 話さなかったので止めました", "🎤 Stopped (no speech)"))
                }
            }
            if (state != State.LISTEN || id != recording) return
        }
        // 録音の側にためる音も、区切りの判定と同じだけにする（話している途中の文字を今の区切りだけで作る）
        voice.trim(seg.bufferedSamples())
    }

    private fun stopHold() {
        cancelHoldCue?.invoke()
        state = State.OFF
        host.publishVoiceState("off")
        val english = english
        val target = client
        host.indicate(Ui.t("🎤 認識中", "🎤 Recognizing"))
        voice.stop { text ->
            synchronized(lock) {
                host.indicate(null)
                // 認識が終わった合図: 文字が入るなら上がる1音、入らなければ下がる2音
                host.voiceEarcon(if (text.isNullOrEmpty()) VoiceEarcon.Kind.DONE_EMPTY else VoiceEarcon.Kind.DONE_TEXT)
                if (text.isNullOrEmpty() || target == null) return@synchronized
                // 認識しているあいだに打ち始めていたら、その文字を先に確定する
                val commands = host.runCommands(engine.commitAll()) + ImeCommand.Commit(text)
                if (!english) addContext(text)
                lastVoice = text
                target.send(host.replyLines(commands))
            }
        }
    }

    /** 押している間の録音を捨てる（[quiet] なら録音キーをすぐ離した押し間違いで、何も出さない） */
    private fun cancelHold(quiet: Boolean) {
        cancelHoldCue?.invoke()
        state = State.OFF
        host.publishVoiceState("off")
        voice.cancel()
        if (!quiet) host.voiceEarcon(VoiceEarcon.Kind.DONE_EMPTY)
        if (quiet) host.indicate(null) else host.indicate(Ui.t("🎤 取り消しました", "🎤 Canceled"), 1000)
    }

    // ---- 聞き続けるモード ----

    private fun listening() = Ui.t("🎤 聞いています", "🎤 Listening")

    private fun micLabel() = if (english) "🎤 EN" else "🎤"

    private fun startListening() {
        cancelHoldCue?.invoke()
        state = State.LISTEN
        listening = recording
        host.voiceEarcon(VoiceEarcon.Kind.START_TAP)
        // 押しているあいだに録った音は捨てて、ここから区切る
        voice.trim(0)
        segmenter = ContinuousSegmenter(WhisperModel.SAMPLE_RATE, idleMs = idleSeconds * 1000L)
        partial = null
        gotText = false
        host.publishVoiceState("listen")
        host.indicate(listening())
    }

    /**
     * 聞き続けるモードを終える。[keep] なら話している途中の音声を文字にして入れる。
     * [keep] でなければ（Esc・入力欄の移動）、話している途中の音声と、まだ届いていない認識の結果を捨てる
     */
    private fun stopListening(keep: Boolean, message: String?) {
        if (state != State.LISTEN) return
        state = State.OFF
        listening = 0
        recording++
        val pcm = segmenter?.flush()
        segmenter = null
        voice.halt()
        // 終わりの合図: このモードで文字が入ったなら上がる1音、入らなければ下がる2音（取り消しは入らなかった扱い）
        host.voiceEarcon(if (gotText && keep) VoiceEarcon.Kind.DONE_TEXT else VoiceEarcon.Kind.DONE_EMPTY)
        host.publishVoiceState("off")
        if (keep) {
            if (pcm != null) submit(pcm)
        } else {
            generation++
            pending.clear()
            partial = null
        }
        if (message != null) host.indicate(message, 1200) else host.indicate(null)
    }

    /** ひと区切りを認識に回す。結果は話した順に [deliver] する */
    private fun submit(pcm: FloatArray) {
        val seq = nextSeq++
        val gen = generation
        val fallback = partial
        partial = null
        val candidate = commandsEnabled && !english && VoiceCommand.isCandidate(pcm, WhisperModel.SAMPLE_RATE)
        voice.recognize(pcm, english) { text ->
            synchronized(lock) {
                results[seq] = Result(gen, text, fallback, candidate)
                while (results.isNotEmpty() && results.firstKey() == nextDeliver) {
                    val r = results.remove(nextDeliver)!!
                    nextDeliver++
                    if (r.generation == generation) deliver(r)
                }
            }
        }
    }

    private fun deliver(r: Result) {
        val command = if (r.candidate && r.text != null) VoiceCommand.match(r.text) else null
        if (command != null) {
            runCommand(command)
            return
        }
        // 確定の文が作れなければ、話している途中に出していた文を使う
        val text = (r.text ?: r.fallback)?.takeIf { it.isNotEmpty() } ?: return
        gotText = true
        lastVoice = text
        // 打っている途中の文字を上書きしないよう、それが確定するまで待つ（前に待たせた文があれば、その後ろに並べる）
        pending.append(text)
        val target = client ?: return
        val commands = flushPending(target)
        if (commands.isNotEmpty()) target.send(host.replyLines(commands))
    }

    /** キー・候補のクリックを処理したあと: 打っている途中でなくなったら、待たせていた文を入れる命令 */
    fun flushPending(c: Client): List<ImeCommand> {
        if (pending.isEmpty() || engine.isComposing || c !== client) return emptyList()
        val text = pending.toString()
        pending.clear()
        if (!english) addContext(text)
        return listOf(ImeCommand.Commit(text))
    }

    private fun addContext(text: String) {
        engine.leftContext = (engine.leftContext + text).takeLast(InputEngine.MAX_CONTEXT)
    }

    private fun runCommand(c: VoiceCommand) {
        val label = when (c) {
            VoiceCommand.SEND -> Ui.t("送信", "Send")
            VoiceCommand.NEXT -> Ui.t("次へ", "Next")
            VoiceCommand.STOP -> Ui.t("止める", "Stop")
            VoiceCommand.UNDO -> Ui.t("取り消し", "Undo")
            VoiceCommand.CLEAR -> Ui.t("全部消す", "Clear")
        }
        host.indicate("🎤 $label", 1500)
        host.log("voice: command $c")
        when (c) {
            VoiceCommand.SEND -> {
                // 打っている途中の文字と、待たせていた文を先に入れてから送る
                val target = client
                if (target != null) {
                    val commands = host.runCommands(engine.commitAll()) + flushPending(target)
                    if (commands.isNotEmpty()) target.send(host.replyLines(commands))
                }
                action("send")
            }
            VoiceCommand.NEXT -> action("next")
            VoiceCommand.CLEAR -> {
                lastVoice = null
                pending.clear()
                engine.leftContext = ""
                action("clear")
            }
            VoiceCommand.UNDO -> {
                val text = lastVoice ?: return host.log("voice: nothing to undo")
                lastVoice = null
                host.log("voice: undo ${text.length} chars (pending ${pending.length})")
                if (pending.endsWith(text)) {
                    pending.setLength(pending.length - text.length)
                } else {
                    engine.leftContext = engine.leftContext.removeSuffix(text)
                    action("undo", text)
                }
            }
            VoiceCommand.STOP -> stopListening(keep = true, message = Ui.t("🎤 止めました", "🎤 Stopped"))
        }
    }

    /** 声のコマンドの実行をフロントへ頼む（voiceaction <send|next|clear|undo> [消す文]） */
    private fun action(kind: String, text: String? = null) {
        val target = client ?: return host.log("voice: no client for $kind")
        val fields = listOfNotNull("c", "0", "voiceaction", kind, text)
        target.send(listOf(Protocol.encode(fields), Protocol.encode("done", 0, 0)))
    }

    companion object {
        /** これより短く押して離したら聞き続けるモード（Android 版・macOS 版と同じ） */
        const val TAP_MS = 300L
    }
}
