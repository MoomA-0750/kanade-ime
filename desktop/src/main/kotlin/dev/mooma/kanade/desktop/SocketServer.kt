package dev.mooma.kanade.desktop

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString

/**
 * DLL とつなぐ口。AF_UNIX のソケット（Windows 10 1803 以降は Winsock でも使える）にして、Linux でも同じ形で試せるようにする。
 * 名前付きパイプにしないのは、Java だけでは作れず（JNA が要る）、同期の読み書きが同じパイプで直列になるため（docs/WINDOWS-PLAN.md）
 */
class SocketServer(private val path: Path, private val host: EngineHost, private val log: (String) -> Unit = {}) {
    private val ids = AtomicInteger()

    /** 別のエンジンがもう動いていれば false */
    fun start(): Boolean {
        if (Files.exists(path)) {
            if (alive()) return false
            Files.delete(path)
        }
        Files.createDirectories(path.parent)
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(path))
        log("listening ${path.absolutePathString()}")
        Thread({
            while (true) {
                val ch = runCatching { server.accept() }.getOrElse { break }
                val client = SocketClient(ids.incrementAndGet(), ch)
                Thread({ serve(client) }, "client-${client.id}").apply { isDaemon = true }.start()
            }
        }, "accept").start()
        return true
    }

    private fun alive(): Boolean = runCatching {
        SocketChannel.open(UnixDomainSocketAddress.of(path)).use { true }
    }.getOrDefault(false)

    private fun serve(client: SocketClient) {
        log("client ${client.id} connected")
        try {
            val reader = BufferedReader(InputStreamReader(Channels.newInputStream(client.channel), Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue
                try {
                    host.onLine(client, line)
                } catch (e: Exception) {
                    // 1つの命令の失敗で DLL との接続を切らない（DLL は返事が来なければ素通しにする）
                    log("client ${client.id}: $line -> $e")
                }
            }
        } catch (_: Exception) {
        } finally {
            host.disconnected(client)
            runCatching { client.channel.close() }
            log("client ${client.id} closed")
        }
    }

    private class SocketClient(override val id: Int, val channel: SocketChannel) : Client {
        private val out: OutputStream = Channels.newOutputStream(channel)

        override fun send(lines: List<String>) {
            val bytes = lines.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8)
            synchronized(this) {
                runCatching {
                    out.write(bytes)
                    out.flush()
                }
            }
        }
    }

    companion object {
        /** Windows は %LOCALAPPDATA%\Kanade\engine.sock、ほかは $XDG_RUNTIME_DIR/kanade-engine.sock */
        fun defaultPath(): Path {
            val local = System.getenv("LOCALAPPDATA")
            if (local != null) return Path.of(local, "Kanade", "engine.sock")
            val runtime = System.getenv("XDG_RUNTIME_DIR") ?: System.getProperty("java.io.tmpdir")
            return Path.of(runtime, "kanade-engine.sock")
        }
    }
}
