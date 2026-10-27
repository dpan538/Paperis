package com.inkreader.nativeink

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** User-initiated foreground work, with a durable job journal and per-page PDF checkpoints. */
internal object LibraryWork {
    @Volatile var active=false;private set
    @Volatile var message="";private set
    var observer:((String,Boolean)->Unit)?=null
    private val main=Handler(Looper.getMainLooper())
    fun journal(context:Context)=File(context.filesDir,"library-work.json")
    fun pending(context:Context)=journal(context).isFile
    fun start(context:Context,job:JSONObject?=null) {
        if(active)return
        if(job!=null) {
            val atomic=android.util.AtomicFile(journal(context));val stream=atomic.startWrite()
            try{stream.write(job.toString().toByteArray());atomic.finishWrite(stream)}catch(t:Throwable){atomic.failWrite(stream);throw t}
        }
        active=true
        try {context.startForegroundService(Intent(context,LibraryService::class.java))}catch(t:Throwable){active=false;throw t}
    }
    internal fun status(value:String,busy:Boolean) {message=value;active=busy;main.post {observer?.invoke(value,busy)}}
    fun cancel(context:Context) {context.startService(Intent(context,LibraryService::class.java).setAction("cancel"))}
}

class LibraryService:Service() {
    private val worker=Executors.newSingleThreadExecutor();private var task:java.util.concurrent.Future<*>?=null
    private val manager get()=getSystemService(NotificationManager::class.java)
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="cancel") {task?.cancel(true);return START_NOT_STICKY}
        manager.createNotificationChannel(NotificationChannel("library","书籍整理 / Library",NotificationManager.IMPORTANCE_LOW))
        startForeground(41,notification("正在准备书籍…"),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if(task?.isDone==false)return START_NOT_STICKY
        LibraryWork.status("正在准备书籍…",true)
        task=worker.submit {runJob(startId)};return START_NOT_STICKY
    }
    private fun notification(value:String):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel=PendingIntent.getService(this,1,Intent(this,LibraryService::class.java).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this,"library").setSmallIcon(com.inkreader.nativeink.R.drawable.ic_ink).setContentTitle("Paperis 纸墨").setContentText(value.replace('\n',' ')).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"暂停 / Pause",cancel).build()).build()
    }
    private fun runJob(startId:Int) {
        val books=BookFiles(this);val backup=LibraryBackup(this,books)
        var last=0L
        fun update(value:String,force:Boolean=false) {
            check(!Thread.currentThread().isInterrupted) {"已暂停，整理进度已保留，可在设置中继续"}
            val now=android.os.SystemClock.uptimeMillis();if(!force&&now-last<700)return;last=now
            LibraryWork.status(value,true);manager.notify(41,notification(value))
        }
        val result=runCatching {
            val job=JSONObject(LibraryWork.journal(this).readText());val english=job.optBoolean("english")
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
                        val item=items.getJSONObject(i);val uri=Uri.parse(item.getString("uri"));val name=books.displayName(uri)
                        update(t("正在导入 ${i+1}/${items.length()} · $name","Importing ${i+1}/${items.length()} · $name"),true)
                        runCatching {books.import(uri,item.optString("folder"),progress={p,n->update(t("整理 $name · $p / $n\n扫描页将离线识别，可暂停后继续","Preparing $name · $p / $n\nScanned pages use offline OCR; pause/resume available"))})}
                            .onSuccess {if(it)added++ else duplicate++}.onFailure {if(Thread.currentThread().isInterrupted)throw it;errors+="$name: ${ReaderErrors.message(it.message,english)}"}
                        job.put("next",i+1).put("added",added).put("duplicates",duplicate)
                        val atomic=android.util.AtomicFile(LibraryWork.journal(this));val out=atomic.startWrite();try{out.write(job.toString().toByteArray());atomic.finishWrite(out)}catch(t:Throwable){atomic.failWrite(out);throw t}
                    };t("导入 $added · 已存在 $duplicate · 失败 ${errors.size}\n原文件、目录和插图已保存在本机","Imported $added · Existing $duplicate · Failed ${errors.size}\nOriginals, contents and illustrations saved locally")+errors.firstOrNull()?.let {"\n$it"}.orEmpty()
                }
            }
            if(kind in setOf("import","reflow")&&backup.tree!=null) {
                update(t("正在同步到所选备份文件夹…","Saving to your backup folder…"),true)
                val saved=runCatching {backup.backupAll {p,n->update(t("备份 $p / $n","Backing up $p / $n"))}}
                notice+=saved.fold({t("\n独立备份已校验","\nIndependent backup verified")},{backup.preferences.edit().putString("error",it.message).commit();t("\n本机已保存，备份失败；请在设置中重试","\nSaved locally; backup failed. Retry in Settings")})
            }
            LibraryWork.journal(this).delete();notice
        }
        val value=result.getOrElse {if(Thread.currentThread().isInterrupted)"已暂停，原文件和进度已保留，可在设置中继续" else "整理未完成，原书保持不变：${it.message}\n可在设置中继续"}
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);LibraryWork.status(value,false)
    }
    override fun onTimeout(startId:Int,fgsType:Int) {
        task?.cancel(true);stopSelf(startId);LibraryWork.status("整理已暂停，进度已保留；可在设置中继续",false)
    }
    override fun onDestroy() {worker.shutdownNow();super.onDestroy()}
}
