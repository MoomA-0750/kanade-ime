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
        else -> {
            println(
                """
                kanade-engine serve [--data DIR] [--socket PATH]   DLL を待つ（常駐するエンジン）
                kanade-engine type [--data DIR] <キー列>...         エンジンを同じプロセスで動かし、キー列を流して結果を出す
                kanade-engine send [--socket PATH] <キー列>...      動いているエンジンへソケット越しにキー列を流す
                キー列: 文字はそのまま、特別なキーは {Space} {Enter} {BS} {Esc} {F7} {Ctrl+Space} {RAlt} {Henkan} など
                """.trimIndent(),
            )
            exitProcess(if (opts.isEmpty()) 0 else 2)
        }
    }
}

private fun log(s: String) = System.err.println("[kanade] ${java.time.LocalTime.now().withNano(0)} $s")

private fun newHost(data: File, view: CandidateView = object : CandidateView {}): EngineHost {
    val started = System.nanoTime()
    val source = DesktopResources(data).candidateSource()
    log("辞書を読んだ ${(System.nanoTime() - started) / 1_000_000}ms")
    return EngineHost(InputEngine(candidateSource = source), view)
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
    val view = if (java.awt.GraphicsEnvironment.isHeadless()) object : CandidateView {} else SwingCandidateView()
    val host = newHost(data, view)
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
