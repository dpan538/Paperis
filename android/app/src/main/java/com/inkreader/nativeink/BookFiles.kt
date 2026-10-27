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

internal data class BookFile(val id: String, val name: String, val title: String, val format: String, val folder: String,val chapters:Int=0,val structured:Boolean=false,val bundle:String="")

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
                if (id.matches(Regex("[a-f0-9]{64}")) && (File(directory,"$id.txt").isFile||File(directory,o.optString("bundle","$id-v2")+"/text.txt").isFile))
                    entries += BookFile(id, o.getString("name"), o.getString("title"), o.getString("format"), o.optString("folder"),o.optInt("chapters"),o.optBoolean("structured"),o.optString("bundle"))
            }
        }
    }
    val demo = BookFile("sample", "山窗随记.txt", "山窗随记 · 试读", "TXT", "")
    @Synchronized fun list(): List<BookFile> = entries.toList() + demo
    @Synchronized fun get(id: String) = list().find { it.id == id }
    private fun bundle(entry:BookFile)=File(directory,entry.bundle.takeIf {it.matches(Regex("[a-f0-9]{64}-v2-[a-z0-9-]{36}"))}?:"${entry.id}-v2")
    fun text(entry: BookFile): String = if (entry.id == "sample") context.assets.open("sample.txt").bufferedReader().use { it.readText().trim() }
        else (File(bundle(entry),"text.txt").takeIf {it.isFile}?:File(directory,"${entry.id}.txt")).readText()
    fun structure(entry:BookFile,text:String)=if(entry.id=="sample")BookStructure.infer(text) else runCatching {BookStructure.read((File(bundle(entry),"structure.json").takeIf {it.isFile}?:File(directory,"${entry.id}.json")).readText(),text)}.getOrElse {BookStructure.infer(text)}
    private val bitmaps=object:LinkedHashMap<String,android.graphics.Bitmap>(4,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,android.graphics.Bitmap>?)=size>4
    }
    fun imageSize(entry:BookFile,key:String):Pair<Int,Int>? {
        if(!key.matches(Regex("[a-f0-9]{64}")))return null
        val options=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
        android.graphics.BitmapFactory.decodeFile(File(bundle(entry),"images/$key").path,options)
        return if(options.outWidth in 1..20000&&options.outHeight in 1..20000)options.outWidth to options.outHeight else null
    }
    fun image(entry:BookFile,key:String):android.graphics.Bitmap? {
        if(!key.matches(Regex("[a-f0-9]{64}")))return null
        val id="${entry.id}/$key"
        return bitmaps[id]?:runCatching {
            val file=File(bundle(entry),"images/$key")
            val info=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeFile(file.path,info)
            require(info.outWidth in 1..20000 && info.outHeight in 1..20000)
            val options=android.graphics.BitmapFactory.Options().apply {inSampleSize=1;while(maxOf(info.outWidth,info.outHeight)/inSampleSize>1600)inSampleSize*=2}
            val source=checkNotNull(android.graphics.BitmapFactory.decodeFile(file.path,options))
            val gray=android.graphics.Bitmap.createBitmap(source.width,source.height,android.graphics.Bitmap.Config.ARGB_8888)
            val paint=android.graphics.Paint().apply {colorFilter=android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply {setSaturation(0f)})}
            android.graphics.Canvas(gray).apply {drawColor(android.graphics.Color.WHITE);drawBitmap(source,0f,0f,paint)};source.recycle()
            bitmaps[id]=gray;gray
        }.getOrNull()
    }
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
    @Synchronized fun import(uri:Uri,folder:String="",providedName:String?=null,rebuild:Boolean=false,progress:(Int,Int)->Unit={_,_->}):Boolean {
        val name=providedName?:displayName(uri)
        val temp = File.createTempFile("import-", ".data", context.cacheDir)
        try {
            copy(uri, temp, BookImporter.MAX_BYTES)
            val hash = MessageDigest.getInstance("SHA-256").let { digest ->
                temp.inputStream().use { input -> val b = ByteArray(32768); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
            val existing=entries.firstOrNull {it.id==hash}
            if(!rebuild&&existing?.structured==true&&File(bundle(existing),"structure.json").isFile)return false
            val parsed = BookImporter.parse(temp,name,progress)
            val stage=java.nio.file.Files.createTempDirectory(directory.toPath(),".$hash-stage-").toFile()
            val target=File(directory,"$hash-v2-${java.util.UUID.randomUUID()}")
            var committed=false
            val oldText=existing?.let {text(it)}
            try {
                File(stage,"text.txt").writeText(parsed.text)
                File(stage,"structure.json").writeText(parsed.structure.json().toString())
                temp.copyTo(File(stage,"original"))
                if(parsed.images.isNotEmpty()) {
                    val images=File(stage,"images").also {it.mkdirs()}
                    parsed.images.forEach {(key,data)->File(images,key).writeBytes(data)}
                }
                try {
                    check(stage.renameTo(target)) {"无法保存本地书籍"}
                    val entry=BookFile(hash,name,parsed.title,parsed.format,existing?.folder?:folder,parsed.structure.chapters.size,true,target.name)
                    val oldIndex=entries.indexOfFirst {it.id==hash}
                    if(oldIndex>=0)entries[oldIndex]=entry else entries+=entry
                    try {writeIndex();committed=true} catch(t:Throwable) {if(oldIndex>=0)entries[oldIndex]=checkNotNull(existing) else entries.remove(entry);throw t}
                    if(oldText!=null&&oldText!=parsed.text) {
                        val saved=reading(entry);val previous=saved.getInt("offset",0).coerceIn(0,oldText.lastIndex.coerceAtLeast(0))
                        val needle=oldText.substring(previous,minOf(previous+120,oldText.length)).filterNot {it.isWhitespace()}.take(48)
                        val normalized=StringBuilder();val positions=IntArray(parsed.text.length);var count=0
                        parsed.text.forEachIndexed {at,c->if(!c.isWhitespace()){positions[count++]=at;normalized.append(c)}}
                        val match=if(needle.length>=12)normalized.indexOf(needle) else -1
                        val offset=if(match>=0)positions[match] else (previous.toLong()*parsed.text.length/oldText.length.coerceAtLeast(1)).toInt()
                        val bookmarks=saved.getStringSet("bookmarks",emptySet()).orEmpty().mapNotNull {it.toIntOrNull()}.mapNotNull {oldOffset->
                            val at=oldOffset.coerceIn(0,oldText.lastIndex);val sample=oldText.substring(at,minOf(at+120,oldText.length)).filterNot {it.isWhitespace()}.take(48)
                            val found=if(sample.length>=12)normalized.indexOf(sample) else -1
                            if(found>=0)positions[found].toString() else (at.toLong()*parsed.text.length/oldText.length.coerceAtLeast(1)).toInt().coerceIn(0,parsed.text.lastIndex).toString()
                        }.toSet()
                        saved.edit().putInt("offset",offset.coerceIn(0,parsed.text.lastIndex)).putStringSet("bookmarks",bookmarks).apply()
                    }
                    existing?.let {bundle(it)}?.takeIf {it!=target}?.deleteRecursively()
                } catch(t:Throwable) {if(!committed)target.deleteRecursively();throw t}
            } finally {stage.deleteRecursively()}
            return true
        } finally { temp.delete() }
    }
    private fun writeIndex() {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("title", it.title).put("format", it.format).put("folder", it.folder).put("chapters",it.chapters).put("structured",it.structured).put("bundle",it.bundle)) }
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
