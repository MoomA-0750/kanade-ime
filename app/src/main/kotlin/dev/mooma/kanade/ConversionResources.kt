package dev.mooma.kanade

import android.content.Context
import dev.mooma.kanade.core.CandidateSource
import dev.mooma.kanade.core.convert.DictionaryCandidateSource
import dev.mooma.kanade.core.convert.KanaKanjiConverter
import dev.mooma.kanade.core.convert.Segmenter
import dev.mooma.kanade.core.dict.PosTable
import dev.mooma.kanade.core.dict.SymbolTable
import dev.mooma.kanade.core.dict.ConnectionMatrix
import dev.mooma.kanade.core.dict.SystemDictionary
import dev.mooma.kanade.zenz.ZenzModel
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * 変換に使う辞書と zenz を読み込む。辞書は APK 内の非圧縮 asset をそのまま mmap する。
 * llama.cpp はファイルのパスを要るので、モデルだけは初回にアプリ内へ写す。
 */
object ConversionResources {
    @Volatile private var converter: KanaKanjiConverter? = null
    @Volatile private var zenz: ZenzModel? = null

    @Synchronized
    fun converter(context: Context): KanaKanjiConverter = converter ?: run {
        val app = context.applicationContext
        KanaKanjiConverter(SystemDictionary(mapExtracted(app, "system.kdic")), ConnectionMatrix(mapExtracted(app, "connection.kcon")))
            .also { converter = it }
    }

    /** 入力エンジンに渡す候補の出どころ（辞書とラティス、文節分け） */
    fun candidateSource(context: Context): DictionaryCandidateSource {
        val app = context.applicationContext
        fun table(name: String) = SymbolTable.parse(app.assets.open(name).bufferedReader().readText())
        val source = DictionaryCandidateSource(
            converter(app), Segmenter(PosTable.read(mapAsset(app, "pos.kpos"))), table("symbol.ksym"), table("emoji.ksym"),
        )
        // カタカナ→英語の表（約11万の読み）は読むのに時間がかかるので、起動を止めないよう裏で読んで差し替える
        Thread({ runCatching { source.setEnglishTable(table("english.ksym")) } }, "kanade-english").start()
        // 表記→読みの逆引き（再変換・音声の文）。初回は展開に時間がかかるので裏で
        Thread({
            runCatching { source.reverse = dev.mooma.kanade.core.dict.ReverseDictionary(SystemDictionary(mapExtracted(app, "reverse.kdic"))) }
        }, "kanade-reverse").start()
        return source
    }

    /** zenz を読む。重いので UI スレッドから呼ばないこと。失敗したら null（辞書だけで変換する） */
    @Synchronized
    fun zenz(context: Context): ZenzModel? {
        val app = context.applicationContext
        zenz?.let { return it }
        // 以前の版で取り込めた small（74MB）は使わなくなったので消す（xsmall と体感が変わらず遅いだけだったため）
        runCatching { java.io.File(app.getExternalFilesDir("models"), "zenz-v3.2-small-Q5_K_M.gguf").delete() }
        return runCatching {
            System.loadLibrary("kanade_zenz")
            val threads = KanadeSettings(app).zenzThreads.coerceIn(1, Runtime.getRuntime().availableProcessors())
            ZenzModel.open(copyModel(app).absolutePath, threads = threads)
        }.getOrNull().also { zenz = it }
    }

    /**
     * gzip で入れた asset（`name.kgz`）をアプリ内へ展開して mmap する。展開は APK を入れ直したとき（更新時刻が変わったとき）だけ。
     * 古い版の展開先は消す
     */
    @Synchronized
    private fun mapExtracted(context: Context, name: String): MappedByteBuffer {
        val updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val root = File(context.noBackupFilesDir, "assets")
        val dir = File(root, updated.toString())
        val file = File(dir, name)
        if (!file.exists()) {
            root.listFiles()?.filter { it != dir }?.forEach { it.deleteRecursively() }
            dir.mkdirs()
            val tmp = File(dir, "$name.part")
            java.util.zip.GZIPInputStream(context.assets.open("$name.kgz"), 1 shl 16).use { input ->
                tmp.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            tmp.renameTo(file)
        }
        FileInputStream(file).channel.use { ch -> return ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()) }
    }

    private fun mapAsset(context: Context, name: String): MappedByteBuffer {
        context.assets.openFd(name).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
            }
        }
    }

    private fun copyModel(context: Context): File {
        val dest = File(context.noBackupFilesDir, "zenz.gguf")
        val length = context.assets.openFd("zenz.gguf").use { it.length }
        if (!dest.exists() || dest.length() != length) {
            val tmp = File(dest.path + ".part")
            context.assets.open("zenz.gguf").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(dest)
        }
        return dest
    }
}
