package dev.mooma.kanade

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * クリップボードの履歴。コピーされた文字と画像を、アプリの中（files/clipboard/）に残す。
 * ピン留めしたものは消えず、それ以外は新しい [MAX_RECENT] 件だけ残す。パスワードなど「機密」の印が付いたコピーは残さない。
 * IME（今使っている入力方法）はコピーを読めるので、コピーされるたびに [capture] する。
 */
class ClipboardHistory(private val context: Context) {
    data class Item(
        val id: String,
        /** 文字（画像なら null） */
        val text: String?,
        /** 画像のファイル名（files/clipboard/ の中。文字なら null） */
        val image: String?,
        val mime: String?,
        val pinned: Boolean,
        val time: Long,
    )

    private val dir = File(context.filesDir, "clipboard").apply { mkdirs() }
    private val index = File(dir, "index.json")

    @Synchronized
    fun all(): List<Item> = read().sortedWith(compareByDescending<Item> { it.pinned }.thenByDescending { it.time })

    @Synchronized
    fun addText(text: String) {
        if (text.isBlank()) return
        val items = read().filter { it.text != text || it.pinned }.toMutableList()
        // ピン留めした同じ文字があれば、新しく足さない
        if (items.any { it.text == text }) return
        items += Item(newId(), text, null, null, false, System.currentTimeMillis())
        write(trim(items))
    }

    @Synchronized
    fun addImage(bitmapOrBytes: ByteArray, mime: String): Item {
        val id = newId()
        val ext = when (mime) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val name = "$id.$ext"
        File(dir, name).writeBytes(bitmapOrBytes)
        val item = Item(id, null, name, mime, false, System.currentTimeMillis())
        write(trim(read() + item))
        return item
    }

    /** 切り抜いた画像を、新しい項目として足す */
    fun addBitmap(bitmap: Bitmap): Item {
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return addImage(out.toByteArray(), "image/png")
    }

    @Synchronized
    fun setPinned(id: String, pinned: Boolean) = write(read().map { if (it.id == id) it.copy(pinned = pinned) else it })

    @Synchronized
    fun delete(id: String) {
        val items = read()
        items.firstOrNull { it.id == id }?.image?.let { File(dir, it).delete() }
        write(items.filter { it.id != id })
    }

    /** ピン留めしていないものをすべて消す */
    @Synchronized
    fun clearRecent() {
        val items = read()
        items.filter { !it.pinned }.forEach { i -> i.image?.let { File(dir, it).delete() } }
        write(items.filter { it.pinned })
    }

    fun imageFile(item: Item): File? = item.image?.let { File(dir, it) }

    /** 画像をほかのアプリに渡すための URI（[ClipboardImageProvider]） */
    fun imageUri(item: Item): Uri? = item.image?.let { Uri.parse("content://${ClipboardImageProvider.authority(context)}/$it") }

    /**
     * いまのコピーを履歴に足す（コピーされたときに呼ぶ）。機密の印のあるもの・画像を読めないものは足さない
     */
    fun capture(cm: ClipboardManager) {
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return
        val desc = clip.description
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        ) return
        if (clip.itemCount == 0) return
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null) {
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: desc.getMimeType(0)
            if (mime != null && mime.startsWith("image/")) {
                val bytes = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                if (bytes != null && bytes.size < MAX_IMAGE_BYTES) addImage(bytes, mime)
                return
            }
        }
        val text = item.text?.toString() ?: runCatching { item.coerceToText(context)?.toString() }.getOrNull()
        if (text != null) addText(text)
    }

    private fun trim(items: List<Item>): List<Item> {
        val recent = items.filter { !it.pinned }.sortedByDescending { it.time }
        val drop = recent.drop(MAX_RECENT)
        drop.forEach { i -> i.image?.let { File(dir, it).delete() } }
        val dropIds = drop.map { it.id }.toSet()
        return items.filter { it.id !in dropIds }
    }

    private fun newId() = System.currentTimeMillis().toString(36) + (0..999).random().toString(36)

    private fun read(): List<Item> {
        if (!index.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Item(
                    o.getString("id"), o.optString("text").takeIf { o.has("text") }, o.optString("image").takeIf { o.has("image") },
                    o.optString("mime").takeIf { o.has("mime") }, o.optBoolean("pinned"), o.optLong("time"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(items: List<Item>) {
        val arr = JSONArray()
        for (i in items) {
            arr.put(JSONObject().apply {
                put("id", i.id)
                i.text?.let { put("text", it) }
                i.image?.let { put("image", it) }
                i.mime?.let { put("mime", it) }
                put("pinned", i.pinned)
                put("time", i.time)
            })
        }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(index)
    }

    companion object {
        const val MAX_RECENT = 30
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
    }
}

/**
 * クリップボードの履歴の画像を、貼り付け先のアプリに読ませるための入口（読むだけ。IME が貼り付けるときに権限を渡す）
 */
class ClipboardImageProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.startsWith(".")) return null
        return File(File(context!!.filesDir, "clipboard"), name).takeIf { it.exists() }
    }

    override fun getType(uri: Uri): String? = when (uri.lastPathSegment?.substringAfterLast('.')) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "jpg" -> "image/jpeg"
        else -> null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = file(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = file(uri) ?: return null
        return android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(f.name, f.length()))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        fun authority(context: Context) = "${context.packageName}.clipboard"
    }
}
