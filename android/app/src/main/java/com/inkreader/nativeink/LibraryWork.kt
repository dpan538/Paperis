package com.inkreader.nativeink

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** In-app work only. Originals and checkpoints survive pause or process loss; no notification service. */
internal object LibraryWork {
    @Volatile var active=false;private set
    @Volatile var message="";private set
    var observer:((String,Boolean)->Unit)?=null
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    @Volatile private var running:Thread?=null
    private var signal=AtomicBoolean(false)
    private fun preferences(context:Context)=context.getSharedPreferences("library-work",Context.MODE_PRIVATE)
    fun journal(context:Context)=File(context.filesDir,"library-work.json")
    fun pending(context:Context)=journal(context).isFile
    fun mayResume(context:Context)=preferences(context).getBoolean("resumeOnOpen",true)
    @Synchronized fun prepare(context:Context,job:JSONObject) {
        val atomic=android.util.AtomicFile(journal(context));val stream=atomic.startWrite()
        try{stream.write(job.toString().toByteArray());atomic.finishWrite(stream)}catch(t:Throwable){atomic.failWrite(stream);throw t}
        preferences(context).edit().putBoolean("resumeOnOpen",true).commit()
    }
    @Synchronized fun start(context:Context,job:JSONObject?=null) {
        if(active)return
        val app=context.applicationContext
        if(job!=null)prepare(app,job)
        require(pending(app)) {"没有待继续的整理任务"}
        preferences(app).edit().putBoolean("resumeOnOpen",true).commit()
        val token=AtomicBoolean(false);signal=token
        status("正在准备书籍…",true)
        worker.execute {
            running=Thread.currentThread()
            try {
                if(token.get())status("已暂停，原文件和进度已保留",false)
                else runJob(app)
            } finally {running=null;Thread.interrupted()}
        }
    }
    internal fun status(value:String,busy:Boolean) {message=value;active=busy;main.post {observer?.invoke(value,busy)}}
    @Synchronized fun cancel(context:Context,manual:Boolean=true) {
        preferences(context).edit().putBoolean("resumeOnOpen",!manual).commit()
        signal.set(true);running?.interrupt()
    }
    private fun runJob(context:Context) {
        val books=BookFiles(context);val backup=LibraryBackup(context,books)
        var last=0L
        fun update(value:String,force:Boolean=false) {
            check(!signal.get()&&!Thread.currentThread().isInterrupted) {"已暂停，整理进度已保留，可在设置中继续"}
            val now=android.os.SystemClock.uptimeMillis();if(!force&&now-last<700)return;last=now
            status(value,true)
        }
        val result=runCatching {
            val job=JSONObject(journal(context).readText());val english=job.optBoolean("english")
            fun t(zh:String,en:String)=if(english)en else zh
            val kind=job.getString("kind")
            var notice=when(kind) {
                "reflow"->{
                    val entry=checkNotNull(books.get(job.getString("id")));val full=books.reprocess(entry.id,job.optBoolean("ocr")){p,n->update(t("重新排版 · ${entry.title}\n处理 $p / $n 页","Reprocessing · ${entry.title}\nPage $p / $n"))}
                    t(if(full)"重新排版完成，目录和插图已更新" else "已从保存的文字重新排版；此旧书未保存原文件",if(full)"Reprocessed; contents and illustrations updated" else "Reflowed saved text; this legacy book has no original copy")
                }
                "restore"->{val count=backup.restoreAll {p,n->update(t("正在恢复 $p / $n","Restoring $p / $n"))};t("已从备份恢复 $count 本书","Restored $count books")}
                "backup"->{val count=backup.backupAll {p,n->update(t("正在备份 $p / $n","Backing up $p / $n"))};t("已校验保存 $count 本书的独立备份","Verified independent backups of $count books")}
                else->{
                    val items=job.getJSONArray("items");var added=job.optInt("added");var duplicate=job.optInt("duplicates");val errors=mutableListOf<String>()
                    for(i in job.optInt("next") until items.length()) {
                        val item=items.getJSONObject(i);val uri=Uri.parse(item.getString("uri"));val name=item.optString("name").ifEmpty {books.displayName(uri)}
                        update(t("正在导入 ${i+1}/${items.length()} · $name","Importing ${i+1}/${items.length()} · $name"),true)
                        runCatching {books.import(uri,item.optString("folder"),providedName=name,progress={p,n->update(t("整理 $name · $p / $n\n扫描页将离线识别，可暂停后继续","Preparing $name · $p / $n\nScanned pages use offline OCR; pause/resume available"))})}
                            .onSuccess {if(it)added++ else duplicate++}.onFailure {if(signal.get()||Thread.currentThread().isInterrupted)throw it;errors+=ReaderErrors.message(it.message,english)}
                        job.put("next",i+1).put("added",added).put("duplicates",duplicate)
                        val atomic=android.util.AtomicFile(journal(context));val out=atomic.startWrite();try{out.write(job.toString().toByteArray());atomic.finishWrite(out)}catch(t:Throwable){atomic.failWrite(out);throw t}
                        if(uri.scheme=="file")java.io.File(uri.path.orEmpty()).takeIf {it.parentFile?.canonicalFile==java.io.File(context.filesDir,"incoming").canonicalFile}?.delete()
                    };if(errors.isEmpty())t("已导入 $added 本 · 已存在 $duplicate 本","Imported $added · Existing $duplicate")
                    else t("有 ${errors.size} 个文件未能导入","${errors.size} files could not be imported")+"\n"+errors.first()
                }
            }
            if(kind in setOf("import","reflow")&&backup.tree!=null) {
                update(t("正在同步到所选备份文件夹…","Saving to your backup folder…"),true)
                val saved=runCatching {backup.backupAll {p,n->update(t("备份 $p / $n","Backing up $p / $n"))}}
                check(!signal.get()&&!Thread.currentThread().isInterrupted) {"已暂停，整理进度已保留"}
                notice+=saved.fold({t("\n独立备份已校验","\nIndependent backup verified")},{backup.preferences.edit().putString("error",it.message).commit();t("\n本机已保存，备份失败；请在设置中重试","\nSaved locally; backup failed. Retry in Settings")})
            }
            check(!signal.get()&&!Thread.currentThread().isInterrupted) {"已暂停，整理进度已保留"}
            journal(context).delete();notice
        }
        val paused=signal.get()||Thread.currentThread().isInterrupted
        if(result.isFailure&&!paused)preferences(context).edit().putBoolean("resumeOnOpen",false).commit()
        val value=result.getOrElse {if(paused)"已暂停，原文件和进度已保留，可在设置中继续" else "整理未完成，原书保持不变\n"+ReaderErrors.message(it.message,false)}
        status(value,false)
    }
}
