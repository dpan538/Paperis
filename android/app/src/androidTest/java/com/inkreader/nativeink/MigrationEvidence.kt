package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Exercises both source-preserving upgrades and the actual attach-original legacy flow. */
internal class MigrationEvidence(private val host:Instrumentation) {
    fun run()=with(host) {
        val out=File(targetContext.getExternalFilesDir(null),"migration-verification").also {it.mkdirs()}
        val checks=JSONArray();var failure:String?=null
        fun <T> ui(f:()->T):T {val r=AtomicReference<Result<T>>();runOnMainSync {r.set(runCatching(f))};return r.get().getOrThrow()}
        fun waitFor(name:String,f:()->Boolean){val end=SystemClock.uptimeMillis()+90000;while(SystemClock.uptimeMillis()<end){if(f())return;SystemClock.sleep(100)};error("Timeout: $name")}
        fun verify(name:String,yes:Boolean){checks.put(JSONObject().put("check",name).put("passed",yes));check(yes){name}}
        try {
            val installed=targetContext.packageManager.getPackageInfo(targetContext.packageName,android.content.pm.PackageManager.GET_PERMISSIONS or android.content.pm.PackageManager.GET_SERVICES)
            verify("The installed app requests no notification or foreground-service permission and declares no service",installed.requestedPermissions.orEmpty().none {it.contains("NOTIFICATION")||it.contains("FOREGROUND_SERVICE")}&&installed.services.isNullOrEmpty())
            val input=File(targetContext.getExternalFilesDir(null),"import-samples")
            val expectation=JSONObject(File(input,"migration.json").readText())
            val expectedChapters=expectation.getInt("chapters")
            val source=File(input,expectation.getString("file"))
            require(source.isFile);val id=BookFiles.hashFile(source);val files=BookFiles(targetContext)
            files.import(Uri.fromFile(source),providedName=source.name,rebuild=true)
            var entry=checkNotNull(files.get(id));val originalText=files.text(entry)
            val anchor=originalText.indexOf(expectation.getString("anchor"));require(anchor>0)
            files.reading(entry).edit().putInt("offset",anchor).putStringSet("bookmarks",setOf(anchor.toString())).commit()
            val json=File(files.bundle(entry),"structure.json");val old=JSONObject(json.readText());old.remove("revision");json.writeText(old.toString())
            verify("An older saved source is recognized for local repair",files.needsRepair(entry))
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            ui {activity.inkView.openBook(entry,originalText)}
            waitFor("automatic source repair"){!LibraryWork.active&&BookFiles(targetContext).let {f->f.get(id)?.let {!f.needsRepair(it)}==true}}
            waitFor("reading resumed"){ui {!activity.inkView.libraryOpen&&!activity.inkView.importBusy}}
            files.reload();entry=checkNotNull(files.get(id));val repaired=files.text(entry);val structure=files.structure(entry,repaired)
            verify("Source-backed old books rebuild without a picker or another import",files.original(entry)?.let {BookFiles.hashFile(it)==id}==true&&structure.directorySource=="epub"&&structure.chapters.size==expectedChapters)
            verify("Source-backed repair preserves the reading anchor and bookmark",files.reading(entry).getInt("offset",0)==anchor&&files.reading(entry).getStringSet("bookmarks",emptySet())==setOf(anchor.toString()))
            val body="保留这段完整的旧版正文，修复时既不能少字，也不能制造新章节。".repeat(5)
            val legacy="书名\n\nTable of Contents\n\n目录\n\n第一章 清晨\n\n第二章 暮色\n\n前言\n\n前言\n\n$body\n\n第一章 清晨\n\n第一章 清晨\n\n一\n\n1\n\n$body"
            val oldEntry=files.commit(id,source.name,entry.folder,ParsedBook(entry.title,legacy,"EPUB"),null,entry)
            val meta=File(files.bundle(oldEntry),"structure.json");val o=JSONObject(meta.readText());o.remove("revision");meta.writeText(o.toString())
            ui {activity.inkView.openBook(oldEntry,legacy)}
            waitFor("legacy text repair"){!LibraryWork.active&&BookFiles(targetContext).let {f->f.get(id)?.let {!f.needsRepair(it)}==true}}
            waitFor("legacy reading resumed"){ui {!activity.inkView.libraryOpen&&!activity.inkView.importBusy}}
            files.reload();entry=checkNotNull(files.get(id));val text=files.text(entry);val guessed=files.structure(entry,text)
            verify("Text-only old books remove printed contents and adjacent duplicate titles",!text.contains("Table of Contents")&&guessed.blocks.count {ChapterScanner.key(text.substring(it.start,it.end))=="第一章清晨"}==1&&text.contains("一\n\n1\n\n"+body))
            verify("A missing original is explicit and never labelled as source contents",!files.hasOriginal(entry)&&guessed.directorySource=="generated")
            val picker=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent)=if(intent.action==Intent.ACTION_OPEN_DOCUMENT)Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.fromFile(source))) else null}
            addMonitor(picker)
            ui {activity.inkView.menuCommand(MenuCommand(MenuAction.ORIGINAL_FILE))}
            removeMonitor(picker)
            waitFor("original attachment"){BookFiles(targetContext).let {f->f.get(id)?.let {f.hasOriginal(it)&&f.structure(it,f.text(it)).directorySource=="epub"}==true}}
            waitFor("attached reading resumed"){ui {!activity.inkView.libraryOpen&&!activity.inkView.importBusy}}
            files.reload();entry=checkNotNull(files.get(id))
            verify("Attaching the original restores all source text, images and all source chapter destinations in the same book",files.text(entry)==originalText&&files.list().count {it.id==id}==1&&files.structure(entry,files.text(entry)).chapters.size==expectedChapters)
            val bad=File(out,"different.epub").also {it.writeText("not the original")}
            val previous=files.text(entry)
            verify("A mismatched replacement cannot overwrite the saved book",runCatching {files.attachOriginal(id,Uri.fromFile(bad))}.isFailure&&files.text(checkNotNull(files.get(id)))==previous)
            bad.delete();ui {activity.finish()}
        }catch(t:Throwable){failure=t.stackTraceToString()}
        File(out,"migration-report.json").writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("error",failure).toString(2))
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${checks.length()} source and text-only legacy upgrade checks passed\n")})
    }
}
