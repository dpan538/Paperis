package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual window flags, system rotation and clean native frames, not preference-only checks. */
internal class ScreenBehaviorEvidence(private val host:Instrumentation) {
    fun run()=with(host) {
        val out=File(targetContext.getExternalFilesDir(null),"screen-behavior-verification").also {it.mkdirs()}
        val checks=JSONArray();var failure:String?=null;var activity:MainActivity?=null
        fun <T> ui(f:()->T):T {val r=AtomicReference<Result<T>>();runOnMainSync {r.set(runCatching(f))};return r.get().getOrThrow()}
        fun shell(s:String)=android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(s)).use {it.bufferedReader().readText()}
        val oldMotion=shell("settings get global animator_duration_scale").trim()
        val oldTimeout=shell("settings get system screen_off_timeout").trim()
        fun waitFor(name:String,p:()->Boolean){val end=SystemClock.uptimeMillis()+25000;while(SystemClock.uptimeMillis()<end){if(p())return;SystemClock.sleep(60)};error("Timeout: $name")}
        fun verify(name:String,value:Boolean){checks.put(JSONObject().put("check",name).put("passed",value));check(value){name}}
        fun current()=checkNotNull(activity)
        fun settle(){waitFor("settled ink"){ui {!current().inkView.isRefreshing}};waitForIdleSync();SystemClock.sleep(180)}
        fun frame(name:String):Bitmap {
            val committed=CountDownLatch(1)
            ui {current().inkView.viewTreeObserver.registerFrameCommitCallback {committed.countDown()};current().inkView.invalidate()}
            check(committed.await(15,TimeUnit.SECONDS)){"No committed final frame"}
            SystemClock.sleep(80)
            val all=checkNotNull(uiAutomation.takeScreenshot());File(out,"$name.png").outputStream().use {all.compress(Bitmap.CompressFormat.PNG,100,it)}
            val v=ui {current().inkView.currentViewport!!}
            return Bitmap.createBitmap(all,v.left,v.top,v.windowWidth-v.left-v.right,v.windowHeight-v.top-v.bottom).also {all.recycle()}
        }
        fun touch(action:MenuAction,value:Int=0) {
            repeat(15) {
                val point=ui {val v=current().inkView;val m=v.currentMenuLayout;val hit=m.hitRegions.first {it.command==MenuCommand(action,value)}.bounds
                    val y=hit.centerY()-v.menuScrollOffset;if(y>=m.bounds.top+m.headerHeight+4&&y<m.bounds.bottom-4)v.screenPoint(hit.centerX(),y) else null}
                val now=SystemClock.uptimeMillis()
                if(point!=null) {
                    for((a,t)in listOf(MotionEvent.ACTION_DOWN to now,MotionEvent.ACTION_UP to now+50)){val e=MotionEvent.obtain(now,t,a,point.x,point.y,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;uiAutomation.injectInputEvent(e,true);e.recycle()}
                    settle();return
                }
                val points=ui {val v=current().inkView;val m=v.currentMenuLayout
                    val up=m.hitRegions.first {it.command==MenuCommand(action,value)}.bounds.centerY()-v.menuScrollOffset>=m.bounds.bottom-4
                    val a=v.screenPoint(m.bounds.centerX(),m.bounds.bottom-16);val z=v.screenPoint(m.bounds.centerX(),m.bounds.top+m.headerHeight+16)
                    if(up)a to z else z to a}
                for(i in 0..12){val a=if(i==0)MotionEvent.ACTION_DOWN else if(i==12)MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                    val e=MotionEvent.obtain(now,now+i*25L,a,points.first.x,points.first.y+(points.second.y-points.first.y)*i/12,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;uiAutomation.injectInputEvent(e,true);e.recycle()};settle()
            }
            error("Cannot reveal control: $action")
        }
        fun screen(){ui {current().inkView.openMenu(MenuPage.ROOT)};settle();touch(MenuAction.SCREEN)}
        fun awake()=ui {current().window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON!=0}
        fun launch():MainActivity = (startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity).also {activity=it;waitFor("reader ready"){ui {it.inkView.pageCount>0&&it.hasWindowFocus()}};settle()}
        try {
            LibraryWork.journal(targetContext).delete();shell("settings put global animator_duration_scale 1")
            val source=File(targetContext.cacheDir,"Screen behavior regression.txt")
            source.writeText("第一章 屏幕与阅读\n\n"+(1..100).joinToString("\n\n"){"阅读位置应当保持。每次翻页结束后，文字仍然清晰。A reader opened the book beside the window. Spaces and paragraphs remain intact. $it."})
            val library=BookFiles(targetContext);library.import(android.net.Uri.fromFile(source))
            val entry=library.list().first {it.name==source.name}
            for((index,p)in listOf(Triple(1080,2400,480),Triple(3408,2272,320)).withIndex()) {
                shell("wm user-rotation lock 0");shell("wm size ${p.first}x${p.second}");shell("wm density ${p.third}")
                uiAutomation.setRotation(android.app.UiAutomation.ROTATION_FREEZE_0)
                waitFor("display reset before profile $index") {targetContext.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0).rotation==0}
                targetContext.getSharedPreferences("files",0).edit().clear().putString("lastBook",entry.id).commit()
                BookFiles(targetContext).reading(entry).edit().clear().putBoolean("optical",false).commit()
                launch();waitFor("window $index"){ui {current().inkView.width==p.first&&current().inkView.height==p.second}};settle()
                ui {current().inkView.jumpToOffset(source.readText().length/3)};settle()
                var baseline:Bitmap?=null
                for(mode in 0..2) {
                    val beforeChoice=ui {current().inkView.anchorOffset}
                    screen();touch(MenuAction.REFRESH_MODE,mode)
                    verify("$index/$mode: refresh selection persists through actual touch",ui {current().inkView.refreshMode==mode}&&targetContext.getSharedPreferences("files",0).getInt("refreshMode",-1)==mode)
                    verify("$index/$mode: refresh selection preserves the exact source anchor",ui {current().inkView.anchorOffset==beforeChoice})
                    ui {current().inkView.toggleTools()};settle()
                    val before=frame("$index-mode-$mode-before")
                    if(baseline==null)baseline=before.copy(Bitmap.Config.ARGB_8888,false)
                    verify("$index/$mode: refresh choice preserves final text and gray levels",baseline!!.sameAs(before))
                    val page=ui {current().inkView.currentPage}
                    val pageTiming=ui {current().inkView.turnPage(1);val r=current().inkView.waveform;r.active?.full==false&&r.duration==RefreshPolicy.duration(mode,RefreshPolicy.Scene.PAGE)}
                    verify("$index/$mode: page uses its bounded non-flash transition",pageTiming)
                    ui {current().inkView.turnPage(-1);repeat(15){current().inkView.turnPage(1);current().inkView.turnPage(-1)}};settle()
                    verify("$index/$mode: rapid turns resolve to the latest page",ui {current().inkView.currentPage==page&&current().inkView.waveform.pending==null})
                    val after=frame("$index-mode-$mode-clean");verify("$index/$mode: terminal frame has no accumulated ghosts",before.sameAs(after));after.recycle()
                    val surfaceTiming=ui {current().inkView.menuCommand(MenuCommand(MenuAction.DARK));val r=current().inkView.waveform;r.active?.surface==true&&r.active?.full==false&&r.duration==RefreshPolicy.duration(mode,RefreshPolicy.Scene.SURFACE)}
                    verify("$index/$mode: appearance change is a soft surface transition",surfaceTiming)
                    settle();ui {current().inkView.menuCommand(MenuCommand(MenuAction.DARK))};settle()
                    val restored=frame("$index-mode-$mode-restored");verify("$index/$mode: appearance round trip restores exact paper",before.sameAs(restored));restored.recycle();before.recycle()
                }
                baseline?.recycle()
                // Explicit page turns select a new page-start anchor; freeze it before screen controls.
                val anchor=ui {current().inkView.anchorOffset}
                screen();touch(MenuAction.KEEP_AWAKE,1);ui {current().inkView.toggleTools()};settle()
                waitFor("reading keep awake"){awake()};verify("$index: foreground reading keeps its window awake",awake())
                ui {current().inkView.showFiles()};settle();verify("$index: Files releases reading keep-awake",!awake())
                ui {current().inkView.menuCommand(MenuCommand(MenuAction.FILES))};settle();verify("$index: returning to reading restores keep-awake",awake())
                uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                waitFor("reader in background"){ui {!current().hasWindowFocus()}};SystemClock.sleep(250)
                verify("$index: background releases the activity flag",!awake())
                targetContext.startActivity(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                waitFor("reader resumed"){ui {current().hasWindowFocus()}};settle()
                waitFor("resumed reading keep-awake"){awake()};verify("$index: resume reapplies the reader preference",awake())
                screen();touch(MenuAction.DIRECTION,1);waitFor("direction lock confirmed"){ui {current().inkView.directionLocked}}
                val rotation=ui {current().display!!.rotation};val axis=ui {current().resources.configuration.orientation}
                shell("wm user-rotation lock 1");SystemClock.sleep(900);settle()
                verify("$index: real display remains at the locked rotation",ui {current().display!!.rotation==rotation&&current().resources.configuration.orientation==axis&&current().inkView.directionLocked})
                verify("$index: locking direction preserves the source anchor",ui {current().inkView.anchorOffset==anchor})
                targetContext.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                waitFor("other app foreground"){ui {!current().hasWindowFocus()}}
                shell("wm user-rotation lock 1");SystemClock.sleep(700)
                waitFor("other application rotates the real display"){targetContext.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0).rotation==1}
                verify("$index: another application can rotate while the reader is backgrounded",!awake())
                verify("$index: another app's orientation does not erase the reading lock",targetContext.getSharedPreferences("files",0).getInt("orientationAxis",0)==axis)
                targetContext.startActivity(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                waitFor("reading lock after another app"){ui {current().hasWindowFocus()&&current().inkView.directionLocked&&current().display!!.rotation==1}}
                waitFor("full rotated reading viewport"){ui {current().inkView.width==p.second&&current().inkView.height==p.first}}
                settle();verify("$index: returning restores lock on the system-approved window and exact source",ui {current().inkView.anchorOffset==anchor&&current().inkView.width==p.second&&current().inkView.height==p.first})
                shell("wm user-rotation lock 0");SystemClock.sleep(600)
                verify("$index: restored lock holds the current real direction",ui {current().display!!.rotation==1&&current().inkView.directionLocked})
                screen()
                touch(MenuAction.DIRECTION,0);waitFor("automatic real rotation"){ui {current().display!!.rotation==0}};settle()
                waitFor("automatic viewport restored"){ui {current().inkView.width==p.first&&current().inkView.height==p.second}}
                verify("$index: Auto restores system rotation with the same source",ui {current().inkView.anchorOffset==anchor&&current().inkView.width==p.first&&current().inkView.height==p.second})
                shell("wm user-rotation lock 1");waitFor("second reading direction"){ui {current().display!!.rotation==1&&current().inkView.width==p.second&&current().inkView.height==p.first}};settle();screen()
                touch(MenuAction.DIRECTION,1);waitFor("second-axis lock"){ui {current().inkView.directionLocked}}
                ui {current().finish()};SystemClock.sleep(250);launch()
                waitFor("saved direction restored"){ui {current().inkView.directionLocked}}
                verify("$index: restart restores screen choices and the exact source",ui {current().inkView.readingAwake&&current().inkView.refreshMode==2&&current().inkView.anchorOffset==anchor&&current().display!!.rotation==1})
                verify("$index: restart reapplies foreground keep-awake",awake())
                screen();frame("$index-screen-menu").recycle();touch(MenuAction.DIRECTION,0);touch(MenuAction.KEEP_AWAKE,0)
                verify("$index: System releases the reading flag",!awake())
                ui {current().inkView.toggleTools()};settle()
                shell("settings put global animator_duration_scale 0");ui {current().inkView.resume();current().inkView.turnPage(1)}
                verify("$index: reduced motion settles immediately in any refresh choice",ui {!current().inkView.isRefreshing&&current().inkView.waveform.pending==null})
                val completed=current();ui {completed.finish()};waitFor("activity destroyed after profile $index"){ui {completed.isDestroyed}}
                activity=null;shell("settings put global animator_duration_scale 1")
            }
            verify("Reading never changes the global screen timeout",shell("settings get system screen_off_timeout").trim()==oldTimeout)
            verify("Application requests no notification permission",targetContext.packageManager.getPackageInfo(targetContext.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().none {it=="android.permission.POST_NOTIFICATIONS"})
        }catch(t:Throwable){failure=t.stackTraceToString();uiAutomation.takeScreenshot()?.let {b->File(out,"failure.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        finally {
            ui {activity?.finish()}
            if(oldMotion=="null")shell("settings delete global animator_duration_scale")else shell("settings put global animator_duration_scale $oldMotion")
            shell("wm user-rotation lock 0");shell("wm size 3408x2272");shell("wm density 320")
            File(out,"screen-behavior-report.json").writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("error",failure).toString(2))
            finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${checks.length()} actual screen behavior checks passed\n")})
        }
    }
}
