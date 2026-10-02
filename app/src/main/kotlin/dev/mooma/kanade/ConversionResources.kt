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
        KanaKanjiConverter(SystemDictionary(mapAsset(app, "system.kdic")), ConnectionMatrix(mapAsset(app, "connection.kcon")))
            .also { converter = it }
    }

    /** 入力エンジンに渡す候補の出どころ（辞書とラティス、文節分け） */
    fun candidateSource(context: Context): DictionaryCandidateSource {
        val app = context.applicationContext
        fun table(name: String) = SymbolTable.parse(app.assets.open(name).bufferedReader().readText())
        return DictionaryCandidateSource(
            converter(app), Segmenter(PosTable.read(mapAsset(app, "pos.kpos"))), table("symbol.ksym"), table("emoji.ksym"),
        )
    }

    /** zenz を読む。重いので UI スレッドから呼ばないこと。失敗したら null（辞書だけで変換する） */
    @Synchronized
    fun zenz(context: Context): ZenzModel? = zenz ?: run {
        val app = context.applicationContext
        runCatching {
            System.loadLibrary("kanade_zenz")
            val model = copyModel(app)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            ZenzModel.open(model.absolutePath, threads = threads)
        }.getOrNull().also { zenz = it }
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
