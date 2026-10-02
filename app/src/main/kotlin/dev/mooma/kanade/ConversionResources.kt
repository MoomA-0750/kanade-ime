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
        return DictionaryCandidateSource(
            converter(app), Segmenter(PosTable.read(mapAsset(app, "pos.kpos"))), table("symbol.ksym"), table("emoji.ksym"),
        )
    }

    /** zenz を読む。重いので UI スレッドから呼ばないこと。失敗したら null（辞書だけで変換する） */
    @Synchronized
    fun zenz(context: Context): ZenzModel? {
        val app = context.applicationContext
        // 設定で small を選び、取り込んであれば small。そうでなければ APK に入れた xsmall
        val wantSmall = KanadeSettings(app).zenzSmall && ZenzSmall.isReady(app)
        if (zenz != null && loadedSmall == wantSmall) return zenz
        // 前のモデルは閉じない（IME のスレッドが推論中かもしれず、閉じると落ちる。替えるのはまれなので、プロセスが終わるまで残す）
        zenz = null
        return runCatching {
            System.loadLibrary("kanade_zenz")
            val model = if (wantSmall) ZenzSmall.path(app) else copyModel(app)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            ZenzModel.open(model.absolutePath, threads = threads)
        }.getOrNull().also {
            zenz = it
            loadedSmall = wantSmall
        }
    }

    /** いま読んでいる zenz が small か（設定画面の表示用） */
    @Volatile var loadedSmall = false
        private set

    /**
     * gzip で入れた asset（`name.kgz`）をアプリ内へ展開して mmap する。展開は APK を入れ直したとき（更新時刻が変わったとき）だけ。
     * 古い版の展開先は消す
     */
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

/**
 * zenz-v3.2-small（74MB）。xsmall（APK に同梱）より正解率が少し高い（手元の評価で 92%→94%）が、3〜4 倍遅い。
 * 端末で試せるよう、whisper と同じくブラウザで取得して設定画面で取り込む
 */
object ZenzSmall {
    const val URL = "https://huggingface.co/Miwa-Keita/zenz-v3.2-small-gguf/resolve/c67e03e07d215c869f591b274c1631170d3e11fe/ggml-model-Q5_K_M.gguf"
    const val BYTES = 73_871_936L

    fun path(context: Context) = File(context.getExternalFilesDir("models"), "zenz-v3.2-small-Q5_K_M.gguf")

    fun isReady(context: Context) = path(context).let { it.exists() && it.length() == BYTES }

    /** 選んだファイルをアプリ内へ写す。大きさが合わなければ false。UI スレッドから呼ばないこと */
    fun importFrom(context: Context, uri: android.net.Uri): Boolean {
        val dest = path(context)
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } } ?: return false
        if (tmp.length() != BYTES) {
            tmp.delete()
            return false
        }
        return tmp.renameTo(dest)
    }
}
