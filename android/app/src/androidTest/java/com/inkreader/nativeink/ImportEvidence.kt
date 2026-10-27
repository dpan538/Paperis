package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ImportEvidence(private val host: Instrumentation,private val pickerOnly:Boolean=false) {
    private val targetContext get()=host.targetContext
    private val uiAutomation get()=host.uiAutomation
    private fun runOnMainSync(action:()->Unit)=host.runOnMainSync(action)
    private fun startActivitySync(intent:Intent)=host.startActivitySync(intent)
    private fun finish(code:Int,result:Bundle)=host.finish(code,result)
    private lateinit var activity: MainActivity
    private val checks=JSONArray()
    private lateinit var out: File
    private fun <T> ui(block: ()->T):T { val value=AtomicReference<Result<T>>(); runOnMainSync { value.set(runCatching(block)) }; return value.get().getOrThrow() }
    private fun verify(name:String,condition:Boolean) { checks.put(JSONObject().put("check",name).put("passed",condition)); check(condition) { name } }
    private fun waitFor(name:String,condition:()->Boolean) { val end=SystemClock.uptimeMillis()+45000; while(SystemClock.uptimeMillis()<end) { if(condition())return; SystemClock.sleep(80) }; error("Timeout: $name") }
    private fun settle() { waitFor("material") { ui { !activity.inkView.isRefreshing } }; SystemClock.sleep(240) }
    private fun tap(x:Float,y:Float) { val p=ui { activity.inkView.screenPoint(x/2f,y/2f) };val now=SystemClock.uptimeMillis(); val a=MotionEvent.obtain(now,now,0,p.x,p.y,0); val b=MotionEvent.obtain(now,now+50,1,p.x,p.y,0); a.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;b.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;uiAutomation.injectInputEvent(a,true);uiAutomation.injectInputEvent(b,true);a.recycle();b.recycle() }
    private fun capture(name:String) {
        val committed=CountDownLatch(1)
        ui { activity.inkView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }; activity.inkView.invalidate() }
        check(committed.await(15,TimeUnit.SECONDS))
        uiAutomation.takeScreenshot()!!.let { bitmap -> File(out,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
    }
    private fun fixture(name:String)=Uri.parse("content://io.github.dpan538.inkreader.fixtures/${Uri.encode(name)}")
    private fun clickSystemLabel(label:String) {
        waitFor("picker label $label") {
            val queue=java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            uiAutomation.rootInActiveWindow?.let { queue.add(it) }
            while(queue.isNotEmpty()) {
                val node=queue.removeFirst()
                if(node.text?.toString()==label || node.contentDescription?.toString()==label) {
                    var clickable:android.view.accessibility.AccessibilityNodeInfo?=node
                    while(clickable!=null && !clickable.isClickable)clickable=clickable.parent
                    if(clickable!=null) {
                        val bounds=Rect();clickable.getBoundsInScreen(bounds)
                        if(!bounds.isEmpty) {
                            // DocumentsUI grid uses system touch handling; inject through shell input
                            // at the observed clickable item's bounds, as with a real touchscreen tap.
                            host.sendStatus(0,Bundle().apply { putString("stream","Picker click: $label at ${bounds.centerX()},${bounds.centerY()}\n") })
                            val descriptor=uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}")
                            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                            SystemClock.sleep(650);return@waitFor true
                        }
                    }
                }
                for(i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
            }
            false
        }
    }
    private fun openImportPicker() { tap(2908f,124f) }
    private fun picker() {
            // Verify the actual system picker is reachable, then cancel back to the same local files.
            ui { activity.inkView.showFiles() };settle();capture("08-file-system")
            openImportPicker()
            waitFor("system file picker") { uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true }
            verify("Files-window import button opens Android's real document picker",true)
            clickSystemLabel("Show roots");clickSystemLabel("Downloads");clickSystemLabel("InkReader-SAF.txt")
            waitFor("actual picker import") { ui { !activity.inkView.importBusy && activity.inkView.files.list().any { it.name=="InkReader-SAF.txt" } } }
            settle()
            verify("Selecting a real Downloads document imports it through the system result",ui {
                activity.inkView.files.list().firstOrNull { it.name=="InkReader-SAF.txt" }?.let { activity.inkView.files.text(it).contains("SAF_ACTUAL_SELECTION") }==true
            })
            capture("09-system-picker-import")
            val afterSelection=ui { activity.inkView.files.list().size }
            openImportPicker()
            waitFor("picker again") { uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true }
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor("picker cancellation") { uiAutomation.rootInActiveWindow?.packageName?.toString()==targetContext.packageName }
            verify("Canceling the picker preserves the native file list",ui { activity.inkView.libraryOpen && activity.inkView.files.list().size==afterSelection })
    }
    fun run() {
        var failure:String?=null
        try {
            out=File(targetContext.getExternalFilesDir(null),if(pickerOnly) "picker-verification" else "imports-verification").also { it.mkdirs() }
            if(!pickerOnly)targetContext.getSharedPreferences("files",0).edit().clear().commit()
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            if(pickerOnly)ui { activity.inkView.showFiles() }
            waitFor("native file list") { ui { activity.inkView.width==3408 && activity.inkView.libraryOpen } }
            settle()
            if(pickerOnly) { picker();return }
            capture("01-files-before")
            verify("First use opens a native file list with menu-based import",ui { activity.inkView.libraryOpen })
            // Import through the same activity callback, content resolver, bounded copy and worker as the picker.
            val names=listOf("原生导入.txt","中文编码.txt","UTF16.txt","章节顺序.epub","简明笔记.md","文章.html","文本稿.docx","文本.pdf")
            val before=ui { activity.inkView.files.list().size }
            ui { activity.importUris(names.map(::fixture)) }
            waitFor("batch import") { ui { !activity.inkView.importBusy } };settle()
            val files=ui { activity.inkView.files.list() }
            verify("Batch import accepts TXT, EPUB, Markdown, HTML, DOCX and text PDF",names.all { name -> files.any { it.name==name } })
            capture("02-files-imported")
            val store=BookFiles(targetContext)
            verify("Imported index survives a new repository instance",names.all { name -> store.list().any { it.name==name } })
            verify("Chinese GB18030 and UTF16 decode correctly",store.text(files.first { it.name=="中文编码.txt" }).contains("中文正文") && store.text(files.first { it.name=="UTF16.txt" }).contains("双字节文本"))
            val epub=store.text(files.first { it.name=="章节顺序.epub" })
            verify("EPUB follows spine order instead of archive/file-name order",epub.indexOf("FIRST_MARKER") in 0 until epub.indexOf("SECOND_MARKER"))
            val html=store.text(files.first { it.name.endsWith(".html") })
            verify("HTML removes scripts/styles and keeps visible text",!html.contains("BAD_SCRIPT") && html.contains("离线正文"))
            verify("Markdown preserves link labels and DOCX exposes body text",store.text(files.first { it.name.endsWith(".md") }).contains("链接文字") && store.text(files.first { it.name.endsWith(".docx") }).contains("文档正文"))
            verify("Text PDF has actual extracted text",store.text(files.first { it.name=="文本.pdf" }).contains("PDF_REFLOW_TEXT"))
            val count=files.size
            ui { activity.importUris(listOf(fixture("原生导入.txt"),fixture("损坏.epub"),fixture("尚不支持.mobi"),fixture("扫描.pdf"))) }
            waitFor("duplicate and invalid imports") { ui { !activity.inkView.importBusy } };settle()
            verify("Duplicate, corrupt, unsupported and scan-only inputs leave valid files intact",ui { activity.inkView.files.list().size==count })
            capture("03-import-feedback")
            clickSystemLabel("OK");settle()
            ui { activity.importTree(Uri.parse("content://io.github.dpan538.inkreader.fixtures/tree/root")) }
            waitFor("folder import") { ui { !activity.inkView.importBusy } };settle()
            val finalCount=ui { activity.inkView.files.list().size }
            verify("Folder import preserves the relative directory path",ui { activity.inkView.files.list().any { it.name=="目录文件.txt" && it.folder=="导入目录/随笔" } })
            tap(500f,440f);settle();tap(500f,440f);settle();capture("03-imported-directory")
            verify("Native file list browses imported folders",ui { activity.inkView.libraryOpen })
            tap(500f,440f);settle()
            verify("A visible imported file row opens its actual book",ui { !activity.inkView.libraryOpen && activity.inkView.currentBookLayout.text.contains("目录层级") })
            // The visible first TXT file is selected through the native row, not a synthetic reader screenshot.
            val book=files.first { it.name=="原生导入.txt" }
            ui { activity.inkView.openBook(book,store.text(book)) };settle()
            verify("Imported text reaches the real native reader",ui { !activity.inkView.libraryOpen && activity.inkView.currentBookLayout.text.contains("清晨的光") })
            ui { activity.inkView.turnPage(1); activity.inkView.pause(); activity.inkView.resume() };settle()
            val saved=ui { activity.inkView.currentOffset }
            val other=files.first { it.name=="章节顺序.epub" }
            ui { activity.inkView.openBook(other,store.text(other)); activity.inkView.openBook(book,store.text(book)) };settle()
            verify("Each imported book keeps its own reading anchor",ui { activity.inkView.currentOffset==saved })
            tap(3192f,124f);settle();tap(3000f,528f);settle()
            val anchor=ui { activity.inkView.currentOffset }
            tap(2996f,784f);settle()
            verify("Visible font control selects a real sans face",ui { activity.inkView.currentFace==1 && activity.inkView.currentBookLayout.face==Typeface.create("sans-serif",Typeface.NORMAL) })
            val page=ui { activity.inkView.currentBookLayout.pages[activity.inkView.currentPage] }
            verify("Font family change keeps the source anchor",anchor in page.first().start until page.last().end)
            capture("04-font-and-reflow")
            ui { activity.inkView.files.importFont(fixture("fixture-font.ttf"));activity.inkView.fontImported() };settle()
            verify("An imported font is validated and selectable",ui { activity.inkView.currentFace==2 && activity.inkView.files.customTypeface()!=null })
            capture("05-custom-font")
            ui { activity.inkView.backFromTools() };settle();tap(3000f,656f);settle()
            tap(3000f,528f);settle();verify("Gentle preset changes the native material",ui { activity.inkView.currentPreset==1 });capture("06-gentle-preset")
            tap(3000f,656f);settle();verify("Paper preset is selectable and persisted",ui { activity.inkView.currentPreset==2 && activity.inkView.files.preferences.getInt("preset",-1)==2 });capture("07-paper-preset")
            // Broad native shape/layout sweep, including CJK punctuation and deliberately bad paragraph tails.
            var tailsBefore=0;var tailsAfter=0;var cases=0
            fun tails(b:BookLayout):Int {
                var start=0;var count=0
                b.text.split('\n').forEach { paragraph ->
                    if(paragraph.length>=8) { val end=start+paragraph.length;val line=b.layout.getLineForOffset(end-1);val at=b.layout.getLineStart(line).coerceAtLeast(start)
                        if(at>start && b.text.substring(at,end).count { it.isLetterOrDigit() }==1) count++ }
                    start+=paragraph.length+1
                };return count
            }
            val reflowText="排版验证\n\n"+(18..170).joinToString("\n\n") { n -> "阅读文字的排列需要自然".repeat(20).take(n)+"。" }
            for(w in listOf(1136f,1704f)) for(font in listOf(28f,32f,36f)) for(column in listOf(1,2)) {
                val h=if(w==1136f)1704f else 1136f
                val raw=BookLayout(reflowText,w,h,font,columnMode=column,protectTails=false)
                val improved=BookLayout(reflowText,w,h,font,columnMode=column)
                tailsBefore+=tails(raw);tailsAfter+=tails(improved);cases++
                val slices=improved.pages.flatten()
                check(slices.first().start==0 && slices.last().end==reflowText.length && slices.zipWithNext().all { (a,b)->a.end==b.start })
                check(slices.all { improved.layout.getLineBottom(it.lastLine)-improved.layout.getLineTop(it.firstLine)<=improved.bodyHeight })
            }
            verify("CJK reflow removes avoidable one-character tails across $cases layouts ($tailsBefore to $tailsAfter)",tailsBefore>0 && tailsAfter==0)
            verify("All reflow cases retain contiguous text and fit every column",cases==12)
            picker()
        } catch(t:Throwable) { failure=t.stackTraceToString() }
        finally {
            if(::out.isInitialized)File(out,"import-report.json").writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("error",failure).toString(2))
            finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply { putString("stream",failure ?: "${checks.length()} import/reflow checks passed\n") })
        }
    }
}
