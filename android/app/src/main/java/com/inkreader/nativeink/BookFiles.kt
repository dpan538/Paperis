package com.inkreader.nativeink

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class BookFile(val id: String, val name: String, val title: String, val format: String, val folder: String)

/** Private imported copies; source documents are never changed or removed. */
internal class BookFiles(private val context: Context) {
    private val directory = File(context.filesDir, "books").also { it.mkdirs() }
    private val index = AtomicFile(File(directory, "index.json"))
    val preferences = context.getSharedPreferences("files", Context.MODE_PRIVATE)
    private val entries = mutableListOf<BookFile>()
    init {
        runCatching {
            val array = JSONArray(index.readFully().toString(Charsets.UTF_8))
            repeat(array.length()) {
                val o = array.getJSONObject(it); val id = o.getString("id")
                if (id.matches(Regex("[a-f0-9]{64}")) && File(directory, "$id.txt").isFile)
                    entries += BookFile(id, o.getString("name"), o.getString("title"), o.getString("format"), o.optString("folder"))
            }
        }
    }
    val demo = BookFile("sample", "山窗随记.txt", "山窗随记 · 试读", "TXT", "")
    @Synchronized fun list(): List<BookFile> = entries.toList() + demo
    @Synchronized fun get(id: String) = list().find { it.id == id }
    fun text(entry: BookFile): String = if (entry.id == "sample") context.assets.open("sample.txt").bufferedReader().use { it.readText().trim() }
        else File(directory, "${entry.id}.txt").readText()
    fun reading(entry: BookFile) = context.getSharedPreferences(if (entry.id == "sample") "reading" else "book_${entry.id}", Context.MODE_PRIVATE)
    fun displayName(uri: Uri): String {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "未命名"
    }
    fun copy(uri: Uri, destination: File, limit: Int) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(32768); var size = 0
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    size += count; require(size <= limit) { "文件超过 ${limit / 1024 / 1024} MB" }
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("无法读取所选文件")
    }
    /** Returns false for an exact duplicate; incomplete parsing never changes the index. */
    @Synchronized fun import(uri: Uri, folder: String = ""): Boolean {
        val name = displayName(uri)
        val temp = File.createTempFile("import-", ".data", context.cacheDir)
        try {
            copy(uri, temp, BookImporter.MAX_BYTES)
            val hash = MessageDigest.getInstance("SHA-256").let { digest ->
                temp.inputStream().use { input -> val b = ByteArray(32768); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            if (entries.any { it.id == hash }) return false
            val parsed = BookImporter.parse(temp, name)
            val textFile = AtomicFile(File(directory, "$hash.txt"))
            val stream = textFile.startWrite()
            try { stream.write(parsed.text.toByteArray()); textFile.finishWrite(stream) } catch (t: Throwable) { textFile.failWrite(stream); throw t }
            val entry = BookFile(hash, name, parsed.title, parsed.format, folder)
            entries += entry
            try { writeIndex() } catch (t: Throwable) { entries.remove(entry); File(directory, "$hash.txt").delete(); throw t }
            return true
        } finally { temp.delete() }
    }
    private fun writeIndex() {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("title", it.title).put("format", it.format).put("folder", it.folder)) }
        val stream = index.startWrite()
        try { stream.write(array.toString().toByteArray()); index.finishWrite(stream) } catch (t: Throwable) { index.failWrite(stream); throw t }
    }
    fun customTypeface(): Typeface? = File(context.filesDir, "reader-font").takeIf { it.isFile }?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
    fun importFont(uri: Uri) {
        val name = displayName(uri)
        require(name.substringAfterLast('.').lowercase() in setOf("ttf", "otf", "ttc")) { "请选择 TTF、OTF 或 TTC 字体" }
        val temp = File.createTempFile("font-", ".data", context.cacheDir)
        try {
            copy(uri, temp, 16 * 1024 * 1024)
            Typeface.createFromFile(temp) // Validate before replacing the previous font.
            val target = AtomicFile(File(context.filesDir, "reader-font")); val stream = target.startWrite()
            try { temp.inputStream().use { it.copyTo(stream) }; target.finishWrite(stream) } catch (t: Throwable) { target.failWrite(stream); throw t }
            preferences.edit().putString("fontName", name).apply()
        } finally { temp.delete() }
    }
}
