package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.InputEngine
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val opts = args.toMutableList()
    fun option(name: String): String? {
        val i = opts.indexOf(name)
        if (i < 0 || i + 1 >= opts.size) return null
        return opts[i + 1].also { opts.removeAt(i + 1); opts.removeAt(i) }
    }
    val data = option("--data")?.let(::File) ?: DesktopResources.defaultDataDir()
    val socket = option("--socket")?.let { Path.of(it) } ?: SocketServer.defaultPath()

    when (opts.firstOrNull()) {
        "serve" -> serve(data, socket)
        "type" -> typeLocal(data, opts.drop(1))
        "send" -> sendOverSocket(socket, opts.drop(1))
        "zenz" -> zenzCheck(data, opts.drop(1))
        "voice" -> voiceCheck(data, opts.drop(1))
        else -> {
            println(
                """
                kanade-engine serve [--data DIR] [--socket PATH]   DLL を待つ（常駐するエンジン）
                kanade-engine type [--data DIR] <キー列>...         エンジンを同じプロセスで動かし、キー列を流して結果を出す
                kanade-engine send [--socket PATH] <キー列>...      動いているエンジンへソケット越しにキー列を流す
                kanade-engine zenz [--data DIR] <読み>...           zenz を読み、辞書の候補を並べ替えた結果を出す（ネイティブの確認）
                kanade-engine voice [--data DIR] <wav> | --mic 秒    whisper で wav（16kHz モノラル）を文字にする / マイクで録って文字にする
                キー列: 文字はそのまま、特別なキーは {Space} {Enter} {BS} {Esc} {F7} {Ctrl+Space} {RAlt} {Henkan} など
                """.trimIndent(),
            )
            exitProcess(if (opts.isEmpty()) 0 else 2)
        }
    }
}

private fun log(s: String) = System.err.println("[kanade] ${java.time.LocalTime.now().withNano(0)} $s")

private class Built(val host: EngineHost, val source: dev.mooma.kanade.core.convert.DictionaryCandidateSource, val voice: DesktopVoice?)

private fun newHost(data: File): EngineHost = build(data).host

private fun build(data: File): Built {
    val started = System.nanoTime()
    val resources = DesktopResources(data)
    val source = resources.candidateSource()
    log("辞書を読んだ ${(System.nanoTime() - started) / 1_000_000}ms")
    val library = ZenzReranker.libraryFile()
    val model = resources.zenzModel
    val reranker = if (library.exists() && model != null) ZenzReranker(library, model, ::log) else {
        log("zenz なし（${library.path}、モデル ${model?.path}）")
        null
    }
    // 話している途中の文字（ReazonSpeech）。設定の画面から後で落としても使えるよう、録音を始めるたびに入っているかを見る
    val reazon = ReazonPartial(File(data, "reazonspeech"), ::log)
    // 確定の文のモデルは設定の画面から落として選べるので、いま入っていなくても音声入力は用意しておく
    val voice = DesktopVoice(DesktopVoice.libraryFile(), data, ::log, reazon)
    return Built(EngineHost(InputEngine(candidateSource = source), reranker, voice), source, voice)
}


private fun serve(data: File, socket: Path) {
    // DLL が javaw で起動する（コンソールがない）ので、記録は自分でファイルに書く。1MB を超えたら前のものは捨てる
    System.getenv("LOCALAPPDATA")?.let { local ->
        val file = File(local, "Kanade\\engine.log")
        file.parentFile.mkdirs()
        val append = file.length() < 1_000_000
        System.setErr(java.io.PrintStream(java.io.FileOutputStream(file, append), true, Charsets.UTF_8))
    }
    if (SocketServer.isAlive(socket)) {
        log("別のエンジンが動いている: $socket")
        exitProcess(1)
    }
    val built = build(data)
    val host = built.host
    val app = DesktopApp(DesktopStore(), host, built.source, built.voice, ::log)
    app.start()
    host.onMenu = app::handleMenu
    host.onAdmin = app::handleAdmin
    val started = runCatching { SocketServer(socket, host, ::log).start() }.getOrElse {
        log("待ち受けられない: $it")
        false
    }
    if (!started) {
        log("別のエンジンが動いている: $socket")
        exitProcess(1)
    }
    // 移行のあいだだけ: 前の版の DLL（ソケットを %LOCALAPPDATA%\Kanade に置いていた）を読み込んだままのアプリのために、前の場所でも待つ。
    // インストーラーを作るとき（フェーズ7）に外す
    System.getenv("LOCALAPPDATA")?.let { local ->
        val legacy = Path.of(local, "Kanade", "engine.sock")
        if (legacy != socket) runCatching { SocketServer(legacy, host, ::log).start() }.onFailure { log("前の場所で待てない: $it") }
    }
}

private fun typeLocal(data: File, scripts: List<String>) {
    val dll = FakeDll.inProcess(newHost(data))
    dll.hello()
    for (script in scripts) {
        val started = System.nanoTime()
        dll.type(script)
        println("$script -> $dll  (${(System.nanoTime() - started) / 1_000_000}ms)")
    }
}

private fun sendOverSocket(socket: Path, scripts: List<String>) {
    val ch = SocketChannel.open(UnixDomainSocketAddress.of(socket))
    val out = Channels.newOutputStream(ch)
    val dll = FakeDll { line ->
        synchronized(out) {
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }
    Thread({
        val reader = BufferedReader(InputStreamReader(Channels.newInputStream(ch), Charsets.UTF_8))
        runCatching { while (true) dll.deliver(reader.readLine() ?: break) }
    }, "reader").apply { isDaemon = true }.start()
    dll.hello()
    for (script in scripts) {
        val started = System.nanoTime()
        dll.type(script)
        println("$script -> $dll  (${(System.nanoTime() - started) / 1_000_000}ms)")
    }
    out.write("bye\n".toByteArray())
    ch.close()
}

private fun zenzCheck(data: File, readings: List<String>) {
    val resources = DesktopResources(data)
    val source = resources.candidateSource()
    val library = ZenzReranker.libraryFile()
    val model = resources.zenzModel ?: error("zenz.gguf がない")
    log("ネイティブ ${library.path}")
    val reranker = ZenzReranker(library, model, ::log)
    for (reading in readings) {
        val candidates = source.convert(reading, 5).map { it.text }
        val started = System.nanoTime()
        val ranked = reranker.rerank(dev.mooma.kanade.core.ImeCommand.RequestRerank(1, "", reading, candidates))
        println("$reading: 辞書 $candidates -> zenz $ranked (${(System.nanoTime() - started) / 1_000_000}ms)")
    }
}

private fun voiceCheck(data: File, args: List<String>) {
    val voice = DesktopVoice(DesktopVoice.libraryFile(), data, ::log)
    System.getProperty("kanade.voiceFinal")?.let { voice.finalModel = VoiceFinal.of(it) }
    if (args.firstOrNull() == "--reazon") {
        val reazon = ReazonPartial(File(data, "reazonspeech"), ::log)
        for (wav in args.drop(1)) {
            val bytes = File(wav).readBytes().let { it.copyOfRange(44, it.size) }
            val pcm = FloatArray(bytes.size / 2) { i -> ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f }
            val started = System.nanoTime()
            println("$wav: ${reazon.recognize(pcm)} (${(System.nanoTime() - started) / 1_000_000}ms)")
        }
        return
    }
    if (args.firstOrNull() == "--mic") {
        val seconds = args.getOrNull(1)?.toLongOrNull() ?: 3
        if (!voice.start()) return
        Thread.sleep(seconds * 1000)
        val done = java.util.concurrent.CountDownLatch(1)
        voice.stop { println("マイク: ${it ?: "（なし）"}"); done.countDown() }
        done.await()
        return
    }
    for (wav in args) println("$wav: ${voice.transcribeWav(File(wav))}")
}
