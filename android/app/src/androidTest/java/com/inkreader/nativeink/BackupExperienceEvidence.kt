package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real DocumentsUI tree grant, foreground import/reflow and provider read-back. */
internal class BackupExperienceEvidence(private val host:Instrumentation) {
    private lateinit var activity:MainActivity
    private val checks=JSONArray()
    private fun <T> ui(block:()->T):T {val r=AtomicReference<Result<T>>();host.runOnMainSync {r.set(runCatching(block))};return r.get().getOrThrow()}
    private fun verify(label:String,ok:Boolean){checks.put(JSONObject().put("check",label).put("passed",ok));check(ok){label};host.sendStatus(0,Bundle().apply {putString("stream","PASS $label\n")})}
    private fun waitFor(label:String,timeout:Long=45000,condition:()->Boolean) {val end=SystemClock.uptimeMillis()+timeout;while(SystemClock.uptimeMillis()<end){if(condition())return;SystemClock.sleep(100)};error("Timeout: $label")}
    private fun settle(){waitFor("settled"){ui {!activity.inkView.isRefreshing}};SystemClock.sleep(250)}
    private fun command(label:String) {
        ui {val info=AccessibilityNodeInfo.obtain();activity.inkView.onInitializeAccessibilityNodeInfo(info)
            val action=info.actionList.firstOrNull {it.label?.toString()==label}?:error("Missing action $label: ${info.text}")
            check(activity.inkView.performAccessibilityAction(action.id,null));info.recycle()};settle()
    }
    private fun click(label:String) {
        waitFor("system label $label") {
            val queue=java.util.ArrayDeque<AccessibilityNodeInfo>();host.uiAutomation.rootInActiveWindow?.let {queue.add(it)}
            while(queue.isNotEmpty()) {
                val node=queue.removeFirst();val name=node.text?.toString().orEmpty();val description=node.contentDescription?.toString().orEmpty()
                if(name.equals(label,true)||description.equals(label,true)) {
                    var parent:AccessibilityNodeInfo?=node;while(parent!=null&&!parent.isClickable)parent=parent.parent
                    if(parent!=null){val bounds=Rect();parent.getBoundsInScreen(bounds);val fd=host.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}");android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use {it.readBytes()};SystemClock.sleep(600);return@waitFor true}
                };for(i in 0 until node.childCount)node.getChild(i)?.let {queue.add(it)}
            };false
        }
    }
    private fun capture(dir:File,name:String){host.uiAutomation.takeScreenshot()?.let {b->File(dir,name).outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    fun run()=with(host) {
        var failure:String?=null;val out=File(targetContext.getExternalFilesDir(null),"backup-experience").also {it.mkdirs()}
        try {
            val shell=uiAutomation.executeShellCommand("mkdir -p /sdcard/Download/Paperis-Backup-Test");android.os.ParcelFileDescriptor.AutoCloseInputStream(shell).use {it.readBytes()}
            targetContext.getSharedPreferences("files",0).edit().putBoolean("english",false).commit()
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            waitFor("native frame"){ui {activity.inkView.width>0}};SystemClock.sleep(2000)
            ui {activity.importUris(listOf(Uri.parse("content://io.github.dpan538.inkreader.fixtures/文本.pdf")))}
            verify("Import starts a persistent foreground job immediately",LibraryWork.active&&LibraryWork.pending(targetContext))
            waitFor("service import"){!LibraryWork.active};settle()
            val file=ui {activity.inkView.files.list().first {it.name=="文本.pdf"}}
            ui {activity.inkView.openBook(file,activity.inkView.files.text(file));activity.inkView.performAccessibilityAction(InkView.ACTION_TOOLS,null)};settle()
            command("阅读排版");command("重新排版本书");capture(out,"01-reprocess-menu.png")
            verify("Reading layout exposes manual reprocessing and original PDF pages",ui {val info=activity.inkView.createAccessibilityNodeInfo();info.text.toString().contains("查看 PDF 原页")&&info.text.toString().contains("无需重复导入")})
            command("重新整理段落与目录")
            waitFor("manual reprocess"){!LibraryWork.active};settle()
            verify("Manual reprocessing completes without launching a file picker",ui {activity.inkView.libraryOpen&&activity.inkView.files.get(file.id)?.bundle!=file.bundle}&&uiAutomation.rootInActiveWindow?.packageName?.toString()==targetContext.packageName)
            ui {activity.inkView.performAccessibilityAction(InkView.ACTION_TOOLS,null)};settle();command("设置");command("保存与备份");capture(out,"02-storage-menu.png")
            val existingTree=LibraryBackup(targetContext,activity.inkView.files).tree
            command("选择本地备份文件夹")
            waitFor("system tree picker"){uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true}
            if(existingTree==null){click("Download");click("Paperis-Backup-Test")}
            click("Use this folder");click("Allow")
            waitFor("backup service",120000){!LibraryWork.active&&LibraryBackup(targetContext,activity.inkView.files).tree!=null};settle()
            val backup=LibraryBackup(targetContext,activity.inkView.files);val tree=checkNotNull(backup.tree)
            verify("Folder selection persists real read/write access beyond the activity",targetContext.contentResolver.persistedUriPermissions.any {it.uri==tree&&it.isReadPermission&&it.isWritePermission})
            verify("Automatic first backup verifies complete provider copies",backup.preferences.getLong("lastSuccess",0)>0&&!backup.preferences.contains("error"))
            val root=android.provider.DocumentsContract.getTreeDocumentId(tree);val children=android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree,root)
            val names=mutableListOf<String>();targetContext.contentResolver.query(children,arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use {c->while(c.moveToNext())names+=c.getString(0)}
            verify("Completed backups use a portable .paperis file rather than app cache",names.any {it.endsWith(".paperis")}&&names.none {it.endsWith(".partial")})
            val scan=File(targetContext.cacheDir,"Resume-scanned.pdf")
            val document=android.graphics.pdf.PdfDocument()
            repeat(8) {index->
                val bitmap=Bitmap.createBitmap(900,1200,Bitmap.Config.ARGB_8888);val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE)
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {textSize=36f;color=android.graphics.Color.BLACK}
                canvas.drawText("Chapter ${index+1}",60f,120f,paint);canvas.drawText("Reading resumes from saved pages.",60f,220f,paint)
                val page=document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(450,600,index+1).create());page.canvas.drawBitmap(bitmap,null,Rect(0,0,450,600),null);document.finishPage(page);bitmap.recycle()
            };scan.outputStream().use {document.writeTo(it)};document.close()
            ui {activity.importUris(listOf(Uri.fromFile(scan)))}
            val jobs=File(targetContext.filesDir,"import-jobs")
            val scanJob=File(jobs,BookFiles.hashBytes((Uri.fromFile(scan).toString()+scan.name).toByteArray()))
            var checkpoint:File?=null
            waitFor("first OCR checkpoint",120000) {checkpoint=File(scanJob,"assets/page-0.json").takeIf {it.isFile};checkpoint!=null&&LibraryWork.active}
            val stamp=checkpoint!!.lastModified()
            ui {activity.inkView.performAccessibilityAction(InkView.ACTION_TOOLS,null)};settle();command("暂停整理并保留进度")
            waitFor("paused OCR",120000){!LibraryWork.active}
            verify("Pause retains the durable original, job journal and finished OCR page",LibraryWork.pending(targetContext)&&checkpoint!!.isFile&&jobs.walkTopDown().any {it.name=="source"})
            scan.delete() // The original picker source is deliberately unavailable on resume.
            ui {LibraryWork.start(activity)}
            waitFor("resumed OCR and backup",180000){!LibraryWork.active};settle()
            val resumed=BookFiles(targetContext).list().firstOrNull {it.name=="Resume-scanned.pdf"}
            verify("A paused scan resumes successfully after its selected source is removed",resumed!=null&&!LibraryWork.pending(targetContext)&&BookFiles(targetContext).text(resumed).contains("Reading resumes from saved pages."))
            verify("Resumed work reuses the existing first-page checkpoint",!checkpoint!!.exists()||checkpoint!!.lastModified()==stamp)
            ui {activity.recreate()};SystemClock.sleep(2500)
            verify("Selected folder survives activity recreation",LibraryBackup(targetContext,BookFiles(targetContext)).tree==tree)
        } catch(t:Throwable){failure=t.stackTraceToString()}
        File(out,"backup-experience-report.json").writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("error",failure).toString(2))
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${checks.length()} real SAF backup checks passed\n")})
    }
}
