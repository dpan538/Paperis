package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
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

/** Per-book actions: independent raster/hit checks, then real UI + durable repository effects. */
internal class FileActionsEvidence(private val host:Instrumentation) {
    fun run()=with(host) {
        val out=File(targetContext.getExternalFilesDir(null),"file-actions-verification").also {it.mkdirs()}
        val checks=JSONArray();var failure:String?=null;var rounds=0;var activity:MainActivity?=null
        fun <T> ui(f:()->T):T {val r=AtomicReference<Result<T>>();runOnMainSync {r.set(runCatching(f))};return r.get().getOrThrow()}
        fun shell(s:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(s)).use {it.bufferedReader().readText()}
        fun verify(name:String,ok:Boolean){checks.put(JSONObject().put("check",name).put("passed",ok));check(ok){name}}
        fun waitFor(name:String,p:()->Boolean){val end=SystemClock.uptimeMillis()+45000;while(SystemClock.uptimeMillis()<end){if(p())return;SystemClock.sleep(60)};error("Timeout: $name")}
        fun reader()=checkNotNull(activity).inkView
        fun settle(){waitFor("settled ink"){ui {!reader().isRefreshing}};waitForIdleSync();SystemClock.sleep(140)}
        fun tap(p:PointF) {val now=SystemClock.uptimeMillis();for((a,t)in listOf(MotionEvent.ACTION_DOWN to now,MotionEvent.ACTION_UP to now+50)) {
            val event=MotionEvent.obtain(now,t,a,p.x,p.y,0);event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;uiAutomation.injectInputEvent(event,true);event.recycle()
        };settle()}
        fun shelf(action:String,id:String="") {
            repeat(150) {
                val p=ui {val v=reader();v.currentShelf.hitRegions.firstOrNull {it.second==ShelfCommand(action,id)}?.first?.let {v.screenPoint(it.centerX(),it.centerY())}}
                if(p!=null){tap(p);return}
                val next=ui {val v=reader();v.currentShelf.hitRegions.first {it.second.action=="next"}.first.let {v.screenPoint(it.centerX(),it.centerY())}}
                tap(next)
            };error("Missing shelf target $action/$id")
        }
        fun command(action:MenuAction) {
            repeat(12) {
                val p=ui {val v=reader();val m=v.currentMenuLayout;val r=m.hitRegions.first {it.command?.action==action}.bounds;val y=r.centerY()-v.menuScrollOffset
                    if(y>=m.bounds.top+m.headerHeight&&y<m.bounds.bottom)v.screenPoint(r.centerX(),y) else null}
                if(p!=null){tap(p);return}
                val points=ui {val v=reader();val m=v.currentMenuLayout;v.screenPoint(m.bounds.centerX(),m.bounds.bottom-12f) to v.screenPoint(m.bounds.centerX(),m.bounds.top+m.headerHeight+12f)}
                val now=SystemClock.uptimeMillis();for(i in 0..12){val event=MotionEvent.obtain(now,now+i*25L,if(i==0)MotionEvent.ACTION_DOWN else if(i==12)MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,points.first.x,points.first.y+(points.second.y-points.first.y)*i/12,0)
                    event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;uiAutomation.injectInputEvent(event,true);event.recycle()};settle()
            };error("Cannot reveal $action")
        }
        fun capture(name:String) {
            val latch=CountDownLatch(1);ui {reader().viewTreeObserver.registerFrameCommitCallback {latch.countDown()};reader().invalidate()}
            check(latch.await(15,TimeUnit.SECONDS));SystemClock.sleep(100)
            checkNotNull(uiAutomation.takeScreenshot()).also {b->File(out,"$name.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
        }
        fun launch() {
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            waitFor("ready window"){ui {reader().currentViewport!=null&&checkNotNull(activity).hasWindowFocus()}};settle()
        }
        fun centeredDots(bitmap:Bitmap,area:RectF):Boolean {
            var minY=Int.MAX_VALUE;var maxY=-1;val columns=sortedSetOf<Int>()
            for(y in area.top.toInt()..area.bottom.toInt())for(x in area.left.toInt()..area.right.toInt())if(x in 0 until bitmap.width&&y in 0 until bitmap.height&&Color.red(bitmap.getPixel(x,y))<145) {minY=minOf(minY,y);maxY=maxOf(maxY,y);columns+=x}
            if(columns.isEmpty())return false
            // At a half-pixel center, a valid small dot has only two dark core pixels.
            // Count the three separated ink groups instead of assuming a fixed pixel area.
            val groups=1+columns.zipWithNext().count {(a,b)->b-a>1}
            return groups==3&&columns.last()-columns.first() in 10..17&&maxY-minY<5&&
                kotlin.math.abs((columns.first()+columns.last()+1)/2f-area.centerX())<=1f&&kotlin.math.abs((minY+maxY+1)/2f-area.centerY())<=1f
        }
        val oldMotion=shell("settings get global animator_duration_scale").trim()
        val oldHidden=targetContext.getSharedPreferences("files",0).getBoolean("sampleHidden",false)
        val ownIds=mutableSetOf<String>()
        try {
            check(shell("getprop ro.kernel.qemu").trim()=="1"){"Only a disposable emulator is supported"}
            // 5 windows x 3 text scales x 2 languages x 10 labels: inspect actual dots, not font baselines.
            for((w,h)in listOf(320f to 640f,360f to 780f,412f to 840f,700f to 360f,1280f to 800f))for(scale in listOf(1f,1.3f,2f))for(english in listOf(false,true))for(n in 0..9) {
                val title=if(n%2==0)"书籍标题与副标题".repeat(n+1) else "A reading copy with a long subtitle ".repeat(n+1)
                val file=BookFile("book-$n","fixture.txt",title,"EPUB","",n,true)
                val shelf=FileShelf {it*scale};val b=Bitmap.createBitmap(w.toInt(),h.toInt(),Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(Color.WHITE)
                shelf.draw(c,w,h,listOf(file),"",0,"",false,english)
                val more=checkNotNull(shelf.moreBounds(file.id));val open=shelf.hitRegions.first {it.second.action=="book"}.first
                if(!centeredDots(b,more)) {
                    File(out,"failed-raster-$rounds.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}
                    File(out,"failed-raster-$rounds.json").writeText(JSONObject().put("width",w).put("height",h).put("scale",scale).put("action",more.toString()).put("row",open.toString()).toString(2))
                    error("Dot raster is not centered in condition $rounds")
                }
                check(kotlin.math.abs(more.centerY()-open.centerY())<.01f&&!RectF.intersects(more,open))
                check(shelf.hit(more.centerX(),more.centerY())==ShelfCommand("more",file.id)&&shelf.hit(open.centerX(),open.centerY())==ShelfCommand("book",file.id))
                check(shelf.titleRegions.all {it.bounds.right<more.left&&it.bounds.bottom<it.metadataTop+.5f})
                val model=MenuModel("",false,listOf(MenuRow(if(english)"Reprocess" else "重新排版",MenuCommand(MenuAction.FILE_REPROCESS)),MenuRow(if(english)"Delete" else "删除",MenuCommand(MenuAction.FILE_DELETE))),anchor=more,showHeader=false,showArrows=false)
                val menu=ReadingMenu {it*scale};menu.place(w,h,model)
                check(menu.bounds.left>=0&&menu.bounds.top>=0&&menu.bounds.right<=w&&menu.bounds.bottom<=h)
                check(menu.headerHeight==0f&&menu.textRegions.size==2&&menu.textRegions.none {it.value==title})
                check(menu.hitRegions.all {it.bounds.left>=menu.bounds.left&&it.bounds.right<=menu.bounds.right})
                if(rounds==0) {
                    verify("Raster detector rejects a five-dp dot offset",!centeredDots(b,RectF(more).apply {offset(0f,5f)}))
                    shelf.draw(c,w,h,listOf(file),"",0,"",true,english)
                    verify("Busy library disables file and overflow hit targets",shelf.hitRegions.isEmpty())
                };b.recycle();rounds++
            }
            verify("All 300 overflow raster, title and target conditions executed",rounds==300)
            shell("settings put global animator_duration_scale 1")
            val folder="Shelf regression"
            val library=BookFiles(targetContext)
            library.list().filter {it.folder==folder}.forEach {library.delete(it.id)}
            val sourceA=File(targetContext.cacheDir,"Shelf regression A.txt").apply {writeText("第一章 当前书\n\n"+"Current reading position stays in this book. 这本书的阅读位置必须保留。\n\n".repeat(45))}
            val sourceB=File(targetContext.cacheDir,"Shelf regression B.txt").apply {writeText("第一章 另一册\n\n"+"The selected book is reprocessed, not the currently opened book. 所选文件独立处理。\n\n".repeat(30)+"版权信息作者：示例作者出版社：示例出版社")}
            for(source in listOf(sourceA,sourceB))library.import(Uri.fromFile(source),folder)
            val a=library.list().first {it.name==sourceA.name};val b=library.list().first {it.name==sourceB.name};ownIds+=listOf(a.id,b.id)
            val originHash=BookFiles.hashFile(sourceB);val backup=File(out,"independent-backup.paperis");LibraryBackup(targetContext,library).write(b,backup);val backupHash=BookFiles.hashFile(backup)
            library.reading(a).edit().putInt("offset",121).putStringSet("bookmarks",setOf("121")).commit()
            library.reading(b).edit().putInt("offset",130).putStringSet("bookmarks",setOf("130")).commit()
            for((profile,size)in listOf(Triple(1080,2400,480),Triple(3408,2272,320)).withIndex()) {
                activity?.let {ui {it.finish()};waitFor("previous activity destroyed"){it.isDestroyed}}
                shell("wm user-rotation lock 0");shell("wm size ${size.first}x${size.second}");shell("wm density ${size.third}")
                targetContext.getSharedPreferences("files",0).edit().putString("lastBook",a.id).putBoolean("english",profile==0).putBoolean("sampleHidden",false).commit()
                launch();ui {reader().showFiles()};settle();shelf("folder",folder)
                capture("$profile-files");val before=ui {reader().anchorOffset}
                shelf("more",b.id)
                verify("$profile: overflow targets the selected book without opening it",ui {reader().menuPage==MenuPage.FILE_ACTIONS&&reader().selectedFileId==b.id&&reader().libraryOpen&&reader().currentBookId==a.id&&reader().anchorOffset==before})
                verify("$profile: popup contains exactly two actions with no title or arrows",ui {val v=reader();val m=v.currentMenuModel;m.title.isEmpty()&&!m.showHeader&&!m.showArrows&&v.currentMenuLayout.headerHeight==0f&&m.rows.map {it.command?.action}==listOf(MenuAction.FILE_REPROCESS,MenuAction.FILE_DELETE)})
                capture("$profile-actions")
                ui {reader().backFromTools()};settle()
                verify("$profile: Back dismisses book actions directly to Files",ui {!reader().toolsOpen&&reader().libraryOpen})
                val oldBundle=BookFiles(targetContext).get(b.id)!!.bundle
                shelf("more",b.id);command(MenuAction.FILE_REPROCESS)
                waitFor("real saved-original reprocessing"){!LibraryWork.active&&ui {!reader().importBusy}&&BookFiles(targetContext).get(b.id)!!.bundle!=oldBundle};settle()
                verify("$profile: reprocessing stays in Files and preserves both reading anchors",ui {reader().libraryOpen&&reader().currentBookId==a.id&&reader().anchorOffset==before}&&library.reading(b).getInt("offset",-1)==130&&library.reading(b).getStringSet("bookmarks",emptySet())==setOf("130"))
                val checkedStore=BookFiles(targetContext);val checkedBook=checkedStore.get(b.id)!!;val quality=JSONObject(File(checkedStore.bundle(checkedBook),"quality.json").readText())
                verify("$profile: real reprocessing repairs the injected boundary and persists a successful multi-window audit",quality.getBoolean("passed")&&quality.getJSONObject("final").getJSONArray("profiles").length()>=3&&checkedStore.text(checkedBook).contains("\n\n作者：示例作者\n\n出版社：示例出版社"))
                shelf("more",b.id);command(MenuAction.FILE_DELETE);capture("$profile-confirm")
                verify("$profile: choosing Delete does not yet remove data",BookFiles(targetContext).get(b.id)!=null)
                command(MenuAction.FILE_CANCEL)
                verify("$profile: cancelling preserves the book and original",BookFiles(targetContext).get(b.id)!=null&&BookFiles.hashFile(sourceB)==originHash&&ui {!reader().toolsOpen})
            }
            // Delete a different book with a paused reflow journal; then verify all independent boundaries.
            LibraryWork.prepare(targetContext,JSONObject().put("kind","reflow").put("id",a.id));LibraryWork.discardDeletedBook(targetContext,b.id)
            verify("Another book's unfinished reflow is retained",JSONObject(LibraryWork.journal(targetContext).readText()).getString("id")==a.id)
            LibraryWork.prepare(targetContext,JSONObject().put("kind","reflow").put("id",b.id));LibraryWork.cancel(targetContext)
            val bundle=BookFiles(targetContext).let {it.bundle(it.get(b.id)!!)}
            shelf("more",b.id);command(MenuAction.FILE_DELETE);command(MenuAction.FILE_DELETE_CONFIRM)
            waitFor("deleted row"){BookFiles(targetContext).get(b.id)==null&&ui {!reader().importBusy}};settle()
            verify("Confirmed deletion removes index, original/assets and reading history",!bundle.exists()&&library.reading(b).all.isEmpty())
            verify("Source file and independent backup are byte-for-byte unchanged",BookFiles.hashFile(sourceB)==originHash&&BookFiles.hashFile(backup)==backupHash)
            verify("Deletion discards only the deleted book's paused reflow",!LibraryWork.pending(targetContext))
            verify("Other book and its anchor/bookmarks remain intact",BookFiles(targetContext).get(a.id)!=null&&library.reading(a).getInt("offset",-1)==121&&library.reading(a).getStringSet("bookmarks",emptySet())==setOf("121")&&ui {reader().currentBookId==a.id})
            activity?.let {ui {it.finish()};waitFor("restart cleanup"){it.isDestroyed}};launch();ui {reader().showFiles()};settle()
            verify("A fresh repository and restarted activity never resurrect the deleted book",BookFiles(targetContext).get(b.id)==null)
            verify("The deleted file can be imported again without old reading history",BookFiles(targetContext).import(Uri.fromFile(sourceB),folder)&&library.reading(b).all.isEmpty())
            ui {reader().showFiles()};settle();shelf("folder",folder);shelf("more",a.id);command(MenuAction.FILE_DELETE);command(MenuAction.FILE_DELETE_CONFIRM)
            waitFor("current book deleted"){BookFiles(targetContext).get(a.id)==null&&ui {!reader().importBusy}};settle()
            verify("Deleting the current book leaves Files with a valid fallback",ui {reader().libraryOpen&&reader().currentBookId!=a.id}&&targetContext.getSharedPreferences("files",0).getString("lastBook",null)!=a.id)
            val count=ui {val v=reader();val area=v.currentViewport!!;v.currentShelf.pageSize(area.width,area.height,v.english)}
            repeat(count) {n->val f=File(targetContext.cacheDir,"Shelf regression Page $n.txt").apply {writeText("第一章 分页测试 $n\n\nKeep every other book while removing the last row on the last file page. $n")}
                BookFiles(targetContext).let {store->store.import(Uri.fromFile(f),folder);ownIds+=store.list().first {it.name==f.name}.id}}
            ui {reader().showFiles()};settle()
            val last=BookFiles(targetContext).list().filter {it.folder==folder}.sortedBy {it.name.lowercase()}.last()
            shelf("more",last.id);command(MenuAction.FILE_DELETE);command(MenuAction.FILE_DELETE_CONFIRM)
            waitFor("last paged row deleted"){BookFiles(targetContext).get(last.id)==null&&ui {!reader().importBusy}};settle()
            verify("Deleting the last row of a file page returns to the preceding populated page",ui {reader().currentShelf.hitRegions.count {it.second.action=="book"}==count})
            ui {reader().showFiles()};settle();shelf("up");shelf("more","sample");command(MenuAction.FILE_DELETE);command(MenuAction.FILE_DELETE_CONFIRM)
            waitFor("sample hidden"){BookFiles(targetContext).get("sample")==null&&ui {!reader().importBusy}};settle()
            verify("The bundled sample can be removed and stays hidden on reload",BookFiles(targetContext).list().none {it.id=="sample"})
            capture("deleted-files")
        }catch(t:Throwable){failure=t.stackTraceToString();runCatching {capture("failure")}}
        finally {
            activity?.let {runCatching {ui {it.finish()}}}
            targetContext.getSharedPreferences("files",0).edit().putBoolean("sampleHidden",oldHidden).commit()
            BookFiles(targetContext).let {store->ownIds.forEach {store.delete(it)}}
            shell("settings put global animator_duration_scale $oldMotion")
            File(out,"file-actions-report.json").writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("roundCount",rounds).put("error",failure).toString(2))
        }
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${checks.length()} file action checks and $rounds raster conditions passed\n")})
    }
}
