package dev.mooma.kanade

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 音声認識のモデルを設定画面からそのまま落とす（2026-10-03、本人の判断で Kanade にネットの権限を付けた）。
 * ネットにつなぐのはここだけ（モデルの取得）。打った文字や音声は外へ送らない。
 * 途中で止まったら `.part` から続きを取り、大きさが合ったら本来の名前にする。
 */
object ModelDownloader {
    class Item(val url: String, val dest: File, val bytes: Long)

    @Volatile var running = false
        private set
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /**
     * [items] を順に落とす（専用のスレッドで）。[progress] は（何個目, 落とした量, 全体の量）、[done] は失敗の理由（成功なら null）。
     * どちらも UI スレッドではないところから呼ぶ。[context] は失敗の理由の文字を引くため
     */
    fun start(context: Context, items: List<Item>, progress: (Int, Long, Long) -> Unit, done: (String?) -> Unit): Boolean {
        if (running) return false
        running = true
        cancelled = false
        Thread({
            val error = runCatching {
                for ((i, item) in items.withIndex()) {
                    if (item.dest.exists() && item.dest.length() == item.bytes) continue
                    fetch(context, item) { got -> progress(i, got, item.bytes) }
                    if (cancelled) break
                }
                if (cancelled) context.getString(R.string.voice_download_cancelled) else null
            }.getOrElse { context.getString(R.string.voice_download_failed, it.message ?: it.javaClass.simpleName) }
            running = false
            done(error)
        }, "kanade-download").start()
        return true
    }

    private fun fetch(context: Context, item: Item, progress: (Long) -> Unit) {
        item.dest.parentFile?.mkdirs()
        val part = File(item.dest.path + ".part")
        if (part.length() > item.bytes) part.delete()
        var got = part.length()
        val conn = URL(item.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        if (got > 0) conn.setRequestProperty("Range", "bytes=$got-")
        try {
            val code = conn.responseCode
            // 続きを頼んでも全体が返ってきたら、最初から取り直す
            val append = got > 0 && code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) error("HTTP $code")
            if (!append) got = 0
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (!cancelled) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        if (got - lastReport >= 1 shl 20) {
                            lastReport = got
                            progress(got)
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (cancelled) return
        if (part.length() != item.bytes) {
            part.delete()
            error(context.getString(R.string.voice_download_size_mismatch, part.length(), item.bytes))
        }
        if (!part.renameTo(item.dest)) error(context.getString(R.string.voice_download_rename_failed))
        progress(item.bytes)
    }
}
