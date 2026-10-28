package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Native 300-case layout matrix plus independent real WindowManager interaction coverage. */
internal class ResponsiveEvidence(private val host:Instrumentation,private val arguments:Bundle) {
    private data class Profile(val name:String,val w:Int,val h:Int,val dpi:Int)
    private val profiles=listOf(
        Profile("phone-320",320,640,480),Profile("phone-360",360,800,480),Profile("phone-393",393,873,440),
        Profile("phone-412",412,915,420),Profile("phone-440",440,956,480),Profile("split-480",480,800,320),
        Profile("phone-landscape",800,360,480),Profile("content-height-479",720,527,320),Profile("content-height-480",720,528,320),
        Profile("content-width-599",623,960,320),Profile("content-width-600",624,960,320),Profile("small-pad",720,1100,320),
        Profile("medium-pad",800,1280,320),Profile("content-width-839",863,1100,320),Profile("content-width-840",864,1100,320),
        Profile("fold-square",720,800,320),Profile("fold-wide",840,720,320),Profile("pad-landscape",1024,768,320),
        Profile("content-width-1199",1223,900,320),Profile("content-width-1200",1224,900,320),Profile("large-pad",1366,1024,320),
        Profile("content-width-1599",1623,1100,320),Profile("content-width-1600",1624,1100,320),Profile("pad-reference",1704,1136,320),
        Profile("xiaomi-pad-split",600,400,320))
    private fun <T> ui(f:()->T):T {val r=AtomicReference<Result<T>>();host.runOnMainSync {r.set(runCatching(f))};return r.get().getOrThrow()}
    private fun shell(value:String):String=android.os.ParcelFileDescriptor.AutoCloseInputStream(host.uiAutomation.executeShellCommand(value)).use {it.bufferedReader().readText()}
    private fun waitFor(name:String,f:()->Boolean) {val end=SystemClock.uptimeMillis()+45000;while(SystemClock.uptimeMillis()<end){if(f())return;SystemClock.sleep(70)};error("Timeout: $name")}
    private fun fixture(english:Boolean,illustrated:Boolean):String {
        val title=if(english)"CHAPTER I — A Reader at the Window" else "第一章　窗边的阅读者"
        val paragraph=if(english)"A reader opened the book beside a quiet window. Spaces remain between English words, and punctuation stays with its sentence. A long name such as extraordinarycircumstances still fits the column."
            else "清晨，读者在窗边打开一本书。屏幕应当保留清晰的段落、自然的换行与标点，调整大小以后仍能找到原来的那句话。中英文混排 Paperis 2026 也应保持顺序，不丢失任何文字。"
        return title+"\n\n"+(0..20).joinToString("\n\n") { i->
            if(illustrated&&i==5)"\uFFFC" else if(i==12)if(english)"A Note on Reading Names" else "阅读札记" else "$paragraph ${i+1}. $paragraph"
        }
    }
    private fun structure(text:String):BookStructure {
        val inferred=BookStructure.infer(text)
        return inferred.copy(blocks=inferred.blocks.map {if(text.substring(it.start,it.end)=="\uFFFC")it.copy(kind="image",image="picture") else it})
    }
    private fun menu(english:Boolean):MenuModel {
        fun t(a:String,b:String)=if(english)b else a
        fun controls(names:List<String>,action:MenuAction)=names.mapIndexed {i,s->MenuControl(s,MenuCommand(action,i),i==2)}
        return MenuModel(t("阅读预设","Reading presets"),true,listOf(
            MenuRow(t("白瓷","Porcelain"),controls=controls(listOf(t("选择","Choose")),MenuAction.PRESET)),
            MenuRow(t("冷暖","Tone"),controls=controls(if(english)listOf("Cool","Cooler","Neutral","Warmer","Warm")else listOf("冷","微冷","中性","微暖","暖"),MenuAction.WARMTH),controlsWidth=300f),
            MenuRow(t("屏幕亮度","Brightness"),controls=controls(if(english)listOf("Low","Mid","High","Auto")else listOf("低","中","高","系统"),MenuAction.LIGHT)),
            MenuRow(t("对比度","Contrast"),controls=controls(if(english)listOf("Low","Mid","High")else listOf("低","中","高"),MenuAction.CONTRAST)),
            MenuRow(t("夜间模式","Night light"),controls=controls(listOf(t("开启","On")),MenuAction.NIGHT)),
            MenuRow(t("深色模式","Dark mode"),controls=controls(listOf(t("关闭","Off")),MenuAction.DARK)),
            MenuRow(t("语言","Language"),controls=controls(listOf("中文","English"),MenuAction.LANGUAGE)),
            MenuRow(t("第 23 页","Page 23"),MenuCommand(MenuAction.BOOKMARK_GO,23),controls(listOf(t("移除","Remove")),MenuAction.BOOKMARK_REMOVE),84f)),8)
    }
    private fun menuFaults(m:ReadingMenu,model:MenuModel):List<String> = buildList {
        val expected=model.rows.flatMap {listOf(it.label).filter(String::isNotEmpty)+it.controls.map {c->c.text}}
        val labels=m.textRegions.filter {it.value!="›"}
        if(labels.map {it.value}!=expected)add("lost/reordered label")
        labels.forEach {r->
            if(r.bounds.left<m.bounds.left||r.bounds.right>m.bounds.right||r.bounds.top<m.bounds.top+m.headerHeight)add("text outside panel: ${r.value}")
            for(line in 0 until r.layout.lineCount)if(r.layout.getEllipsisCount(line)>0||r.layout.getLineRight(line)>r.layout.width+.6f||r.layout.getLineLeft(line)<-.6f)add("text clipped: ${r.value}")
        }
        val hits=m.hitRegions.filter {it.command!=null}
        hits.forEach {if(it.bounds.width()<m.minimumTouchSize-.1f||it.bounds.height()<m.minimumTouchSize-.1f||it.bounds.left<m.bounds.left||it.bounds.right>m.bounds.right)add("small/outside touch target: ${it.command}")}
        for(i in hits.indices)for(j in i+1 until hits.size)if(RectF.intersects(hits[i].bounds,hits[j].bounds))add("overlapping commands")
    }
    private fun coverage(text:String,slices:List<BookLayout.Column>):Boolean = slices.isNotEmpty()&&slices.first().start==0&&slices.last().end==text.length&&
        slices.zipWithNext().all {(a,b)->a.end==b.start}&&slices.joinToString(""){text.substring(it.start,it.end)}==text

    fun runMatrix()=with(host) {
        val out=File(targetContext.getExternalFilesDir(null),"responsive300-verification").also {it.mkdirs()}
        val checks=JSONArray();var failure:String?=null;var context=JSONObject();var rounds=0
        val picture=Bitmap.createBitmap(160,100,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.LTGRAY);Canvas(this).drawRect(20f,20f,140f,80f,android.graphics.Paint().apply {color=Color.DKGRAY})}
        fun verify(name:String,value:Boolean){checks.put(JSONObject().put("check",name).put("passed",value));check(value){name}}
        try {
            var id=0
            for((profileIndex,p) in profiles.withIndex())for(scale in listOf(1f,1.3f,2f))for(english in listOf(false,true))for(illustrated in listOf(false,true)) {
                val selected=id++;if(arguments.containsKey("case")&&selected!=arguments.getString("case")!!.toInt())continue
                context=JSONObject().put("case",selected).put("profile",profileIndex).put("name",p.name).put("windowDp",JSONArray(listOf(p.w,p.h))).put("densityDpi",p.dpi).put("fontScale",scale).put("english",english).put("illustrated",illustrated)
                val config=Configuration(targetContext.resources.configuration).apply {fontScale=scale;densityDpi=p.dpi}
                val metrics=targetContext.createConfigurationContext(config).resources.displayMetrics
                fun sp(size:Float)=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,size,metrics)/metrics.density
                val w=p.w-24f;val h=p.h-48f;val sizing=ReaderSizing(w,h)
                val text=fixture(english,illustrated);val book=BookLayout(text,w,h,sp(sizing.fontSizes[1]),structure=structure(text),imageProvider={picture},imageSize={160 to 100})
                val slices=book.pages.flatten()
                try {
                    check(coverage(text,slices)){"Source coverage changed"}
                    check(book.bodyHeight>0&&slices.all {book.layout.getLineBottom(it.lastLine)-book.layout.getLineTop(it.firstLine)<=book.bodyHeight+.5f}){"Vertical overflow"}
                    for(line in 0 until book.layout.lineCount)check(book.layout.getLineLeft(line)>=-.5f&&book.layout.getLineRight(line)<=book.columnWidth+.5f){"Horizontal overflow at line $line"}
                    check(!sizing.phone||book.columns==1){"Compact window became two columns"}
                    for(anchor in listOf(0,text.length/3,text.length*2/3,text.lastIndex))check(book.pages[book.pageForOffset(anchor)].let {anchor in it.first().start until it.last().end}){"Source anchor not visible"}
                    val model=menu(english);val panel=ReadingMenu(::sp);panel.place(w,h,model)
                    check(menuFaults(panel,model).isEmpty()){menuFaults(panel,model).joinToString()}
                    check(panel.bounds.left>=0&&panel.bounds.bottom<=h+.1f){"Panel outside safe window"}
                    val shelf=FileShelf(::sp);val g=shelf.geometry(w,h,english=english)
                    check(listOf(g.importBounds,g.previous,g.next).all {it.left>=0&&it.top>=0&&it.right<=w&&it.bottom<=h}){"Shelf action leaves safe viewport"}
                    check(!RectF.intersects(g.previous,g.next)&&g.previous.width()>=(if(sizing.phone)35.99f else 47.99f)&&g.next.width()>=(if(sizing.phone)35.99f else 47.99f)){"Shelf paging targets overlap"}
                    check(g.listTop+g.pageSize*g.rowHeight<=g.previous.top-3f){"Shelf row overlaps footer"}
                    val raster=Bitmap.createBitmap(w.toInt(),h.toInt(),Bitmap.Config.ARGB_8888)
                    val canvas=Canvas(raster);canvas.drawColor(Color.WHITE);book.draw(canvas,book.pageForOffset(text.length/3))
                    check((book.bodyTop.toInt() until book.bodyBottom.toInt() step 4).any {y->(book.margin.toInt() until (book.margin+book.columnWidth).toInt() step 4).any {x->Color.red(raster.getPixel(x,y))<160}}){"Blank raster at source anchor"}
                    if(selected in setOf(0,1,4,6,7,12,18,19,24,25,30,72,73,78,79,216,217,282,283))File(out,"case-$selected.png").outputStream().use {raster.compress(Bitmap.CompressFormat.PNG,100,it)}
                    raster.recycle()
                    if(selected==0) {
                        verify("Coverage detector rejects a missing final source slice",!coverage(text,slices.dropLast(1)))
                        panel.textRegions.first().bounds.offset(-w,0f)
                        verify("Menu detector rejects the hidden-label fault",menuFaults(panel,model).isNotEmpty())
                        panel.place(w,h,model);panel.hitRegions.first {it.command!=null}.bounds.apply {right=left+12f}
                        verify("Menu detector rejects an undersized-touch fault",menuFaults(panel,model).isNotEmpty())
                    }
                    checks.put(JSONObject().put("case",selected).put("passed",true).put("pages",book.pages.size).put("fontSp",sizing.fontSizes[1]).put("columns",book.columns));rounds++
                }catch(t:Throwable){File(out,"failed-case-$selected.txt").writeText(text);throw t}
            }
            verify("Every requested synthetic condition executed",rounds==if(arguments.containsKey("case"))1 else 300)
        }catch(t:Throwable){failure=t.stackTraceToString()}
        finally {
            picture.recycle()
            File(out,"responsive300-report.json").writeText(JSONObject().put("passed",failure==null).put("roundCount",rounds).put("checks",checks).put("failureContext",context).put("error",failure).toString(2))
            finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"$rounds native responsive combinations passed\n")})
        }
    }

    fun runWindows()=with(host) {
        val out=File(targetContext.getExternalFilesDir(null),"responsive-window-verification").also {it.mkdirs()}
        val checks=JSONArray();var failure:String?=null;var context=JSONObject()
        lateinit var activity:MainActivity
        val previousIme=shell("settings get secure show_ime_with_hard_keyboard").trim()
        val previousAccessibilityFlags=uiAutomation.serviceInfo.flags
        var windowCount=0
        fun verify(name:String,value:Boolean){checks.put(JSONObject().put("check",name).put("passed",value));check(value){name}}
        fun settle(){waitFor("settled ink"){ui {!activity.inkView.isRefreshing}};waitForIdleSync();SystemClock.sleep(160)}
        fun capture(name:String):Bitmap {
            val frame=CountDownLatch(1);ui {activity.inkView.viewTreeObserver.registerFrameCommitCallback {frame.countDown()};activity.inkView.invalidate()}
            check(frame.await(15,TimeUnit.SECONDS));return checkNotNull(uiAutomation.takeScreenshot()).also {b->File(out,"$name.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)}}
        }
        fun event(action:Int,x:Float,y:Float,whenAt:Long,down:Long) {val e=MotionEvent.obtain(down,whenAt,action,x,y,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;uiAutomation.injectInputEvent(e,true);e.recycle()}
        fun tap(x:Float,y:Float){val now=SystemClock.uptimeMillis();event(MotionEvent.ACTION_DOWN,x,y,now,now);event(MotionEvent.ACTION_UP,x,y,now+50,now);settle()}
        fun swipe(up:Boolean) {
            val pair=ui {val m=activity.inkView.currentMenuLayout;val x=m.bounds.centerX();val a=activity.inkView.screenPoint(x,m.bounds.bottom-20);val z=activity.inkView.screenPoint(x,m.bounds.top+m.headerHeight+20);if(up)a to z else z to a}
            val now=SystemClock.uptimeMillis();for(i in 0..12)event(if(i==0)MotionEvent.ACTION_DOWN else if(i==12)MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE,pair.first.x+(pair.second.x-pair.first.x)*i/12,pair.first.y+(pair.second.y-pair.first.y)*i/12,now+i*25L,now);settle()
        }
        fun touch(action:MenuAction) {
            repeat(15) {
                val target=ui {
                    val v=activity.inkView;val m=v.currentMenuLayout;val region=m.hitRegions.first {it.command?.action==action}.bounds
                    val y=region.centerY()-v.menuScrollOffset
                    if(y>=m.bounds.top+m.headerHeight+8&&y<m.bounds.bottom-8)v.screenPoint(region.centerX(),y) else null
                }
                if(target!=null){tap(target.x,target.y);return}
                swipe(true)
            };error("Could not reveal and touch $action")
        }
        fun find(description:String):AccessibilityNodeInfo? {
            fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {if(n==null)return null;if(n.contentDescription?.toString()==description)return n;for(i in 0 until n.childCount)visit(n.getChild(i))?.let {return it};return null}
            return visit(uiAutomation.rootInActiveWindow)
        }
        try {
            if(arguments.containsKey("case"))require(arguments.getString("case")!!.toInt() in 0..11){"Window case must be in 0..11"}
            shell("wm user-rotation lock 0");shell("settings put system font_scale 1.0")
            LibraryWork.journal(targetContext).delete()
            targetContext.getSharedPreferences("files",0).edit().putString("lastBook","sample").putBoolean("english",false).putBoolean("dark",false).putBoolean("night",false).commit()
            targetContext.getSharedPreferences("reading",0).edit().clear().putBoolean("optical",false).commit()
            val library=BookFiles(targetContext)
            for((index,name) in listOf("A reader's notebook with a deliberately long descriptive title.txt","手机阅读的长书名测试：段落、章节与图片的排列.txt","平板横屏与竖屏的阅读位置测试.txt").withIndex()) {
                val source=File(targetContext.cacheDir,name);source.writeText(fixture(index==0,false)+"\n\nLibrary fixture $index")
                library.import(android.net.Uri.fromFile(source))
            }
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            waitFor("reader"){ui {activity.inkView.pageCount>0}};settle()
            val text=fixture(false,false)+"\n\n"+fixture(true,false)
            ui {val v=activity.inkView;v.openBook(v.files.demo,text);v.jumpToOffset(text.length/3)};settle()
            val anchor=ui {activity.inkView.anchorOffset}
            val windows=listOf(
                Profile("narrow-phone",960,1920,480),Profile("mainstream-phone",1080,2400,480),Profile("xiaomi-phone-sample",1200,2670,480),
                Profile("high-resolution-phone",1440,3200,560),Profile("phone-landscape",2400,1080,480),
                Profile("small-pad",1200,1920,320),Profile("pad-11",2560,1600,320),Profile("xiaomi-pad-pixel-sample",3200,2136,360),
                Profile("pad-reference",3408,2272,320),Profile("pad-portrait",2272,3408,320),Profile("split-short",1800,1200,480),Profile("large-type-phone",1080,2400,480))
            for((index,p) in windows.withIndex()) {
                if(arguments.containsKey("case")&&arguments.getString("case")!!.toInt()!=index)continue
                val scale=if(index==11)2f else if(index==1)1.3f else 1f
                context=JSONObject().put("case",index).put("profile",index).put("name",p.name).put("px",JSONArray(listOf(p.w,p.h))).put("dpi",p.dpi).put("fontScale",scale)
                shell("wm size ${p.w}x${p.h}");shell("wm density ${p.dpi}");shell("settings put system font_scale $scale")
                waitFor("window ${p.name}"){ui {val v=activity.inkView;v.width==p.w&&v.height==p.h&&abs((v.currentViewport?.density?:0f)-p.dpi/160f)<.01f&&abs((v.currentViewport?.fontScale?:0f)-scale)<.01f}};settle()
                ui {activity.window.insetsController?.show(WindowInsets.Type.statusBars());if(index==0)activity.window.insetsController?.show(WindowInsets.Type.navigationBars()) else activity.window.insetsController?.hide(WindowInsets.Type.navigationBars())};settle()
                var previous:ReaderViewport?=null;var stableSince=SystemClock.uptimeMillis()
                waitFor("system inset animation ${p.name}") {
                    val area=ui {activity.inkView.currentViewport}
                    if(area!=previous){previous=area;stableSince=SystemClock.uptimeMillis()}
                    SystemClock.uptimeMillis()-stableSince>=400
                }
                ui {
                    val v=activity.inkView;val area=v.currentViewport!!;val b=v.currentBookLayout
                    val safe=v.rootWindowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    context.put("reportedInsets",JSONArray(listOf(safe.left,safe.top,safe.right,safe.bottom))).put("cutout",v.rootWindowInsets.displayCutout?.toString()?:"none")
                    verify("${p.name}: safe bounds include all reported system edges",area.left>=safe.left&&area.top>=safe.top&&area.right>=safe.right&&area.bottom>=safe.bottom)
                    verify("${p.name}: exact source anchor survives window and SP changes",v.anchorOffset==anchor&&b.pages[v.currentPage].let {anchor in it.first().start until it.last().end})
                    verify("${p.name}: source coverage remains contiguous",coverage(text,b.pages.flatten()))
                    verify("${p.name}: columns fit actual height",b.pages.flatten().all {b.layout.getLineBottom(it.lastLine)-b.layout.getLineTop(it.firstLine)<=b.bodyHeight+.5f})
                    verify("${p.name}: compact windows remain single-column",!ReaderSizing(area.width,area.height).phone||b.columns==1)
                    val expected=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,v.baseFontSize,v.resources.displayMetrics)/area.density
                    verify("${p.name}: text uses native SP conversion",abs(expected-b.font)<.01f)
                    v.menuCommand(MenuCommand(MenuAction.LANGUAGE,index%2))
                };settle()
                val menuPoint=ui {activity.inkView.menuButtonPoint};tap(menuPoint.x,menuPoint.y)
                verify("${p.name}: the visible ellipsis opens the reading menu",ui {activity.inkView.toolsOpen&&activity.inkView.menuPage==MenuPage.ROOT})
                val root=ui {RectF(activity.inkView.currentMenuLayout.bounds)}
                touch(MenuAction.SETTINGS)
                val settings=ui {activity.inkView.menuPage to RectF(activity.inkView.currentMenuLayout.bounds)}
                verify("${p.name}: Settings opens by visible touch and matches root dimensions ($root -> $settings)",settings.first==MenuPage.SETTINGS&&settings.second==root)
                for(page in MenuPage.entries) {
                    ui {activity.inkView.openMenu(page)};settle()
                    ui {val v=activity.inkView;val m=v.currentMenuLayout;verify("${p.name}/$page: all labels and touch regions fit",menuFaults(m,v.currentMenuModel).isEmpty());verify("${p.name}/$page: panel stays inside safe viewport",m.bounds.left>=0&&m.bounds.top>=0&&m.bounds.right<=v.currentViewport!!.width&&m.bounds.bottom<=v.currentViewport!!.height)}
                }
                ui {activity.inkView.openMenu(MenuPage.PRESETS)};settle()
                if(index==0)ui {
                    val m=activity.inkView.currentMenuLayout
                    val tone=m.hitRegions.filter {it.command?.action==MenuAction.WARMTH}
                    verify("Phone presets retain compact grouped controls",m.bounds.width()<=288.1f&&m.bounds.height()<=400f)
                    verify("Five phone tone choices fit on one line",tone.size==5&&tone.map {it.bounds.centerY()}.distinct().size==1)
                }
                if(index in setOf(0,1,4,7,8,11))capture("$index-presets").recycle()
                ui {activity.inkView.toggleTools();activity.inkView.showFiles()};settle()
                ui {
                    val v=activity.inkView;val hits=v.currentShelf.hitRegions
                    verify("${p.name}: Files retains a direct import action",hits.any {it.second.action=="import"})
                    verify("${p.name}: library actions do not overlap",hits.indices.all {i->(i+1 until hits.size).none {j->RectF.intersects(hits[i].first,hits[j].first)}})
                    verify("${p.name}: library touch targets remain inside safe viewport",hits.all {it.first.left>=0&&it.first.right<=v.currentViewport!!.width&&it.first.top>=0&&it.first.bottom<=v.currentViewport!!.height})
                    verify("${p.name}: book titles do not collide with metadata",v.currentShelf.titleRegions.all {it.bounds.bottom<=it.metadataTop+.5f})
                    if(index==0) {
                        val g=v.currentShelf.geometry(v.currentViewport!!.width,v.currentViewport!!.height)
                        verify("Phone import stays a small header action",g.importBounds.width()<=120f&&g.importBounds.height()<=44f&&g.importBounds.bottom<g.listTop)
                        verify("Phone library rows avoid tablet-sized spacing",g.rowHeight<=96f)
                    }
                }
                if(index in setOf(0,1,4,8,11))capture("$index-files").recycle()
                ui {activity.inkView.menuCommand(MenuCommand(MenuAction.FILES))};settle()
                if(index in setOf(0,1,4,7,8,11))capture("$index-reading").recycle()
                val originalPage=ui {activity.inkView.currentPage}
                ui {activity.inkView.changeFont(1)};settle();ui {activity.inkView.changeFont(-1)};settle()
                verify("${p.name}: font-size round trip preserves content and page",ui {activity.inkView.anchorOffset==anchor&&activity.inkView.currentPage==originalPage})
                windowCount++
            }
            verify("Every requested real-window condition executed",windowCount==if(arguments.containsKey("case"))1 else 12)
            // Keyboard coverage is deliberately local: no feedback submission/network request.
            if(!arguments.containsKey("case")||arguments.getString("case")=="11") {
            shell("settings put secure show_ime_with_hard_keyboard 1")
            uiAutomation.serviceInfo=uiAutomation.serviceInfo.apply {flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
            ui {activity.inkView.menuCommand(MenuCommand(MenuAction.LANGUAGE,1));activity.inkView.openMenu(MenuPage.SETTINGS);activity.inkView.menuCommand(MenuCommand(MenuAction.FEEDBACK))};settle()
            waitFor("feedback editor"){find("Feedback message")!=null}
            val draft="Responsive local draft; do not submit"
            find("Feedback message")!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,draft)})
            find("Feedback message")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            waitFor("keyboard"){uiAutomation.windows.any {it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD}}
            val keyboard=Rect();uiAutomation.windows.first {it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD}.getBoundsInScreen(keyboard)
            val scroll=uiAutomation.rootInActiveWindow?.let {root->
                fun visit(n:AccessibilityNodeInfo):AccessibilityNodeInfo? {if(n.className=="android.widget.ScrollView")return n;for(i in 0 until n.childCount)n.getChild(i)?.let {visit(it)?.let {r->return r}};return null};visit(root)
            }
            val panel=Rect();checkNotNull(scroll).getBoundsInScreen(panel)
            verify("Feedback panel stays above the phone keyboard",panel.bottom<=keyboard.top+2)
            verify("Feedback typing preserves the local draft without submitting",ui {activity.readerFeedback.draft==draft&&!activity.readerFeedback.busy})
            capture("12-feedback-keyboard").recycle()
            shell("input keyevent KEYCODE_BACK");SystemClock.sleep(300);shell("input keyevent KEYCODE_BACK");settle()
            verify("Feedback closes and retains the source anchor",ui {!activity.feedbackOpen&&activity.inkView.anchorOffset==anchor})
            ui {activity.readerFeedback.edit("");activity.inkView.menuCommand(MenuCommand(MenuAction.LANGUAGE,0))}
            }
        }catch(t:Throwable){failure=t.stackTraceToString();runCatching {capture("failure-window").recycle()}}
        finally {
            runCatching {ui {activity.finish()}}
            uiAutomation.serviceInfo=uiAutomation.serviceInfo.apply {flags=previousAccessibilityFlags}
            if(previousIme in listOf("0","1"))shell("settings put secure show_ime_with_hard_keyboard $previousIme") else shell("settings delete secure show_ime_with_hard_keyboard")
            shell("settings put system font_scale 1.0");shell("wm size 3408x2272");shell("wm density 320");shell("wm user-rotation lock 0")
            File(out,"responsive-window-report.json").writeText(JSONObject().put("passed",failure==null).put("roundCount",windowCount).put("checks",checks).put("failureContext",context).put("error",failure).toString(2))
            finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${checks.length()} actual-window checks passed\n")})
        }
    }
}
