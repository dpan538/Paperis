package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Independent regressions for controls, stretched spaces and already-flattened old books. */
internal class ReadingRevisionEvidence(private val host:Instrumentation) {
    fun run()=with(host) {
        val out=File(targetContext.getExternalFilesDir(null),"reading-revision").also {it.mkdirs()}
        val checks=JSONArray();var failure:String?=null
        fun <T> ui(f:()->T):T {val r=AtomicReference<Result<T>>();runOnMainSync {r.set(runCatching(f))};return r.get().getOrThrow()}
        fun waitFor(name:String,f:()->Boolean){val end=SystemClock.uptimeMillis()+90000;while(SystemClock.uptimeMillis()<end){if(f())return;SystemClock.sleep(70)};error("Timeout: $name")}
        fun verify(name:String,value:Boolean){checks.put(JSONObject().put("check",name).put("passed",value));check(value){name}}
        lateinit var activity:MainActivity
        fun settle(){waitFor("settled ink"){ui {!activity.inkView.isRefreshing}};SystemClock.sleep(200)}
        fun actions()=ui {activity.inkView.createAccessibilityNodeInfo().actionList.map {it.label?.toString().orEmpty()}}
        fun click(label:String){ui {val node=activity.inkView.createAccessibilityNodeInfo();val action=node.actionList.single {it.label?.toString()==label};check(activity.inkView.performAccessibilityAction(action.id,null))};settle()}
        fun capture(name:String):Bitmap {settle();return checkNotNull(uiAutomation.takeScreenshot()).also {b->File(out,"$name.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}}}
        try {
            // This suite uses a disposable test library, independent of a previous paused test job.
            LibraryWork.journal(targetContext).delete()
            targetContext.getSharedPreferences("reading",0).edit().clear().commit()
            targetContext.getSharedPreferences("files",0).edit().putString("lastBook","sample").putBoolean("english",false).putInt("warmth",2).putBoolean("night",false).putBoolean("dark",false).putBoolean("brightnessSet",false).commit()
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            waitFor("window"){ui {activity.inkView.pageCount>0}}
            ui {val v=activity.inkView;v.openBook(v.files.demo,v.files.text(v.files.demo));v.openMenu(MenuPage.ROOT)};settle()
            val order=listOf("文件","目录与进度","＋ 导入书籍","阅读排版","阅读预设","屏幕与刷新","书签","设置")
            val labels=actions().map {if(it.startsWith("书签 ·"))"书签" else it}
            verify("Root menu follows the reading workflow and keeps light/polarity out of the root: $labels",labels.filter {it in order}==order&&labels.none {it.startsWith("夜间模式")||it.startsWith("深色模式")})
            val root=ui {activity.inkView.menuBounds};click("设置")
            verify("Settings keeps the exact root panel dimensions",ui {activity.inkView.menuBounds==root})
            click("返回阅读菜单");click("阅读预设");capture("01-reading-presets").recycle()
            verify("Presets combines three surfaces, five tones, three brightness levels, night and dark",actions().count {it.startsWith("冷暖 ")}==5&&actions().containsAll(listOf("屏幕亮度 低","屏幕亮度 中","屏幕亮度 高","夜间模式 关闭","深色模式 关闭")))
            val tones=mutableListOf<Int>()
            for((step,name) in listOf("冷","微冷","中性","微暖","暖").withIndex()) {
                click("冷暖 $name");ui {activity.inkView.toggleTools()};val bitmap=capture("tone-$step")
                val color=bitmap.getPixel(1704,230);tones+=Color.red(color)-Color.blue(color);bitmap.recycle()
                verify("Tone $step is selected and persisted",ui {activity.inkView.warmthStep==step}&&targetContext.getSharedPreferences("files",0).getInt("warmth",-1)==step)
                ui {activity.inkView.openMenu(MenuPage.PRESETS)};settle()
            }
            verify("The five paper tones progress visibly from cool to warm",tones.zipWithNext().all {(a,b)->b>a})
            for((step,name) in listOf("低","中","高").withIndex()) {
                click("屏幕亮度 $name")
                verify("Brightness $step changes the actual app window without system settings permission",ui {activity.inkView.brightnessOverridden&&kotlin.math.abs(activity.window.attributes.screenBrightness-floatArrayOf(.28f,.60f,1f)[step])<.001f})
            }
            click("屏幕亮度 系统")
            verify("System brightness can be restored",ui {!activity.inkView.brightnessOverridden&&activity.window.attributes.screenBrightness==-1f})
            click("冷暖 中性");click("夜间模式 关闭");click("深色模式 关闭")
            verify("Night light and dark polarity remain independent inside Presets",ui {activity.inkView.nightEnabled&&activity.inkView.darkEnabled})
            click("夜间模式 开启");click("深色模式 开启");ui {activity.inkView.toggleTools()};settle()
            waitFor("hidden navigation edge"){ui {activity.inkView.currentViewport!!.bottom==activity.inkView.currentViewport!!.left}}
            verify("Bottom perimeter equals the sides while the top keeps status-bar space",ui {val v=activity.inkView.currentViewport!!;v.bottom==v.left&&v.right==v.left&&v.top>v.left})
            val prose="1884 年，一位读者打开一本书，开始阅读。这句话含有日期与中英文混排。\n\nA reader walks beneath the trees and turns another page with a quiet smile."
            val boundary=prose.indexOf("\n\n")
            val layout=BookLayout(prose,1000f,700f,30f,structure=BookStructure(listOf(BookBlock(0,boundary),BookBlock(boundary+2,prose.length)),emptyList()))
            val measured=TextDrawProbe.capture(layout.layout){layout.drawNativeText(it)}
            val widths=measured.filter {it.observed}.map {it.width}
            verify("Dates and English words never receive oversized justified spaces",widths.isNotEmpty()&&widths.all {it<=30f*.65f})
            val body="This independently written paragraph preserves its words and original meaning. ".repeat(3)
            val legacy="Legacy sample\n\n版权信息示例书坊 书名作　者：示例作者\n\n出版社：示例出版社\n\nDedication\n\n$body\n\nSample-final.html A Note on Natural History Natural history begins with patient reading.\n\nSample-final.html\n\nCHAPTER I\n\n$body\n\n第二部 远方\n\n13. 一条路...................(45)\n\n14. 一座山...................(50)\n\n第一章 一条路\n\n$body"
            val files=activity.inkView.files;val id=BookFiles.hashBytes("authored-old-reading-regression".toByteArray())
            var entry=files.commit(id,"legacy-regression.epub","",ParsedBook("Legacy sample",legacy,"EPUB"),null)
            val metadata=File(files.bundle(entry),"structure.json");val old=JSONObject(metadata.readText()).put("revision",3);metadata.writeText(old.toString())
            ui {activity.inkView.openBook(entry,legacy)}
            waitFor("old text repaired"){!LibraryWork.active&&BookFiles(targetContext).let {f->f.get(id)?.let {!f.needsRepair(it)}==true}}
            waitFor("reader resumes"){ui {!activity.inkView.libraryOpen&&!activity.inkView.importBusy}};settle()
            files.reload();entry=checkNotNull(files.get(id));val text=files.text(entry);val structure=files.structure(entry,text)
            verify("An already-flattened copyright restores complete separate author and publisher fields",structure.blocks.any {it.kind=="metadata"&&text.substring(it.start,it.end)=="作者：示例作者"}&&text.contains("版权信息\n\n示例书坊 书名\n\n作者：示例作者"))
            verify("Old HTML filenames disappear and collapsed front matter regains its own heading",!text.contains(".html")&&structure.blocks.any {it.kind=="heading"&&text.substring(it.start,it.end)=="A Note on Natural History"}&&text.contains("Natural history begins with patient reading."))
            verify("Old printed contents is removed without discarding the real body",!text.contains("(45)")&&!text.contains("(50)")&&text.contains(body.trim())&&text.contains("第一章 一条路"))
            capture("02-repaired-legacy").recycle()
            val first="清晨醒来时，山还隐在窗外的薄雾中，屋子里很静，只有远处的水声"
            val second="一阵近，一阵远。";val next="第二个段落保持独立，不能因前段有软换行就被合并。"
            val wrapped="Legacy PDF\n\n第一章 清晨\n\n$first\n$second\n\n$next"
            val pdfId=BookFiles.hashBytes("authored-legacy-soft-wrap".toByteArray())
            files.commit(pdfId,"old.pdf","",ParsedBook("Legacy PDF",wrapped,"PDF"),null);files.reprocess(pdfId)
            val pdfText=files.text(checkNotNull(files.get(pdfId)))
            verify("Text-only PDF repairs local soft wraps while keeping the next paragraph separate",pdfText.contains(first+second)&&pdfText.contains("\n\n"+next))
            val poemId=BookFiles.hashBytes("authored-legacy-refrain".toByteArray())
            val poem="Poem\n\nNight Falls\nNight Falls\n\nAnother paragraph remains separate."
            files.commit(poemId,"poem.txt","",ParsedBook("Poem",poem,"TXT"),null);files.reprocess(poemId)
            verify("A different legacy format keeps repeated poetic hard lines instead of applying PDF repair",files.text(checkNotNull(files.get(poemId))).contains("Night Falls\nNight Falls"))
            ui {activity.finish()}
        }catch(t:Throwable){failure=t.stackTraceToString()}
        File(out,"reading-revision-report.json").writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("error",failure).toString(2))
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${checks.length()} reading preset, spacing and legacy text checks passed\n")})
    }
}
