package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.convert.DictionaryCandidateSource
import dev.mooma.kanade.core.convert.KanaKanjiConverter
import dev.mooma.kanade.core.convert.Segmenter
import dev.mooma.kanade.core.dict.ConnectionMatrix
import dev.mooma.kanade.core.dict.PosTable
import dev.mooma.kanade.core.dict.SymbolTable
import dev.mooma.kanade.core.dict.SystemDictionary
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

/**
 * 辞書を [dataDir] から読む。中身は Android 版の assets と同じ（`dictool assets` が作るもの）。
 * gzip のまま（`.kgz`）置いてあれば、[cacheDir] へ展開する（`.kgz` が新しくなったら展開し直す）。
 * 展開先を分けるのは、手元では [dataDir] が APK の assets のフォルダで、展開したものが APK に入ってしまうため
 */
class DesktopResources(private val dataDir: File, private val cacheDir: File = defaultCacheDir()) {
    fun candidateSource(): DictionaryCandidateSource {
        val converter = KanaKanjiConverter(SystemDictionary(map("system.kdic")), ConnectionMatrix(map("connection.kcon")))
        fun table(name: String) = File(dataDir, name).takeIf { it.exists() }?.let { SymbolTable.parse(it.readText()) } ?: SymbolTable(emptyMap())
        val source = DictionaryCandidateSource(converter, Segmenter(PosTable.read(map("pos.kpos"))), table("symbol.ksym"), table("emoji.ksym"))
        // カタカナ→英語の表記の表（約11万の読み）は読むのに時間がかかるので、Android 版と同じく裏で読んで差し替える
        Thread({ runCatching { source.setEnglishTable(table("english.ksym")) } }, "english").apply { isDaemon = true }.start()
        return source
    }

    /** zenz のモデル（なければ null） */
    val zenzModel: File? get() = File(dataDir, "zenz.gguf").takeIf { it.exists() }

    private fun map(name: String): MappedByteBuffer {
        var file = File(dataDir, name)
        if (!file.exists()) {
            val gz = File(dataDir, "$name.kgz")
            require(gz.exists()) { "${file.path} も ${gz.path} もない" }
            file = File(cacheDir, name)
            if (!file.exists() || file.lastModified() < gz.lastModified()) {
                cacheDir.mkdirs()
                val tmp = File(cacheDir, "$name.part")
                GZIPInputStream(gz.inputStream(), 1 shl 16).use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
                file.delete()
                require(tmp.renameTo(file)) { "${file.path} に置けない" }
            }
        }
        FileInputStream(file).channel.use { ch -> return ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()) }
    }

    companion object {
        /**
         * Windows は %LOCALAPPDATA%\Kanade\data。Linux は入れた場所（エンジンの lib の隣）の data があればそこ
         * （~/.local/share/kanade/data。音声のモデルもここへ落とす）、なければリポジトリのビルド結果（app/build/generated/kanadeAssets）
         */
        fun defaultDataDir(): File {
            val local = System.getenv("LOCALAPPDATA")
            if (local != null) return File(local, "Kanade\\data")
            installRoot()?.let { File(it, "data") }?.takeIf { it.isDirectory }?.let { return it }
            return File("app/build/generated/kanadeAssets")
        }

        fun defaultCacheDir(): File {
            val local = System.getenv("LOCALAPPDATA")
            if (local != null) return File(local, "Kanade\\cache")
            val cache = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotEmpty() } ?: (System.getProperty("user.home") + "/.cache")
            return File(cache, "kanade")
        }

        /** エンジンを入れた場所（lib/ の jar の1つ上）。jar から動いていなければ null */
        fun installRoot(): File? = runCatching {
            File(DesktopResources::class.java.protectionDomain.codeSource.location.toURI()).takeIf { it.isFile }?.parentFile?.parentFile
        }.getOrNull()
    }
}
