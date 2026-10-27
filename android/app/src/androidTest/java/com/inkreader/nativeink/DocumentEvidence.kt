package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Optional local regression corpus. User books and extracted text never enter test assets. */
internal class DocumentEvidence(private val host:Instrumentation) {
    fun run(probe:Boolean=false)=with(host) {
        val out=File(targetContext.getExternalFilesDir(null),"document-verification").also {it.mkdirs()}
        val results=JSONArray();var failure:String?=null
        fun <T> ui(block:()->T):T {val r=AtomicReference<Result<T>>();runOnMainSync {r.set(runCatching(block))};return r.get().getOrThrow()}
        fun waitFor(name:String,p:()->Boolean) {val end=SystemClock.uptimeMillis()+60000;while(SystemClock.uptimeMillis()<end){if(p())return;SystemClock.sleep(100)};error("Timeout: $name")}
        fun capture(activity:MainActivity,name:String) {
            waitFor("settle") {ui {!activity.inkView.isRefreshing}};SystemClock.sleep(200)
            val latch=CountDownLatch(1);ui {activity.inkView.viewTreeObserver.registerFrameCommitCallback {latch.countDown()};activity.inkView.invalidate()};check(latch.await(15,TimeUnit.SECONDS))
            uiAutomation.takeScreenshot()?.let {b->File(out,"$name.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        }
        try {
            val input=File(targetContext.getExternalFilesDir(null),"import-samples")
            val books=input.listFiles()?.filter {it.extension.lowercase() in setOf("epub","pdf")}.orEmpty().sortedBy {it.name}
            require(books.isNotEmpty()) {"Place private EPUB/PDF samples in the app external-files/import-samples directory"}
            if(probe) {
                if(android.os.Build.VERSION.SDK_INT<35)error("PDF probe needs API 35")
                val pdf=books.first {it.extension=="pdf"}
                android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(pdf,android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use {doc->
                    val data=JSONArray()
                    for(i in listOf(4,10))doc.openPage(i).use {page->
                        val parts=JSONArray()
                        val searches=JSONArray();page.textContents.flatMap {it.text.lineSequence().filter {s->s.isNotBlank()}.toList()}.take(10).forEach {line->searches.put(JSONObject().put("text",line).put("matches",JSONArray().apply {page.searchText(line.trim()).forEach {m->put(JSONObject().put("at",m.textStartIndex).put("bounds",JSONArray().apply {m.bounds.forEach {r->put(JSONArray(listOf(r.left,r.top,r.right,r.bottom)))}}))}}))}
                        page.textContents.forEach {c->parts.put(JSONObject().put("text",c.text).put("bounds",JSONArray().apply {c.bounds.forEach {put(JSONArray(listOf(it.left,it.top,it.right,it.bottom)))}}))}
                        data.put(JSONObject().put("page",i).put("parts",parts).put("searches",searches))
                    }
                    File(out,"pdf-probe.json").writeText(data.toString(2))
                }
                finish(Activity.RESULT_OK,Bundle().apply {putString("stream","PDF geometry probe saved\n")});return
            }
            val files=BookFiles(targetContext)
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            waitFor("window") {ui {activity.inkView.pageCount>0}}
            for((i,file) in books.withIndex()) {
                val start=SystemClock.uptimeMillis()
                files.import(Uri.fromFile(file),providedName=file.name,rebuild=true)
                val entry=checkNotNull(files.list().firstOrNull {it.name==file.name})
                val text=files.text(entry);val structure=files.structure(entry,text)
                val expectationFile=File(input,"expectations.json")
                val expected=if(expectationFile.isFile)JSONObject(expectationFile.readText()).optJSONObject(file.name) else null
                expected?.optJSONArray("chapterSequence")?.let {sequence->
                    check(structure.chapters.map {ChapterScanner.key(it.title)}==(0 until sequence.length()).map {ChapterScanner.key(sequence.getString(it))}) {"Source contents differs from independently supplied chapter sequence: ${structure.chapters.map {it.title}}"}
                    check(structure.chapters.all {it.source=="epub"}) {"Complete source navigation was replaced by guesses"}
                }
                expected?.optJSONArray("uniqueHeadings")?.let {headings->repeat(headings.length()){n->
                    val key=ChapterScanner.key(headings.getString(n))
                    check(structure.blocks.count {ChapterScanner.key(text.substring(it.start,it.end))==key}==1) {"Printed contents or duplicate HTML title remains: $key"}
                }}
                expected?.optJSONArray("forbiddenChapters")?.let {labels->repeat(labels.length()){n->
                    check(structure.chapters.none {ChapterScanner.key(it.title)==ChapterScanner.key(labels.getString(n))}) {"Printed contents or publisher metadata was classified as a chapter: ${labels.getString(n)}"}
                }}
                expected?.optJSONArray("chapterPrefixes")?.let {prefixes->repeat(prefixes.length()){n->val prefix=prefixes.getString(n);check(structure.chapters.any {c->ChapterScanner.key(c.title).startsWith(ChapterScanner.key(prefix))}) {"Required chapter missing: $prefix"}}}
                expected?.optJSONArray("paragraphs")?.let {paragraphs->repeat(paragraphs.length()){n->check(text.contains(paragraphs.getString(n))) {"Known paragraph was split or altered"}}}
                check(structure.blocks.filter {it.kind=="image"}.all {files.imageSize(entry,it.image)!=null}) {"An imported illustration has no decodable local asset"}
                File(out,"$i-structure.json").writeText(structure.json().put("name",file.name).put("characters",text.length).put("elapsedMs",SystemClock.uptimeMillis()-start).toString(2))
                File(out,"$i-text.txt").writeText(text)
                check(structure.chapters.size>=2) {"No useful directory: ${file.name}"}
                check(!text.contains('\uFFFC')) {"Raw object replacement glyph: ${file.name}"}
                check(structure.chapters.all {it.offset in text.indices}&&structure.chapters.zipWithNext().all {(a,b)->a.offset<=b.offset})
                ui {activity.inkView.openBook(entry,text);activity.inkView.jumpToPage(0)}
                capture(activity,"$i-opening")
                val chosen=structure.chapters.firstOrNull {it.level==2}?:structure.chapters[structure.chapters.size/2]
                ui {activity.inkView.menuCommand(MenuCommand(MenuAction.CHAPTER,chosen.offset))}
                capture(activity,"$i-chapter")
                ui {
                    val b=activity.inkView.currentBookLayout;val slices=b.pages.flatten()
                    check(slices.first().start==0&&slices.last().end==text.length&&slices.zipWithNext().all {(a,c)->a.end==c.start}) {"Lost/duplicated ranges: ${file.name}"}
                    check(b.pages[activity.inkView.currentPage].let {chosen.offset in it.first().start until it.last().end}) {"Wrong TOC destination"}
                    check(slices.all {b.layout.getLineBottom(it.lastLine)-b.layout.getLineTop(it.firstLine)<=b.bodyHeight+.5f}) {"Clipped column: ${file.name}"}
                    for(line in 0 until b.layout.lineCount)check(b.layout.getLineRight(line)<=b.columnWidth+.5f) {"Clipped line $line: ${file.name}: ${b.layout.getLineRight(line)} > ${b.columnWidth}: ${text.substring(b.layout.getLineStart(line),b.layout.getLineEnd(line))}"}
                    val audit=BookDiagnostics.inspect(ParsedBook(entry.title,text,entry.format,structure),b)
                    File(out,"$i-audit.json").writeText(JSONObject().put("findings",JSONArray(audit.map {it.json()})).put("spacingDecisions",JSONArray(b.spacingDecisions.map {d->JSONObject().put("block",d.block).put("offset",d.start).put("justified",d.justified).put("reason",d.reason).put("maxSpaceRatio",d.maxSpaceRatio)})).toString(2))
                    check(audit.none {it.severity=="error"}) {"Book diagnostics failed: ${audit.filter {it.severity=="error"}.map {it.json()}}"}
                    expected?.optJSONObject("chapterBody")?.let {pairs->for(label in pairs.keys()) {
                        val chapter=structure.chapters.single {ChapterScanner.key(it.title)==ChapterScanner.key(label)}
                        val page=b.pageForOffset(chapter.offset);val content=b.pageText(page)
                        check(content.contains(pairs.getString(label))) {"Chapter $label is stranded on a title-only page"}
                        check(page==0||!b.pageText(page-1).trim().let {ChapterScanner.key(it)==ChapterScanner.key(label)}) {"A duplicate title-only page precedes $label"}
                    }}
                    activity.inkView.openMenu(MenuPage.NAVIGATION)
                }
                capture(activity,"$i-contents")
                expected?.optJSONArray("readingPoints")?.let {points->repeat(points.length()){n->
                    val at=text.indexOf(points.getString(n));check(at>=0){"Independently supplied reading point is absent: $n"}
                    ui {activity.inkView.toggleTools();activity.inkView.jumpToPage(activity.inkView.currentBookLayout.pageForOffset(at))}
                    capture(activity,"$i-point-$n")
                    if(n+1<points.length())ui {activity.inkView.openMenu(MenuPage.NAVIGATION)}
                }}
                results.put(JSONObject().put("file",file.name).put("passed",true).put("chapters",structure.chapters.size).put("source",structure.directorySource).put("repairedBreaks",structure.repairedBreaks))
            }
        }catch(t:Throwable){failure=t.stackTraceToString()}
        File(out,"document-report.json").writeText(JSONObject().put("passed",failure==null).put("books",results).put("error",failure).toString(2))
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${results.length()} private books imported, navigated and laid out\n")})
    }
}
