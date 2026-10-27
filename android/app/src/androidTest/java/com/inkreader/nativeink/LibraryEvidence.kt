package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Independent scanned/mixed/vector PDF, 50 MiB, reprocessing, restart and backup regression. */
internal class LibraryEvidence(private val host:Instrumentation) {
    private val checks=JSONArray()
    private fun verify(name:String,ok:Boolean){checks.put(JSONObject().put("check",name).put("passed",ok));host.sendStatus(0,Bundle().apply {putString("stream","${if(ok)"PASS" else "FAIL"} $name\n")});check(ok){name}}
    private fun <T> ui(block:()->T):T {val result=AtomicReference<Result<T>>();host.runOnMainSync {result.set(runCatching(block))};return result.get().getOrThrow()}
    fun run()=with(host) {
        var failure:String?=null
        val root=File(targetContext.filesDir,"library-evidence").also {it.deleteRecursively();it.mkdirs()}
        val ctx=object:ContextWrapper(targetContext) {
            override fun getFilesDir()=root
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("evidence12_$name",mode)
        }
        val out=File(targetContext.getExternalFilesDir(null),"library-verification").also {it.mkdirs()}
        val fixtures=File(root,"fixtures").also {it.mkdirs()}
        try {
            val scan=File(fixtures,"scan.pdf");val pdf=PdfDocument()
            for(pageNumber in 0..1) {
                val b=Bitmap.createBitmap(1200,1600,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(Color.WHITE)
                val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;typeface=Typeface.create("serif",Typeface.NORMAL);textSize=54f}
                val lines=if(pageNumber==0)listOf("第一章 阅读的清晨","这是一段清晰的中文正文。","阳光落在书页，阅读使人安静。","Paper and ink make reading calm.") else listOf("第二章 紙與墨","這是一段清晰的繁體中文。","書頁上的文字，讓閱讀回歸純粹。","Original pages keep every illustration.")
                lines.forEachIndexed {i,t->c.drawText(t,80f,160f+i*100,p)}
                p.style=Paint.Style.STROKE;p.strokeWidth=6f;c.drawCircle(600f,900f,160f,p);c.drawRect(340f,1150f,860f,1360f,p);c.drawLine(340f,1150f,860f,1360f,p)
                val page=pdf.startPage(PdfDocument.PageInfo.Builder(600,800,pageNumber+1).create());page.canvas.drawBitmap(b,null,Rect(0,0,600,800),null);pdf.finishPage(page);b.recycle()
            };scan.outputStream().use {pdf.writeTo(it)};pdf.close()
            PdfRenderer(ParcelFileDescriptor.open(scan,ParcelFileDescriptor.MODE_READ_ONLY)).use {doc->doc.openPage(0).use {p->verify("Independent raster PDF has no embedded text layer",p.textContents.all {it.text.isBlank()})}}
            ctx.getSharedPreferences("files",0).edit().putInt("ocrScript",0).commit()
            val books=BookFiles(ctx);val start=SystemClock.elapsedRealtime()
            verify("Raster-only PDF imports with bundled models while network is disabled",books.import(Uri.fromFile(scan),providedName="扫描测试.pdf"))
            val scanBook=books.list().first {it.name=="扫描测试.pdf"};val recognized=books.text(scanBook)
            File(out,"ocr-result.txt").writeText(recognized)
            verify("Simplified Chinese OCR matches independent expected sentences",recognized.contains("这是一段清晰的中文正文")&&recognized.contains("阅读使人安静"))
            verify("Traditional Chinese OCR retains Traditional glyphs",recognized.contains("繁體中文")&&recognized.contains("閱讀"))
            verify("English OCR retains words and spaces",recognized.contains("Paper and ink make reading calm.")&&recognized.contains("Original pages keep every illustration."))
            val structure=books.structure(scanBook,recognized)
            verify("OCR creates navigable chapters from real recognized headings",structure.chapters.any {it.title.startsWith("第一章")}&&structure.chapters.any {it.title.startsWith("第二章")})
            verify("Scanned PDF figures remain separate local grayscale illustrations",structure.blocks.count {it.kind=="image"}>=2&&structure.blocks.filter {it.kind=="image"}.all {books.imageSize(scanBook,it.image)!=null})
            verify("Source-page anchors survive structural persistence",structure.blocks.any {it.page==1}&&BookStructure.read(structure.json().toString(),recognized)==structure)
            verify("Original PDF is copied byte-for-byte outside cache",books.original(scanBook)?.let {BookFiles.hashFile(it)==BookFiles.hashFile(scan)}==true)
            val checkpointDir=File(root,"checkpoint-assets")
            BookImporter.parse(scan,"checkpoint.pdf",context=ctx,workspace=checkpointDir)
            val stamp=File(checkpointDir,"page-0.json").lastModified();SystemClock.sleep(20)
            BookImporter.parse(scan,"checkpoint.pdf",context=ctx,workspace=checkpointDir)
            verify("Restart reuses completed OCR page checkpoints without rewriting them",stamp==File(checkpointDir,"page-0.json").lastModified())
            val anchor=recognized.indexOf("Paper and ink");books.reading(scanBook).edit().putInt("offset",anchor).putStringSet("bookmarks",setOf(anchor.toString())).commit()
            verify("Manual reprocessing uses the saved original without a picker",books.reprocess(scanBook.id))
            val rebuilt=checkNotNull(books.get(scanBook.id));verify("Reprocessing retains reading position and bookmarks",books.reading(rebuilt).getInt("offset",-1)==anchor&&books.reading(rebuilt).getStringSet("bookmarks",emptySet())==setOf(anchor.toString()))
            val mixed=File(fixtures,"mixed.pdf");val doc=PdfDocument();val page=doc.startPage(PdfDocument.PageInfo.Builder(600,800,1).create());val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {textSize=24f;color=Color.BLACK}
            page.canvas.drawText("CHAPTER I",70f,80f,paint);paint.textSize=16f;page.canvas.drawText("Text and vector figures are both retained.",50f,130f,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=3f;page.canvas.drawCircle(300f,420f,110f,paint);page.canvas.drawLine(180f,580f,420f,680f,paint);page.canvas.drawLine(420f,580f,180f,680f,paint);doc.finishPage(page);mixed.outputStream().use {doc.writeTo(it)};doc.close()
            val parsed=BookImporter.parse(mixed,"mixed.pdf",context=ctx)
            verify("Text-layer PDF retains prose without OCR changes",parsed.text.contains("Text and vector figures are both retained."))
            verify("Native vector diagrams become inline images",parsed.structure.blocks.any {it.kind=="image"}&&parsed.images.values.all {it.isFile})
            val large=File(fixtures,"large.pdf")
            // A legal unreferenced stream exercises >50 MiB without allocating a giant bitmap/string.
            RandomAccessFile(large,"rw").use {f->
                val offsets=mutableListOf<Long>(0)
                fun write(v:String)=f.write(v.toByteArray(Charsets.US_ASCII))
                fun obj(n:Int,v:String){offsets+=f.filePointer;write("$n 0 obj\n$v\nendobj\n")}
                write("%PDF-1.4\n")
                obj(1,"<< /Type /Catalog /Pages 2 0 R >>")
                obj(2,"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
                obj(3,"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources << /Font << /F1 6 0 R >> >> /Contents 4 0 R >>")
                val content="BT /F1 20 Tf 50 700 Td (FIFTY_MIB_STREAM_IMPORT) Tj ET"
                obj(4,"<< /Length ${content.length} >>\nstream\n$content\nendstream")
                offsets+=f.filePointer;val padding=50*1024*1024;write("5 0 obj\n<< /Length $padding >>\nstream\n")
                val bytes=ByteArray(32768){32};repeat(padding/bytes.size){f.write(bytes)};write("\nendstream\nendobj\n")
                obj(6,"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
                val xref=f.filePointer;write("xref\n0 7\n0000000000 65535 f \n")
                offsets.drop(1).forEach {write("%010d 00000 n \n".format(java.util.Locale.ROOT,it))}
                write("trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
            }
            verify("A real 50 MiB PDF crosses the streaming import and parser boundary",books.import(Uri.fromFile(large),providedName="50MiB.pdf")&&books.list().first {it.name=="50MiB.pdf"}.let {books.original(it)!!.length()>=50L*1024*1024&&books.text(it).contains("FIFTY_MIB_STREAM_IMPORT")})
            val tooBig=File(fixtures,"too-big.pdf");RandomAccessFile(tooBig,"rw").use {it.setLength(65L*1024*1024)}
            val before=books.list().size
            verify("Over-limit input fails without changing the saved library",runCatching {books.import(Uri.fromFile(tooBig),providedName="65MiB.pdf")}.isFailure&&books.list().size==before)
            val backup=LibraryBackup(ctx,books);val archive=File(fixtures,"snapshot.paperis");backup.write(rebuilt,archive)
            val originalHash=BookFiles.hashFile(books.original(rebuilt)!!)
            val savedDirectory=File(root,"books");val hidden=File(root,"books-before-restore");check(savedDirectory.renameTo(hidden))
            val restored=BookFiles(ctx);val restoredBackup=LibraryBackup(ctx,restored)
            verify("Independent backup restores into an empty library",restoredBackup.restore(Uri.fromFile(archive))==rebuilt.id)
            val entry=checkNotNull(restored.get(rebuilt.id))
            verify("Restore recovers original, text, figures and chapter destinations",BookFiles.hashFile(restored.original(entry)!!)==originalHash&&restored.text(entry)==recognized&&restored.structure(entry,recognized)==structure&&structure.blocks.filter {it.kind=="image"}.all {restored.imageSize(entry,it.image)!=null})
            verify("Restore recovers reading position and bookmarks",restored.reading(entry).getInt("offset",-1)==anchor&&restored.reading(entry).getStringSet("bookmarks",emptySet())==setOf(anchor.toString()))
            val bad=File(fixtures,"traversal.paperis");ZipOutputStream(bad.outputStream()).use {z->z.putNextEntry(ZipEntry("../outside.txt"));z.write("bad".toByteArray());z.closeEntry()}
            val savedText=restored.text(entry)
            verify("Unsafe backup paths are rejected without modifying existing books",runCatching {restoredBackup.restore(Uri.fromFile(bad))}.isFailure&&restored.text(entry)==savedText&&!File(root,"outside.txt").exists())
            val corrupt=File(fixtures,"corrupt.paperis");ZipOutputStream(corrupt.outputStream()).use {z->z.putNextEntry(ZipEntry("book.json"));z.write("{}".toByteArray());z.closeEntry()}
            verify("Incomplete backups fail before library commit",runCatching {restoredBackup.restore(Uri.fromFile(corrupt))}.isFailure&&restored.text(entry)==savedText)
            val legacyId=BookFiles.hashBytes("legacy-fixture".toByteArray());val legacyText="旧书\n\n第一章 晨光\n\n这是一段没有正确恢复的\n\n中文物理换行。\n\n第二章 夜读\n\n保留旧书正文。"
            File(savedDirectory,"$legacyId.txt").writeText(legacyText)
            val indexFile=File(savedDirectory,"index.json");val index=JSONArray(indexFile.readText());index.put(JSONObject().put("id",legacyId).put("name","old.pdf").put("title","旧书").put("format","PDF"));indexFile.writeText(index.toString())
            val legacy=BookFiles(ctx);verify("Legacy text-only books reflow without requesting another import",!legacy.reprocess(legacyId)&&legacy.text(legacy.get(legacyId)!!).contains("这是一段没有正确恢复的中文物理换行。"))
            verify("Legacy reprocessing generates genuine chapter destinations",legacy.structure(legacy.get(legacyId)!!,legacy.text(legacy.get(legacyId)!!)).chapters.map {it.title}==listOf("第一章 晨光","第二章 夜读"))
            targetContext.cacheDir.listFiles()?.filter {it.name.startsWith("evidence12-")}?.forEach {it.deleteRecursively()}
            verify("New repository instance restores books with no cache dependency",BookFiles(ctx).let {store->store.text(store.get(entry.id)!!)==recognized&&store.original(store.get(entry.id)!!)!!.isFile})
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val reader=ui {InkView(ctx).also {activity.setContentView(it);it.openBook(entry,recognized)}}
            SystemClock.sleep(2200)
            ui {val layout=reader.currentBookLayout;val ranges=layout.pages.flatten();verify("OCR text and figures paginate without dropped or duplicated characters",ranges.joinToString(""){layout.text.substring(it.start,it.end)}==recognized)}
            uiAutomation.takeScreenshot()?.let {b->File(out,"01-scan-reader.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            File(out,"timing.json").writeText(JSONObject().put("elapsedMs",SystemClock.elapsedRealtime()-start).put("pssAtEndKiB",android.os.Debug.MemoryInfo().also {android.os.Debug.getMemoryInfo(it)}.totalPss).toString(2))
            ui {activity.finish()}
        } catch(t:Throwable){failure=t.stackTraceToString()}
        File(out,"library-report.json").writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("error",failure).toString(2))
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${checks.length()} durable library checks passed\n")})
    }
}
