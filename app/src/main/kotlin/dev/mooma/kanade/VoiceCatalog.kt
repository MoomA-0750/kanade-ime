package dev.mooma.kanade

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import org.json.JSONObject
import java.io.File

/** モデルの特徴を表す札（一覧で並べて比べる）。[kind] ごとに並べる順と色を決める */
data class VoiceTag(val kind: Kind, val text: String) {
    enum class Kind { LANGUAGE, FORMAT, ACCURACY, SPEED, PUNCTUATION, ROLE, SIZE }
}

/**
 * 確定の文を作る音声認識のモデル1つ。同梱の一覧（落として使う）と、本人が取り込んだもの（リストにないモデル）がある
 */
class VoiceModelSpec(
    /** 設定に保存する名前（voice_final） */
    val id: String,
    val name: String,
    val tags: List<VoiceTag>,
    /** 札で言い切れない一言（なければ null） */
    val note: String?,
    val custom: Boolean,
    private val ready: (Context) -> Boolean,
    /** 落とすファイル（取り込んだものは null） */
    val download: ((Context) -> List<ModelDownloader.Item>)?,
    private val run: (Context, FloatArray, Int, (String?) -> Unit) -> Unit,
    private val remove: (Context) -> Unit,
) {
    fun isReady(context: Context) = ready(context)

    /** 文字にする（専用のスレッドで）。[onResult] はそのスレッドから呼ばれる */
    fun recognize(context: Context, pcm: FloatArray, threads: Int, onResult: (String?) -> Unit) = run(context, pcm, threads, onResult)

    /** 落とした・取り込んだファイルを消す */
    fun delete(context: Context) = remove(context)
}

/** 取り込めるモデルの形式 */
enum class CustomModelType(val label: String, val help: String) {
    WHISPER_CPP("whisper.cpp（ggml）", "ggml-〜.bin のファイル1つ"),
    TRANSDUCER("sherpa-onnx トランスデューサ（zipformer など）", "encoder・decoder・joiner の .onnx と tokens.txt"),
    NEMO_CTC("sherpa-onnx NeMo CTC（parakeet など）", "model の .onnx と tokens.txt"),
    SENSEVOICE("sherpa-onnx SenseVoice", "model の .onnx と tokens.txt"),
}

/**
 * 音声認識のモデルの一覧。設定画面の「モデル」と、IME が確定の文を作るときに使う
 */
object VoiceCatalog {
    private fun tags(vararg t: Pair<VoiceTag.Kind, String>) = t.map { VoiceTag(it.first, it.second) }

    private fun sherpa(r: SherpaRecognizer, id: String, tags: List<VoiceTag>, note: String?) = VoiceModelSpec(
        id, r.label, tags, note, custom = false,
        ready = { r.isReady(it) },
        download = { r.downloadItems(it) },
        run = { c, pcm, threads, cb -> r.recognize(c, pcm, threads, cb) },
        remove = { c -> r.dir(c).deleteRecursively() },
    )

    private fun whisper(m: VoiceModel, id: String, name: String, tags: List<VoiceTag>, note: String?) = VoiceModelSpec(
        id, name, tags, note, custom = false,
        ready = { m.isReady(it) },
        download = { listOf(ModelDownloader.Item(m.url, m.path(it), m.bytes)) },
        run = { c, pcm, threads, cb -> VoiceInput.transcribe(c, pcm, m.path(c), threads) { text, _ -> cb(text) } },
        remove = { c -> m.path(c).delete() },
    )

    private val L = VoiceTag.Kind.LANGUAGE
    private val A = VoiceTag.Kind.ACCURACY
    private val S = VoiceTag.Kind.SPEED
    private val P = VoiceTag.Kind.PUNCTUATION
    private val R = VoiceTag.Kind.ROLE
    private val Z = VoiceTag.Kind.SIZE

    /** 同梱の一覧（おすすめの順）。速さはエミュレータで 8.8 秒の音声にかけた時間から、精度は公開の評価（JSUT など）から */
    val builtIn: List<VoiceModelSpec> = listOf(
        sherpa(
            SherpaRecognizer.parakeet, "parakeet",
            tags(L to "日本語", A to "精度 高", S to "速い", P to "句読点あり", Z to "656MB"), "NVIDIA の日本語専用のモデルです",
        ),
        sherpa(
            SherpaRecognizer.reazon, "reazon",
            tags(L to "日本語", A to "精度 高", S to "とても速い", P to "句読点なし", R to "途中の表示", Z to "73MB"),
            "話している途中の文字を出すのにも使います。句読点は「くてん」のように話して入れます",
        ),
        sherpa(
            SherpaRecognizer.senseVoice, "sensevoice",
            tags(L to "中・英・日・韓", A to "精度 中", S to "速い", P to "句読点あり", Z to "239MB"), null,
        ),
        whisper(
            VoiceModel.SMALL, "whisper-small", "whisper small",
            tags(L to "多言語", A to "精度 低", S to "遅い", P to "句読点あり", Z to "190MB"), null,
        ),
        whisper(
            VoiceModel.KOTOBA, "whisper-kotoba", "kotoba-whisper v2.0",
            tags(L to "日本語", A to "精度 高", S to "とても遅い", P to "句読点 少", Z to "538MB"), "日本語向けの whisper です",
        ),
        whisper(
            VoiceModel.TURBO, "whisper-turbo", "whisper large-v3-turbo",
            tags(L to "多言語", A to "精度 高", S to "とても遅い", P to "句読点あり", Z to "574MB"), null,
        ),
    )

    private fun customRoot(context: Context) = File(context.getExternalFilesDir("models"), "custom")

    /** 取り込んだモデルの sherpa-onnx の認識（同じものを使い回す） */
    private val customSherpa = HashMap<String, SherpaRecognizer>()

    /** 取り込んだモデル（models/custom/<id>/ に、ファイルと meta.json） */
    fun custom(context: Context): List<VoiceModelSpec> =
        customRoot(context).listFiles()?.filter { File(it, "meta.json").exists() }?.sortedBy { it.lastModified() }?.mapNotNull { dir ->
            runCatching {
                val meta = JSONObject(File(dir, "meta.json").readText())
                val type = CustomModelType.valueOf(meta.getString("type"))
                customSpec(dir, meta.getString("name"), type)
            }.getOrNull()
        }.orEmpty()

    private fun customSpec(dir: File, name: String, type: CustomModelType): VoiceModelSpec {
        val id = "custom-${dir.name}"
        val bytes = dir.listFiles()?.filter { it.name != "meta.json" }?.sumOf { it.length() } ?: 0L
        val size = if (bytes >= 1_000_000) "${Math.round(bytes / 1e6)}MB" else "${(bytes + 999) / 1000}KB"
        val tags = listOf(VoiceTag(VoiceTag.Kind.FORMAT, type.label.substringBefore('（')), VoiceTag(R, "取り込み"), VoiceTag(Z, size))
        if (type == CustomModelType.WHISPER_CPP) {
            val file = File(dir, "model.bin")
            return VoiceModelSpec(
                id, name, tags, null, custom = true,
                ready = { file.exists() },
                download = null,
                run = { c, pcm, threads, cb -> VoiceInput.transcribe(c, pcm, file, threads) { text, _ -> cb(text) } },
                remove = { dir.deleteRecursively() },
            )
        }
        val recognizer = synchronized(customSherpa) {
            customSherpa.getOrPut(id) {
                val files = dir.listFiles()!!.filter { it.name != "meta.json" }.associate { it.name to it.length() }
                SherpaRecognizer(name, "custom/${dir.name}", "", files) { d, threads ->
                    when (type) {
                        CustomModelType.TRANSDUCER -> OfflineModelConfig(
                            transducer = OfflineTransducerModelConfig(
                                encoder = File(d, "encoder.onnx").path,
                                decoder = File(d, "decoder.onnx").path,
                                joiner = File(d, "joiner.onnx").path,
                            ),
                            tokens = File(d, "tokens.txt").path, numThreads = threads, modelType = "transducer",
                        )
                        CustomModelType.NEMO_CTC -> OfflineModelConfig(
                            nemo = OfflineNemoEncDecCtcModelConfig(model = File(d, "model.onnx").path),
                            tokens = File(d, "tokens.txt").path, numThreads = threads,
                        )
                        else -> OfflineModelConfig(
                            senseVoice = OfflineSenseVoiceModelConfig(model = File(d, "model.onnx").path, language = "", useInverseTextNormalization = true),
                            tokens = File(d, "tokens.txt").path, numThreads = threads,
                        )
                    }
                }
            }
        }
        return VoiceModelSpec(
            id, name, tags, null, custom = true,
            ready = { recognizer.isReady(it) },
            download = null,
            run = { c, pcm, threads, cb -> recognizer.recognize(c, pcm, threads, cb) },
            remove = {
                synchronized(customSherpa) { customSherpa.remove(id) }
                dir.deleteRecursively()
            },
        )
    }

    fun all(context: Context) = builtIn + custom(context)

    /**
     * 確定の文を作るモデル。設定で選んだ（適用した）ものが使えなければ、使えるほかのもの（おすすめの順）。どれもなければ null
     */
    fun finalModel(context: Context, settings: KanadeSettings): VoiceModelSpec? {
        val all = all(context)
        return all.firstOrNull { it.id == settings.voiceFinal && it.isReady(context) } ?: all.firstOrNull { it.isReady(context) }
    }

    /** 選んだファイルの役割（ファイル名から見分ける）。分からなければ null */
    private fun role(name: String): String? {
        val n = name.lowercase()
        return when {
            "tokens" in n && n.endsWith(".txt") -> "tokens.txt"
            "encoder" in n && n.endsWith(".onnx") -> "encoder.onnx"
            "decoder" in n && n.endsWith(".onnx") -> "decoder.onnx"
            "joiner" in n && n.endsWith(".onnx") -> "joiner.onnx"
            n.endsWith(".onnx") -> "model.onnx"
            n.endsWith(".bin") -> "model.bin"
            else -> null
        }
    }

    fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** 選んだファイルから、形式を見当つける（.bin なら whisper.cpp、encoder があればトランスデューサ、ほかは NeMo CTC） */
    fun guessType(names: List<String>): CustomModelType {
        val roles = names.mapNotNull { role(it) }.toSet()
        return when {
            "model.bin" in roles -> CustomModelType.WHISPER_CPP
            "encoder.onnx" in roles -> CustomModelType.TRANSDUCER
            names.any { "sense" in it.lowercase() } -> CustomModelType.SENSEVOICE
            else -> CustomModelType.NEMO_CTC
        }
    }

    /** 形式に要るファイルがそろっているか。足りなければ、足りないものを返す */
    fun missing(type: CustomModelType, names: List<String>): List<String> {
        val roles = names.mapNotNull { role(it) }.toSet()
        val need = when (type) {
            CustomModelType.WHISPER_CPP -> listOf("model.bin")
            CustomModelType.TRANSDUCER -> listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
            else -> listOf("model.onnx", "tokens.txt")
        }
        return need.filter { it !in roles }
    }

    /**
     * 選んだファイルをアプリの中へ写して、一覧に足す。UI スレッドから呼ばないこと。足したモデルの id を返す
     */
    fun importCustom(context: Context, uris: List<Uri>, name: String, type: CustomModelType): String {
        val dir = File(customRoot(context), System.currentTimeMillis().toString(36)).apply { mkdirs() }
        try {
            for (uri in uris) {
                val target = role(displayName(context, uri) ?: continue) ?: continue
                val tmp = File(dir, "$target.part")
                context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 20) } }
                tmp.renameTo(File(dir, target))
            }
            File(dir, "meta.json").writeText(JSONObject().put("name", name).put("type", type.name).toString())
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        return "custom-${dir.name}"
    }
}
