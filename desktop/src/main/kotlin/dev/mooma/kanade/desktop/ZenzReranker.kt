package dev.mooma.kanade.desktop

import dev.mooma.kanade.core.ImeCommand
import dev.mooma.kanade.core.convert.Reranker
import dev.mooma.kanade.zenz.ZenzModel
import java.io.File

/**
 * zenz での並べ替え（Android 版と同じ Reranker）。ネイティブ（kanade_zenz.dll / libkanade_zenz.so）とモデルは、
 * 最初の依頼のときに読む（エンジンの起動を遅くしない）。読めなければ以後は何もしない（辞書だけで変換する）
 */
class ZenzReranker(private val library: File, private val model: File, private val log: (String) -> Unit) : RerankService {
    private var zenz: ZenzModel? = null
    private var failed = false

    @Synchronized
    private fun model(): ZenzModel? {
        if (zenz != null || failed) return zenz
        val started = System.nanoTime()
        zenz = runCatching {
            System.load(library.absolutePath)
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            ZenzModel.open(model.absolutePath, threads = threads)
        }.onFailure { log("zenz を読めない: $it") }.getOrNull()
        failed = zenz == null
        if (zenz != null) log("zenz を読んだ ${(System.nanoTime() - started) / 1_000_000}ms")
        return zenz
    }

    override fun rerank(request: ImeCommand.RequestRerank): List<String>? {
        val m = model() ?: return null
        return Reranker(m).rerank(request.leftContext, request.reading, request.candidates, rightContext = request.rightContext)?.candidates
    }

    override fun rankPredictions(request: ImeCommand.RequestPredictionRank): List<String>? {
        val m = model() ?: return null
        return Reranker(m).rankPredictions(request.leftContext, request.items)
    }

    override fun cancel() {
        zenz?.cancel()
    }

    companion object {
        /** ネイティブの置き場所: -Dkanade.native、なければエンジンの横の native フォルダ */
        fun libraryFile(): File {
            val name = System.mapLibraryName("kanade_zenz")
            System.getProperty("kanade.native")?.let { return File(it, name) }
            val jar = File(ZenzReranker::class.java.protectionDomain.codeSource.location.toURI())
            return File(jar.parentFile.parentFile, "native/$name")
        }
    }
}
