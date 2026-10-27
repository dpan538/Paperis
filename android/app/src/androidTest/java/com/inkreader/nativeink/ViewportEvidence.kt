package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowInsets
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real WindowManager density/size changes, system insets, scrolling and clean settled frames. */
internal class ViewportEvidence(private val host:Instrumentation) {
    fun run()=with(host) {
        val checks=JSONArray();var failure:String?=null
        val out=File(targetContext.getExternalFilesDir(null),"viewport-verification").also { it.mkdirs() }
        lateinit var activity:MainActivity
        fun <T> ui(block:()->T):T {val result=AtomicReference<Result<T>>();runOnMainSync { result.set(runCatching(block)) };return result.get().getOrThrow()}
        fun shell(command:String){android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).use { it.readBytes() }}
        fun waitFor(name:String,p:()->Boolean){val end=SystemClock.uptimeMillis()+30000;while(SystemClock.uptimeMillis()<end){if(p())return;SystemClock.sleep(80)};error("Timeout: $name")}
        fun settle(){waitFor("ink") {ui { !activity.inkView.isRefreshing }};waitForIdleSync();SystemClock.sleep(180)}
        fun verify(name:String,value:Boolean){checks.put(JSONObject().put("check",name).put("passed",value));check(value){name}}
        fun capture(name:String):Bitmap {
            val latch=CountDownLatch(1);ui {activity.inkView.viewTreeObserver.registerFrameCommitCallback {latch.countDown()};activity.inkView.invalidate()}
            check(latch.await(15,TimeUnit.SECONDS));return checkNotNull(uiAutomation.takeScreenshot()).also {bitmap->File(out,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
        }
        fun tap(x:Float,y:Float){val now=SystemClock.uptimeMillis();for((action,t) in listOf(MotionEvent.ACTION_DOWN to now,MotionEvent.ACTION_UP to now+50)){val e=MotionEvent.obtain(now,t,action,x,y,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;uiAutomation.injectInputEvent(e,true);e.recycle()};settle()}
        try {
            shell("wm user-rotation lock 0")
            targetContext.getSharedPreferences("files",0).edit().clear().putString("lastBook","sample").commit()
            targetContext.getSharedPreferences("reading",0).edit().clear().commit()
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            waitFor("initial layout") {ui {activity.inkView.pageCount>0}};settle()
            verify("The top system information bar remains visible by default",ui {activity.inkView.rootWindowInsets.isVisible(WindowInsets.Type.statusBars())})
            ui {activity.inkView.jumpToPage(1)};settle()
            val anchor=ui {activity.inkView.anchorOffset}
            for((index,profile) in listOf(Triple(3408,2272,320),Triple(3240,2160,480),Triple(2272,3408,400),Triple(1920,1200,320),Triple(1280,800,320)).withIndex()) {
                val (w,h,dpi)=profile
                shell("wm size ${w}x$h");shell("wm density $dpi")
                waitFor("actual window $w/$h/$dpi") {ui {val v=activity.inkView.currentViewport;activity.inkView.width==w && activity.inkView.height==h && v!=null && kotlin.math.abs(v.density-dpi/160f)<.01f}}
                settle()
                ui {activity.window.insetsController?.show(WindowInsets.Type.systemBars())};SystemClock.sleep(400);settle()
                ui {
                    val view=activity.inkView;val v=checkNotNull(view.currentViewport);val b=view.currentBookLayout
                    val stable=view.rootWindowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    verify("Profile $index uses actual window density and reserves reported system areas",v.left>=stable.left && v.top>=stable.top && v.bottom>=stable.bottom && v.width==(w-v.left-v.right)/v.density)
                    verify("Profile $index reserves a blank perimeter beyond every system inset",minOf(v.left-stable.left,v.top-stable.top,v.right-stable.right,v.bottom-stable.bottom)>=v.density*11f)
                    verify("Profile $index keeps the original source anchor after reflow",b.pages[view.currentPage].let {anchor in it.first().start until it.last().end})
                    verify("Profile $index fits every text column and native line",b.pages.flatten().all {b.layout.getLineBottom(it.lastLine)-b.layout.getLineTop(it.firstLine)<=b.bodyHeight} && (0 until b.layout.lineCount).all {b.layout.getLineRight(it)<=b.columnWidth+.5f})
                }
                val windowShot=capture("0$index-window-$w-$h-$dpi")
                if(index==0) {
                    val point=ui {activity.inkView.screenPoint(activity.inkView.currentViewport!!.width/2,activity.inkView.currentBookLayout.bodyBottom+12)}
                    val pixel=windowShot.getPixel(point.x.toInt(),point.y.toInt());val r=(pixel shr 16)and 255;val g=(pixel shr 8)and 255;val b=pixel and 255
                    verify("Base paper is bright and neutral, with no magenta or blue channel bias",minOf(r,g,b)>232&&r>=g&&g>=b&&r-b<=3)
                }
                windowShot.recycle()
                val p=ui {activity.inkView.menuButtonPoint};tap(p.x,p.y)
                ui {val v=checkNotNull(activity.inkView.currentViewport);val r=activity.inkView.menuBounds;verify("Profile $index keeps menu bounds inside the actual safe window",r.left>=0 && r.top>=0 && r.right<=v.width && r.bottom<=v.height)}
                capture("0$index-menu").recycle()
                if(index==4) {
                    val r=ui {activity.inkView.menuBounds};val a=ui {activity.inkView.screenPoint(r.centerX(),r.bottom-20)};val z=ui {activity.inkView.screenPoint(r.centerX(),r.top+80)}
                    repeat(3) {
                        val start=SystemClock.uptimeMillis()
                        for(i in 0..14) {val action=if(i==0)MotionEvent.ACTION_DOWN else if(i==14)MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE;val e=MotionEvent.obtain(start,start+i*30L,action,a.x,a.y+(z.y-a.y)*i/14f,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;uiAutomation.injectInputEvent(e,true);e.recycle()};settle()
                    }
                    verify("Compact menu scrolls without turning the book page",ui {activity.inkView.menuScrollOffset>0 && activity.inkView.toolsOpen})
                    capture("04-menu-scrolled").recycle()
                    val point=ui {activity.inkView.screenPoint(r.centerX(),r.top+58+7*64+32-activity.inkView.menuScrollOffset)}
                    tap(point.x,point.y)
                    verify("Scrolled Settings remains touchable in a short window",ui {activity.inkView.menuPage==MenuPage.SETTINGS})
                }
                ui {activity.inkView.toggleTools()};settle()
            }
            shell("wm size 3408x2272");shell("wm density 320")
            waitFor("restored window") {ui {activity.inkView.width==3408 && kotlin.math.abs((activity.inkView.currentViewport?.density ?: 0f)-2f)<.01f}};settle()
            shell("settings put system font_scale 1.8")
            waitFor("font scaling") {ui {(activity.inkView.currentViewport?.fontScale ?: 0f)>1.79f}};settle()
            ui {
                val view=activity.inkView
                val expected=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,30f,view.resources.displayMetrics)/view.resources.displayMetrics.density
                verify("System font scaling follows Android nonlinear SP conversion",expected>30f && kotlin.math.abs(view.currentBookLayout.font-expected)<.01f)
            }
            shell("settings put system font_scale 1.0")
            waitFor("font scale restored") {ui {kotlin.math.abs((activity.inkView.currentViewport?.fontScale ?: 0f)-1f)<.01f}};settle()
            ui {activity.window.insetsController?.hide(WindowInsets.Type.systemBars());activity.inkView.jumpToPage(0)};settle()
            val pristine=capture("05-pristine")
            ui {repeat(20){activity.inkView.turnPage(1);activity.inkView.turnPage(-1)}};settle()
            val returned=capture("06-roundtrip-clean")
            verify("Repeated page turns leave no old text on the settled page",pristine.sameAs(returned))
            ui {activity.inkView.openMenu(MenuPage.ROOT)};settle();ui {activity.inkView.toggleTools()};settle()
            val noMenu=capture("07-menu-dismissed-clean")
            verify("Closing a menu leaves neither old letters nor panel outlines",pristine.sameAs(noMenu))
            pristine.recycle();returned.recycle();noMenu.recycle()
        }catch(t:Throwable){failure=t.stackTraceToString()}
        finally {
            shell("settings put system font_scale 1.0");shell("wm size 3408x2272");shell("wm density 320");shell("wm user-rotation lock 0")
            File(out,"viewport-report.json").writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("error",failure).toString(2))
            finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure ?: "${checks.length()} actual-window/clean-frame checks passed\n")})
        }
    }
}
