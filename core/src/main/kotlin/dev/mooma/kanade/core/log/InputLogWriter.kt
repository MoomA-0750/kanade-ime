package dev.mooma.kanade.core.log

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * 入力の記録を、日ごとの `YYYY-MM-DD.jsonl` に追記する。[record] は待たない（待ち行列に入れるだけ。あふれたら捨てて数える）。
 * 別のデーモンのスレッドが 2 秒ごと・256 件ごとにまとめて書く。古い日と、合計の容量を超えた分は古い日から消す。
 * ファイルは 0600、フォルダーは 0700（取れない OS では何もしない）。外へは送らない。
 *
 * @param startThread false なら書くスレッドを起こさない（試験用。[flush] で書く）
 */
class InputLogWriter(
    val dir: File,
    private val keepDays: () -> Int = { 90 },
    private val maxBytes: () -> Long = { 200L * 1024 * 1024 },
    private val zone: ZoneId = ZoneId.systemDefault(),
    startThread: Boolean = true,
) {
    private val queue = ArrayBlockingQueue<InputEvent>(QUEUE_SIZE)
    private val dropped = AtomicInteger()
    private val ioLock = Any()
    @Volatile private var closed = false

    /** ファイルの頭に1行入れる出来事（`start`）。日が変わって新しいファイルを開くたびに書く */
    @Volatile var header: (() -> InputEvent)? = null

    private var currentDay: LocalDate? = null
    private val thread: Thread? = if (startThread) Thread(::loop, "input-log").apply { isDaemon = true; start() } else null

    /** 待たずに入れる。入らなければ捨てて数える */
    fun record(event: InputEvent) {
        if (closed) return
        if (!queue.offer(event)) dropped.incrementAndGet()
        else if (thread != null && queue.size >= BATCH) synchronized(wake) { wake.notifyAll() }
    }

    private val wake = Object()

    private fun loop() {
        while (!closed) {
            synchronized(wake) { if (queue.size < BATCH) wake.wait(2000) }
            runCatching { flush() }
        }
    }

    /** 待ち行列のものを今すぐ書く（Android の `onFinishInput`・終わるとき・試験） */
    fun flush() = synchronized(ioLock) {
        val batch = ArrayList<InputEvent>()
        queue.drainTo(batch)
        val lost = dropped.getAndSet(0)
        if (lost > 0) batch += InputEvent(batch.lastOrNull()?.t ?: System.currentTimeMillis(), "dropped", mapOf("n" to lost))
        if (batch.isEmpty()) return@synchronized
        // 日ごとにまとめて書く
        batch.groupBy { dayOf(it.t) }.forEach { (day, events) -> append(day, events) }
    }

    fun close() {
        closed = true
        synchronized(wake) { wake.notifyAll() }
        runCatching { thread?.join(1000) }
        runCatching { flush() }
    }

    private fun dayOf(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()

    private fun fileOf(day: LocalDate) = File(dir, "$day.jsonl")

    private fun append(day: LocalDate, events: List<InputEvent>) {
        ensureDir()
        if (currentDay != day) {
            currentDay = day
            prune(day)
        }
        val file = fileOf(day)
        if (file.exists() && file.length() > DAY_LIMIT) return
        val isNew = !file.exists()
        val text = StringBuilder()
        if (isNew) header?.invoke()?.let { text.append(it.toJson()).append('\n') }
        for (e in events) text.append(e.toJson()).append('\n')
        file.appendText(text.toString(), Charsets.UTF_8)
        if (isNew) restrict(file, "rw-------")
    }

    private fun ensureDir() {
        if (dir.isDirectory) return
        dir.mkdirs()
        restrict(dir, "rwx------")
    }

    private fun restrict(f: File, perms: String) {
        runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(perms)) }
    }

    /** 古い日・容量を超えた分を、古い日から消す。[today] のファイルは消さない */
    fun prune(today: LocalDate = LocalDate.now(zone)) {
        val files = logFiles().sortedBy { it.name }
        val limit = today.minusDays(keepDays().toLong())
        for (f in files) if (dateOf(f)?.isBefore(limit) == true) f.delete()
        var rest = logFiles().sortedBy { it.name }
        var total = rest.sumOf { it.length() }
        val cap = maxBytes()
        for (f in rest) {
            if (total <= cap) break
            if (dateOf(f) == today) continue
            total -= f.length()
            f.delete()
        }
    }

    private fun logFiles(): List<File> = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") && dateOf(f) != null }?.toList().orEmpty()

    private fun dateOf(f: File): LocalDate? = runCatching { LocalDate.parse(f.name.removeSuffix(".jsonl")) }.getOrNull()

    /** 全部消す（設定の画面から） */
    fun clearAll(): Int = synchronized(ioLock) {
        queue.clear()
        dropped.set(0)
        val files = logFiles()
        files.forEach { it.delete() }
        currentDay = null
        files.size
    }

    /** 件数（ファイル数）と合計のバイト数 */
    fun stats(): Pair<Int, Long> = logFiles().let { it.size to it.sumOf { f -> f.length() } }

    /** 新しい日から、最後の [n] 行（設定の画面で見る） */
    fun tail(n: Int): List<String> {
        val out = ArrayDeque<String>()
        for (f in logFiles().sortedByDescending { it.name }) {
            val lines = runCatching { f.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
            for (l in lines.asReversed()) {
                out.addFirst(l)
                if (out.size >= n) return out.toList()
            }
        }
        return out.toList()
    }

    companion object {
        const val QUEUE_SIZE = 4096
        const val BATCH = 256

        /** 1日のファイルがこれを超えたら、その日はもう書かない */
        const val DAY_LIMIT = 20L * 1024 * 1024
    }
}
