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
    private var cachedBitmapBytes=0L
    private val bitmapCacheLimit=40L*1024*1024
    private val bitmaps=object:LinkedHashMap<String,android.graphics.Bitmap>(4,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,android.graphics.Bitmap>?)=false
    }
    init { reload() }
    @Synchronized fun reload() {
        entries.clear();bitmaps.clear();cachedBitmapBytes=0
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
    internal fun bundle(entry:BookFile)=File(directory,entry.bundle.takeIf {it.matches(Regex("[a-f0-9]{64}-v2-[a-z0-9-]{36}"))}?:"${entry.id}-v2")
    fun text(entry: BookFile): String = if (entry.id == "sample") context.assets.open("sample.txt").bufferedReader().use { it.readText().trim() }
        else (File(bundle(entry),"text.txt").takeIf {it.isFile}?:File(directory,"${entry.id}.txt")).readText()
    fun structure(entry:BookFile,text:String)=if(entry.id=="sample")BookStructure.infer(text) else runCatching {BookStructure.read((File(bundle(entry),"structure.json").takeIf {it.isFile}?:File(directory,"${entry.id}.json")).readText(),text)}.getOrElse {BookStructure.infer(text)}
    fun imageSize(entry:BookFile,key:String):Pair<Int,Int>? {
        if(!key.matches(Regex("[a-f0-9]{64}")))return null
        val options=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
        android.graphics.BitmapFactory.decodeFile(File(bundle(entry),"images/$key").path,options)
        return if(options.outWidth in 1..20000&&options.outHeight in 1..20000)options.outWidth to options.outHeight else null
    }
    @Synchronized fun image(entry:BookFile,key:String):android.graphics.Bitmap? {
        if(!key.matches(Regex("[a-f0-9]{64}")))return null
        val id="${entry.id}/$key"
        return bitmaps[id]?:runCatching {
            val file=File(bundle(entry),"images/$key")
            val info=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeFile(file.path,info)
            require(info.outWidth in 1..20000 && info.outHeight in 1..20000)
            val options=android.graphics.BitmapFactory.Options().apply {inSampleSize=1;while(maxOf(info.outWidth,info.outHeight)/inSampleSize>2200)inSampleSize*=2}
            val source=checkNotNull(android.graphics.BitmapFactory.decodeFile(file.path,options))
            val gray=try {InkImage.grayscale16(source)} finally {source.recycle()}
            if(gray.allocationByteCount<=bitmapCacheLimit) {
                bitmaps[id]=gray;cachedBitmapBytes+=gray.allocationByteCount
                val iterator=bitmaps.entries.iterator()
                while(cachedBitmapBytes>bitmapCacheLimit&&iterator.hasNext()) {
                    val eldest=iterator.next();iterator.remove();cachedBitmapBytes-=eldest.value.allocationByteCount
                }
            }
            gray
        }.getOrNull()
    }
    fun reading(entry: BookFile) = context.getSharedPreferences(if (entry.id == "sample") "reading" else "book_${entry.id}", Context.MODE_PRIVATE)
    fun displayName(uri: Uri): String {
        return runCatching {context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }}.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "未命名"
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
    fun hasOriginal(entry:BookFile)=File(bundle(entry),"original").isFile
    fun original(entry:BookFile)=File(bundle(entry),"original").takeIf {it.isFile}
    fun needsRepair(entry:BookFile)=entry.id!="sample" && runCatching {JSONObject(File(bundle(entry),"structure.json").readText()).optInt("revision")<BookStructure.REVISION}.getOrDefault(true)
    @Synchronized fun attachOriginal(id:String,uri:Uri) {
        val entry=checkNotNull(get(id));val job=jobDirectory("attach-$id");val source=File(job,"original")
        try {
            copy(uri,source,BookImporter.MAX_BYTES)
            require(hashFile(source)==id) {"所选文件与这本旧书不一致，请选择原来的同一文件"}
            val parsed=BookImporter.parse(source,entry.name,context=context,workspace=File(job,"assets"))
            commit(id,entry.name,entry.folder,parsed,source,entry)
        } finally {job.deleteRecursively()}
    }
    internal fun jobDirectory(key:String)=File(context.filesDir,"import-jobs/$key").also {it.mkdirs()}
    /** The original is staged on durable storage, not the evictable cache. */
    @Synchronized fun import(uri:Uri,folder:String="",providedName:String?=null,rebuild:Boolean=false,progress:(Int,Int)->Unit={_,_->}):Boolean {
        val name=providedName?:displayName(uri)
        val job=jobDirectory(hashBytes((uri.toString()+name).toByteArray()))
        val source=File(job,"source")
        if(!source.isFile) {
            val partial=File(job,"source.partial");try {copy(uri,partial,BookImporter.MAX_BYTES)}catch(t:Throwable){partial.delete();throw t}
            check(partial.renameTo(source)) {"无法保存原文件"}
        }
        try {
            val hash=hashFile(source);val existing=entries.firstOrNull {it.id==hash}
            if(!rebuild&&existing?.structured==true&&!needsRepair(existing)&&File(bundle(existing),"structure.json").isFile){job.deleteRecursively();return false}
            val parsed=BookImporter.parse(source,name,progress,context,File(job,"assets"))
            check(!Thread.currentThread().isInterrupted) {"已暂停，进度已保留"}
            commit(hash,name,existing?.folder?:folder,parsed,source,existing)
            job.deleteRecursively();return true
        } catch(t:Throwable) {throw t} // Keep the durable original/checkpoints for an explicit retry.
    }
    @Synchronized fun reprocess(id:String,forceOcr:Boolean=false,progress:(Int,Int)->Unit={_,_->}):Boolean {
        val entry=checkNotNull(get(id));require(id!="sample") {"试读书无需重新排版"}
        val raw=original(entry);val job=jobDirectory("reflow-$id-${if(forceOcr)"ocr" else "auto"}")
        val parsed=if(raw!=null)BookImporter.parse(raw,entry.name,progress,context,File(job,"assets"),forceOcr)
            else legacyReflow(text(entry),entry.title,entry.format)
        check(!Thread.currentThread().isInterrupted) {"已暂停，进度已保留"}
        commit(id,entry.name,entry.folder,parsed,raw,entry)
        job.deleteRecursively();return raw!=null
    }
    private fun legacyReflow(value:String,title:String,format:String):ParsedBook {
        val out=BookAssembly()
        val pieces=LegacyBookRepair.pieces(value)
        pieces.forEachIndexed {index,piece->
            val line=piece.text
            val raw=line.trim();val t=if(TextSemantics.metadata(raw))TextSemantics.canonicalFields(raw)else raw;val known=ChapterScanner.level(t)
            val standalone=piece.hardBreakBefore&&pieces.getOrNull(index+1)?.hardBreakBefore==true
            val level=if(known>0)known else if(t in setOf("版权信息","版權資訊","Copyright Information"))2 else if(standalone&&TextSemantics.englishTitle(t)&&(pieces.getOrNull(index+1)?.text?.length?:0)>=40)2 else 0
            val metadata=TextSemantics.metadata(t)
            if(t.matches(Regex("[一二三四五六七八九十百零〇0-9IVXLCDMivxlcdm]{1,8}"))) {out.add(SourceBlock(t,"subtitle"));return@forEachIndexed}
            val prev=out.blocks.lastOrNull()
            if(!piece.hardBreakBefore&&level==0&&!metadata&&prev?.kind=="body") {
                if(format=="PDF"&&LegacyBookRepair.probablePdfWrap(pieces.getOrNull(index-1)?.text.orEmpty(),t)) {prev.text=BookImporter.joinProse(prev.text,t);out.repairedBreaks++}
                else prev.text+="\n"+t
            } else out.add(SourceBlock(t,if(level>0||t==title)"heading" else if(metadata)"metadata" else "body",if(t==title)1 else level,origin="legacy:textOffset=${piece.sourceOffset}"))
        }
        return out.finish(title,format)
    }
    internal fun commit(id:String,name:String,folder:String,parsed:ParsedBook,raw:File?,existing:BookFile?=get(id)):BookFile {
        val stage=java.nio.file.Files.createTempDirectory(directory.toPath(),".$id-stage-").toFile()
        val target=File(directory,"$id-v2-${java.util.UUID.randomUUID()}");var committed=false
        val oldText=existing?.let {text(it)}
        try {
            File(stage,"text.txt").writeText(parsed.text)
            File(stage,"structure.json").writeText(parsed.structure.json().toString())
            raw?.copyTo(File(stage,"original"))
            parsed.images.forEach {(key,file)-> require(key.matches(Regex("[a-f0-9]{64}")));val dest=File(stage,"images/$key");dest.parentFile?.mkdirs();file.copyTo(dest)}
            check(stage.renameTo(target)) {"无法保存本地书籍"}
            val entry=BookFile(id,name,parsed.title,parsed.format,folder,parsed.structure.chapters.size,true,target.name)
            val at=entries.indexOfFirst {it.id==id};if(at>=0)entries[at]=entry else entries+=entry
            try {writeIndex();committed=true} catch(t:Throwable) {if(at>=0)entries[at]=checkNotNull(existing) else entries.remove(entry);throw t}
            if(oldText!=null&&oldText!=parsed.text)remap(entry,oldText,parsed.text)
            bitmaps.clear();cachedBitmapBytes=0
            existing?.let {bundle(it)}?.takeIf {it!=target}?.deleteRecursively()
            return entry
        } finally {stage.deleteRecursively();if(!committed)target.deleteRecursively()}
    }
    private fun remap(entry:BookFile,old:String,new:String) {
        val saved=reading(entry);val normalized=StringBuilder();val positions=IntArray(new.length);var n=0
        new.forEachIndexed {at,c->if(!c.isWhitespace()){positions[n++]=at;normalized.append(c)}}
        fun map(value:Int):Int {
            val at=value.coerceIn(0,old.lastIndex.coerceAtLeast(0));val needle=old.substring(at,minOf(at+120,old.length)).filterNot {it.isWhitespace()}.take(48)
            val match=if(needle.length>=12)normalized.indexOf(needle) else -1
            return (if(match>=0)positions[match] else (at.toLong()*new.length/old.length.coerceAtLeast(1)).toInt()).coerceIn(0,new.lastIndex.coerceAtLeast(0))
        }
        saved.edit().putInt("offset",map(saved.getInt("offset",0))).putStringSet("bookmarks",saved.getStringSet("bookmarks",emptySet()).orEmpty().mapNotNull {it.toIntOrNull()}.map {map(it).toString()}.toSet()).commit()
    }
    companion object {
        fun hashBytes(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
        fun hashFile(file:File):String {
            val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use {input->val b=ByteArray(32768);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
            return digest.digest().joinToString("") {"%02x".format(it)}
        }
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
