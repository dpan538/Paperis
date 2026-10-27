package com.inkreader.nativeink

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Independent, verified snapshots in the user's persisted SAF tree. No app-cache dependency. */
internal class LibraryBackup(private val context:Context,private val books:BookFiles) {
    val preferences=context.getSharedPreferences("library-backup",0)
    val tree:Uri? get()=preferences.getString("tree",null)?.let(Uri::parse)
    val label:String get()=preferences.getString("label","").orEmpty()
    fun choose(uri:Uri) {
        context.contentResolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val root=Docs.buildDocumentUriUsingTree(uri,Docs.getTreeDocumentId(uri))
        val name=books.displayName(root)
        preferences.edit().putString("tree",uri.toString()).putString("label",name).commit()
    }
    private fun root()=checkNotNull(tree) {"请先选择备份文件夹"}.let {Docs.buildDocumentUriUsingTree(it,Docs.getTreeDocumentId(it))}
    fun backupAll(progress:(Int,Int)->Unit={_,_->}):Int {
        val list=books.list().filter {it.id!="sample"};var count=0
        for((i,b) in list.withIndex()){progress(i+1,list.size);backup(b);count++}
        preferences.edit().putLong("lastSuccess",System.currentTimeMillis()).remove("error").commit();return count
    }
    fun backup(book:BookFile):Uri {
        val stage=books.jobDirectory("backup-${book.id}");val archive=File(stage,"book.paperis")
        try {
            write(book,archive)
            // Use an unpublished extension until a complete read-back has verified the provider's copy.
            val uri=checkNotNull(Docs.createDocument(context.contentResolver,root(),"application/octet-stream","Paperis-${book.id.take(12)}-${System.currentTimeMillis()}.partial"))
            try {
                context.contentResolver.openOutputStream(uri,"wt")!!.use {out->archive.inputStream().use {it.copyTo(out)}}
                val digest=java.security.MessageDigest.getInstance("SHA-256")
                context.contentResolver.openInputStream(uri)!!.use {input->val b=ByteArray(32768);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
                check(digest.digest().joinToString("") {"%02x".format(it)}==BookFiles.hashFile(archive)) {"备份写入校验失败"}
                val renamed=Docs.renameDocument(context.contentResolver,uri,"Paperis-${book.id.take(12)}-${System.currentTimeMillis()}.paperis")
                checkNotNull(renamed) {"备份文件夹不支持完成保存，请换一个本地文件夹"}
                return renamed
            } catch(t:Throwable) {runCatching {Docs.deleteDocument(context.contentResolver,uri)};throw t}
        } finally {stage.deleteRecursively()}
    }
    internal fun write(book:BookFile,archive:File) {
        val text=books.text(book);val structure=books.structure(book,text)
        val hashes=JSONObject();val metadata=JSONObject().put("schema",1).put("id",book.id).put("name",book.name).put("title",book.title).put("format",book.format).put("folder",book.folder)
        val saved=books.reading(book);val state=JSONObject()
        for(key in listOf("offset","font","spacing","columns"))state.put(key,saved.getInt(key,if(key=="spacing")1 else 0))
        state.put("bookmarks",JSONArray(saved.getStringSet("bookmarks",emptySet()).orEmpty().toList()))
        ZipOutputStream(archive.outputStream().buffered()).use {zip->
            fun bytes(name:String,value:ByteArray){zip.putNextEntry(ZipEntry(name));zip.write(value);zip.closeEntry();hashes.put(name,BookFiles.hashBytes(value))}
            fun file(name:String,value:File){zip.putNextEntry(ZipEntry(name));value.inputStream().use {it.copyTo(zip)};zip.closeEntry();hashes.put(name,BookFiles.hashFile(value))}
            bytes("text.txt",text.toByteArray());bytes("structure.json",structure.json().toString().toByteArray());bytes("reading.json",state.toString().toByteArray())
            books.original(book)?.let {file("original",it)}
            File(books.bundle(book),"images").listFiles()?.filter {it.isFile&&it.name.matches(Regex("[a-f0-9]{64}"))}?.forEach {file("images/${it.name}",it)}
            metadata.put("files",hashes);zip.putNextEntry(ZipEntry("book.json"));zip.write(metadata.toString().toByteArray());zip.closeEntry()
        }
    }
    fun restoreAll(progress:(Int,Int)->Unit={_,_->}):Int {
        val selected=checkNotNull(tree) {"请先选择备份文件夹"};val children=Docs.buildChildDocumentsUriUsingTree(selected,Docs.getTreeDocumentId(selected))
        val documents=mutableListOf<Pair<String,Uri>>()
        context.contentResolver.query(children,arrayOf(Docs.Document.COLUMN_DOCUMENT_ID,Docs.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use {cursor->
            while(cursor.moveToNext())if(cursor.getString(1).endsWith(".paperis"))documents+=cursor.getString(1) to Docs.buildDocumentUriUsingTree(selected,cursor.getString(0))
        }
        require(documents.isNotEmpty()) {"文件夹内没有 Paperis 备份"}
        // Newest snapshot for each book wins. Old snapshots remain recoverable.
        val restored=mutableSetOf<String>()
        for((i,pair) in documents.sortedByDescending {it.first}.withIndex()) {
            progress(i+1,documents.size)
            restore(pair.second,restored)?.let {restored+=it}
        };return restored.size
    }
    internal fun restore(uri:Uri,skip:Set<String> = emptySet()):String? {
        val stage=books.jobDirectory("restore-${java.util.UUID.randomUUID()}");var total=0L;var count=0
        try {
            ZipInputStream(checkNotNull(context.contentResolver.openInputStream(uri)).buffered()).use {zip->
                while(true) {
                    val entry=zip.nextEntry?:break;val name=entry.name
                    require(++count<=5000&&(name in setOf("book.json","text.txt","structure.json","reading.json","original")||name.matches(Regex("images/[a-f0-9]{64}")))) {"备份条目无效"}
                    val target=File(stage,name);require(!entry.isDirectory&&!target.exists()) {"备份条目重复"};target.parentFile?.mkdirs()
                    var size=0L;val limit=if(name=="original")BookImporter.MAX_BYTES.toLong() else if(name.startsWith("images/"))32L*1024*1024 else 16L*1024*1024
                    target.outputStream().use {out->val b=ByteArray(32768);while(true){val n=zip.read(b);if(n<0)break;size+=n;total+=n;require(size<=limit&&total<=384L*1024*1024) {"备份内容过大"};out.write(b,0,n)}}
                    zip.closeEntry()
                }
            }
            val meta=JSONObject(File(stage,"book.json").readText());require(meta.getInt("schema")==1) {"备份版本不支持"}
            val id=meta.getString("id");require(id.matches(Regex("[a-f0-9]{64}"))) {"备份书籍标识无效"};if(id in skip)return null
            val hashes=meta.getJSONObject("files");val files=stage.walkTopDown().filter {it.isFile&&it.name!="book.json"}.toList()
            require(files.size==hashes.length()&&hashes.has("text.txt")&&hashes.has("structure.json")&&hashes.has("reading.json")) {"备份不完整"}
            files.forEach {val path=it.relativeTo(stage).invariantSeparatorsPath;require(hashes.optString(path)==BookFiles.hashFile(it)) {"备份文件校验失败"}}
            val original=File(stage,"original").takeIf {it.isFile};require(original==null||BookFiles.hashFile(original)==id) {"原文件校验失败"}
            val text=File(stage,"text.txt").readText();require(text.length<=BookImporter.MAX_TEXT&&text.isNotBlank())
            val structure=BookStructure.read(File(stage,"structure.json").readText(),text)
            val images=File(stage,"images").listFiles().orEmpty().associateBy {it.name}
            require(structure.blocks.filter {it.image.isNotEmpty()}.all {images.containsKey(it.image)}) {"备份插图缺失"}
            val state=JSONObject(File(stage,"reading.json").readText())
            books.commit(id,meta.getString("name"),meta.optString("folder"),ParsedBook(meta.getString("title"),text,meta.getString("format"),structure,images),original)
            val editor=books.reading(checkNotNull(books.get(id))).edit()
            for(key in listOf("offset","font","spacing","columns"))editor.putInt(key,state.optInt(key).coerceAtLeast(0))
            val marks=state.optJSONArray("bookmarks")?:JSONArray();editor.putStringSet("bookmarks",(0 until marks.length()).mapNotNull {marks.optString(it).toIntOrNull()?.takeIf {at->at in text.indices}?.toString()}.toSet()).commit()
            return id
        } finally {stage.deleteRecursively()}
    }
}
