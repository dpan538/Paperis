package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Repeated real-reader reflows must keep a semantic source location, not a page index or new page top. */
internal class AnchorEvidence(private val host:Instrumentation) {
    fun run()=with(host) {
        val out=File(targetContext.getExternalFilesDir(null),"anchor-verification").also {it.mkdirs()}
        val checks=JSONArray();var changes=0;var failure:String?=null
        lateinit var activity:MainActivity
        fun <T> ui(f:()->T):T {val r=AtomicReference<Result<T>>();runOnMainSync {r.set(runCatching(f))};return r.get().getOrThrow()}
        fun shell(value:String){android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(value)).use {it.readBytes()}}
        fun waitFor(name:String,f:()->Boolean){val end=SystemClock.uptimeMillis()+60000;while(SystemClock.uptimeMillis()<end){if(f())return;SystemClock.sleep(60)};error("Timeout: $name")}
        fun verify(name:String,yes:Boolean){checks.put(JSONObject().put("check",name).put("passed",yes));check(yes){name}}
        fun contains(anchor:Int)=ui {val v=activity.inkView;val p=v.currentBookLayout.pages[v.currentPage];v.anchorOffset==anchor&&v.currentOffset==anchor&&anchor in p.first().start until p.last().end}
        fun start():MainActivity {
            val a=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            waitFor("reader window"){ui {a.inkView.pageCount>0}};return a
        }
        try {
            shell("wm size 3408x2272");shell("wm density 320");shell("settings put system font_scale 1.0")
            val files=BookFiles(targetContext)
            val input=File(targetContext.getExternalFilesDir(null),"import-samples")
            val sources=input.listFiles().orEmpty().filter {it.extension=="epub"}.sortedBy {it.name}.mapNotNull {f->files.get(BookFiles.hashFile(f))?.let {it to files.text(it)}}
            val latin=checkNotNull(sources.firstOrNull {it.second.take(2000).count {c->c in 'a'..'z'||c in 'A'..'Z'}>600}) {"Provide a private Latin-language EPUB corpus"}
            val cjk=checkNotNull(sources.firstOrNull {it.second.take(2000).count {c->c.code in 0x3400..0x9fff}>200}) {"Provide a private CJK EPUB corpus"}
            activity=start()
            for((name,source) in listOf("Latin" to latin,"CJK" to cjk)) {
                val (entry,text)=source;val structure=files.structure(entry,text)
                ui {activity.inkView.openBook(entry,text);activity.inkView.changeFont(-100);activity.inkView.menuCommand(MenuCommand(MenuAction.FONT_FACE,0));activity.inkView.menuCommand(MenuCommand(MenuAction.SPACING,1));activity.inkView.menuCommand(MenuCommand(MenuAction.COLUMNS,0))}
                var lastAnchor=0;var lastPage=0
                for(fraction in 1..3) {
                    val at=text.length*fraction/4
                    val anchor=(structure.blocks.firstOrNull {it.start>=at&&it.kind=="body"&&it.end-it.start>=80}?:structure.blocks.last {it.kind=="body"}).start
                    ui {activity.inkView.jumpToOffset(anchor)}
                    val baseline=ui {activity.inkView.currentPage};lastAnchor=anchor;lastPage=baseline
                    repeat(3) {
                        for(delta in listOf(1,1,1,1,-1,-1,-1,-1)) {ui {activity.inkView.changeFont(delta)};changes++;check(contains(anchor)){"$name font sequence drifted from the original sentence"}}
                        for(command in listOf(MenuCommand(MenuAction.FONT_FACE,1),MenuCommand(MenuAction.FONT_FACE,0),MenuCommand(MenuAction.SPACING,0),MenuCommand(MenuAction.SPACING,2),MenuCommand(MenuAction.SPACING,1),MenuCommand(MenuAction.COLUMNS,1),MenuCommand(MenuAction.COLUMNS,2),MenuCommand(MenuAction.COLUMNS,0))) {
                            ui {activity.inkView.menuCommand(command)};changes++;check(contains(anchor)){"$name layout change lost the source paragraph"}
                        }
                        check(ui {activity.inkView.currentPage==baseline}) {"$name round trip returned to a different paragraph/page"}
                    }
                    verify("$name source location $fraction survives repeated font, face, spacing and column round trips",contains(anchor)&&ui {activity.inkView.currentPage==baseline})
                }
                shell("wm size 2272x3408")
                waitFor("portrait"){ui {activity.inkView.width==2272&&activity.inkView.height==3408}}
                verify("$name keeps its source sentence across portrait reflow",contains(lastAnchor))
                shell("settings put system font_scale 1.6")
                waitFor("system font size"){ui {activity.inkView.currentViewport!!.fontScale>1.59f}}
                verify("$name keeps its source sentence across Android font scaling",contains(lastAnchor))
                shell("settings put system font_scale 1.0");shell("wm size 3408x2272")
                waitFor("landscape restored"){ui {activity.inkView.width==3408&&activity.inkView.height==2272&&activity.inkView.currentViewport!!.fontScale<1.01f}}
                verify("$name returns to the original landscape content after rotation and scaling",contains(lastAnchor)&&ui {activity.inkView.currentPage==lastPage})
                waitFor("settled content"){ui {!activity.inkView.isRefreshing}};SystemClock.sleep(200)
                uiAutomation.takeScreenshot()?.let {b->File(out,"$name-restored.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
                ui {activity.inkView.saveReadingState();activity.finish()};activity=start()
                verify("$name persists the same semantic reading anchor after reopening",ui {activity.inkView.currentBookId==entry.id}&&contains(lastAnchor))
            }
            ui {activity.finish()}
        }catch(t:Throwable){failure=t.stackTraceToString()}
        finally {
            shell("settings put system font_scale 1.0");shell("wm size 3408x2272");shell("wm density 320")
            File(out,"anchor-report.json").writeText(JSONObject().put("passed",failure==null).put("changes",changes).put("checks",checks).put("error",failure).toString(2))
            finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${checks.length()} semantic reading-anchor checks passed across $changes layout changes\n")})
        }
    }
}
