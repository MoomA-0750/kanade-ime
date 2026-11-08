package dev.mooma.kanade.desktop

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * TSF の DLL と同じ手順でエンジンと話す、試験用の偽物。アプリの入力欄（確定した文と入力中の文字）も真似る。
 * 本物の DLL（windows/tsf）の動きを変えたら、ここも合わせる
 */
class FakeDll(private val out: (String) -> Unit) {
    /** 確定してアプリに入った文 */
    val document = StringBuilder()
    /** 入力中の文字（アプリでは下線付きで出ているもの） */
    var composition = ""
        private set
    var japanese = false
        private set
    /** いま出ている候補の窓（出ていなければ空）と、選んでいる位置 */
    var candidates: List<String> = emptyList()
        private set
    var selectedCandidate = -1
        private set

    /** 届いたものすべて（確かめる用） */
    val received = mutableListOf<List<String>>()

    private var nextId = 1
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Boolean>>()
    private val batch = mutableListOf<List<String>>()

    fun hello(kind: String = "fake") = out(Protocol.encode("hello", 1, ProcessHandle.current().pid(), kind))

    /** 返事を待たない1行を送る（fcitx5 のアドオンの voice など） */
    fun send(vararg fields: Any) = out(Protocol.encode(*fields))

    fun press(keys: List<WinKey>) = keys.forEach { key(it) }

    fun type(script: String) = press(KeyScript.parse(script))

    /**
     * 本物の DLL と同じく、OnTestKeyDown の時点でエンジンに処理させる（`key`）。使わず書くものもないキーはアプリへ。
     * 書くものがあれば書き、使わなかったキーはそのあとアプリへ届いたことにする（TSF に対応したアプリと同じ）
     */
    fun key(key: WinKey) {
        val upDown = if (key.down) "d" else "u"
        val consumed = request("key", upDown, key.vk, key.char, key.mods, if (key.repeat) 1 else 0)
        if (!consumed && key.down) appReceives(key)
    }

    fun commitAll() = request("commitall")

    /** 候補の窓で候補をクリックした */
    fun select(index: Int) = out(Protocol.encode("select", index))

    private fun request(type: String, vararg fields: Any): Boolean {
        val id = synchronized(this) { nextId++ }
        val future = CompletableFuture<Boolean>()
        pending[id] = future
        out(Protocol.encode(type, id, *fields))
        // 本物の DLL も、返事が遅ければ（エンジンが落ちているなど）素通しにする
        return runCatching { future.get(2, TimeUnit.SECONDS) }.getOrDefault(false).also { pending.remove(id) }
    }

    /** エンジンから届いた1行 */
    @Synchronized
    fun deliver(line: String) {
        val f = Protocol.decode(line)
        when (f[0]) {
            "c" -> batch += f.drop(2)
            "done" -> {
                val commands = batch.toList()
                batch.clear()
                commands.forEach(::apply)
                pending[f[1].toInt()]?.complete(f.getOrNull(2) == "1")
            }
        }
    }

    private fun apply(c: List<String>) {
        received += c
        when (c[0]) {
            "compose" -> composition = c[1]
            "commit" -> {
                composition = ""
                document.append(c[1])
            }
            "mode" -> japanese = c[1] == "1"
            "cands" -> {
                selectedCandidate = c[1].toInt()
                candidates = (0 until c[2].toInt()).map { c[3 + it * 2] }
            }
            "hidecands" -> {
                candidates = emptyList()
                selectedCandidate = -1
            }
            "replace" -> {
                val expected = c[1]
                if (document.endsWith(expected)) {
                    document.setLength(document.length - expected.length)
                    document.append(c[3])
                } else {
                    document.append(c[2])
                }
            }
        }
    }

    private fun appReceives(key: WinKey) {
        when {
            key.vk == VK.BACK -> if (document.isNotEmpty()) document.setLength(document.length - 1)
            key.vk == VK.RETURN -> document.append('\n')
            key.char >= 0x20 && key.mods and (Mods.CTRL or Mods.ALT or Mods.WIN) == 0 -> document.append(key.char.toChar())
        }
    }

    override fun toString() = if (composition.isEmpty()) document.toString() else "$document[$composition]"

    companion object {
        /** エンジンを同じプロセスの中で直接つなぐ */
        fun inProcess(host: EngineHost, id: Int = 1): FakeDll {
            lateinit var dll: FakeDll
            val client = object : Client {
                override val id = id
                override fun send(lines: List<String>) = lines.forEach { dll.deliver(it) }
            }
            dll = FakeDll { host.onLine(client, it) }
            return dll
        }
    }
}
