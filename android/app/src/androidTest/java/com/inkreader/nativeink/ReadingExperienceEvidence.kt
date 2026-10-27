package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real native UI checks for discoverability, settings, independent light/polarity and lifecycle. */
internal class ReadingExperienceEvidence(private val instrumentation: Instrumentation) {
    fun run() = with(instrumentation) {
        val checks=JSONArray();var failure:String?=null
        val out=File(targetContext.getExternalFilesDir(null),"experience-verification").also { it.mkdirs() }
        lateinit var activity:MainActivity
        fun <T> ui(block:()->T):T { val value=AtomicReference<Result<T>>();runOnMainSync { value.set(runCatching(block)) };return value.get().getOrThrow() }
        fun verify(name:String,value:Boolean) { checks.put(JSONObject().put("check",name).put("passed",value));check(value) { name } }
        fun waitFor(name:String,condition:()->Boolean) { val until=SystemClock.uptimeMillis()+25000;while(SystemClock.uptimeMillis()<until) { if(condition())return;SystemClock.sleep(40) };error("Timeout: $name") }
        fun settle() { waitFor("settled ink") { ui { !activity.inkView.isRefreshing } };waitForIdleSync();SystemClock.sleep(180) }
        fun capture(name:String):Bitmap {
            val committed=CountDownLatch(1)
            ui { activity.inkView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() };activity.inkView.invalidate() }
            check(committed.await(15,TimeUnit.SECONDS))
            val bitmap=checkNotNull(uiAutomation.takeScreenshot())
            File(out,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            return bitmap
        }
        fun tap(x:Float,y:Float) {
            val point=ui { activity.inkView.screenPoint(x/2f,y/2f) }
            val now=SystemClock.uptimeMillis()
            for((action,time) in listOf(MotionEvent.ACTION_DOWN to now,MotionEvent.ACTION_UP to now+60)) {
                val event=MotionEvent.obtain(now,time,action,point.x,point.y,0);event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
                uiAutomation.injectInputEvent(event,true);event.recycle()
            }
            settle()
        }
        fun click(label:String) { ui {
            val node=activity.inkView.createAccessibilityNodeInfo()
            val action=node.actionList.firstOrNull { it.label?.toString()==label } ?: error("Action missing: $label / ${node.actionList.map { it.label }}")
            check(activity.inkView.performAccessibilityAction(action.id,null))
        };settle() }
        try {
            targetContext.getSharedPreferences("files",0).edit().clear().putString("lastBook","sample").commit()
            targetContext.getSharedPreferences("reading",0).edit().clear().commit()
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            waitFor("landscape") { ui { activity.inkView.width==3408 && activity.inkView.height==2272 } };settle()
            val base=capture("01-base-porcelain")
            val baseTone=base.getPixel(1704,230)
            verify("Fresh reading defaults to near-neutral Porcelain with night and dark off",ui { activity.inkView.currentPreset==0 && !activity.inkView.nightEnabled && !activity.inkView.darkEnabled } && ((baseTone shr 16) and 255)-(baseTone and 255)<=2)
            base.recycle()
            tap(3192f,124f);capture("01-reading-menu").recycle()
            tap(3000f,1168f)
            verify("Permanent root-menu Import opens a native import panel",ui { activity.inkView.menuPage==MenuPage.IMPORT && activity.inkView.toolsOpen })
            capture("02-import-panel").recycle()
            click("＋ 从文件选择")
            waitFor("system picker") { uiAutomation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui")==true }
            verify("Import panel launches the real Android document picker",true)
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor("picker cancelled") { uiAutomation.rootInActiveWindow?.packageName?.toString()==targetContext.packageName };settle()
            ui { activity.inkView.openMenu(MenuPage.ROOT) };settle()
            val root=ui { activity.inkView.menuBounds }
            tap((root.centerX()*2),(root.top+58+9*64+32)*2)
            verify("Visible Settings row opens its own panel",ui { activity.inkView.menuPage==MenuPage.SETTINGS })
            verify("Settings uses exactly the root-menu width, height and origin",root==ui { activity.inkView.menuBounds })
            capture("03-settings-zh").recycle()
            click("语言 English")
            verify("Language switches the actual native UI and persists",ui { activity.inkView.english } && targetContext.getSharedPreferences("files",0).getBoolean("english",false))
            capture("04-settings-en").recycle()
            activity.updateSource={ ReaderUpdates.Result(3,"0.1.0-preview.999",ReaderUpdates.RELEASES+"/download/v0.1.0-preview.999/Paperis-0.1.0-preview.999.apk") }
            click("Check for updates")
            waitFor("update available") { ui { activity.inkView.updateState==3 } };settle()
            verify("Manual update callback exposes a real download action",ui { activity.inkView.createAccessibilityNodeInfo().actionList.any { it.label?.toString()=="Download 0.1.0-preview.999" } })
            var external:Intent?=null
            val monitor=object:Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? { external=intent;return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null) }
            }
            addMonitor(monitor)
            click("Download 0.1.0-preview.999")
            verify("Update download opens the release asset for user review",external?.action==Intent.ACTION_VIEW && external?.data?.host=="github.com" && external?.data?.path?.endsWith(".apk")==true)
            external=null;click("Send feedback")
            verify("Feedback opens a native editor without a browser handoff",ui { activity.feedbackOpen } && external==null)
            removeMonitor(monitor)
            fun findNative(label:String):android.view.accessibility.AccessibilityNodeInfo? {
                val queue=java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
                uiAutomation.rootInActiveWindow?.let { queue.add(it) }
                while(queue.isNotEmpty()) {
                    val node=queue.removeFirst()
                    if(node.contentDescription?.toString()==label || node.text?.toString()==label)return node
                    for(i in 0 until node.childCount)node.getChild(i)?.let { queue.add(it) }
                };return null
            }
            fun nativeClick(label:String) {
                val node=checkNotNull(findNative(label)) { "Native control missing: $label" }
                check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));SystemClock.sleep(250)
            }
            val draft="Paperis native feedback draft"
            val editor=checkNotNull(findNative("Feedback message"))
            check(editor.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,draft) }))
            ui { activity.readerFeedback.transport={_,_->503 to JSONObject().put("error","service_unconfigured")} }
            nativeClick("Send feedback")
            waitFor("feedback response") { ui { !activity.readerFeedback.busy } }
            verify("Unavailable feedback preserves the exact draft and reports its status",ui { activity.readerFeedback.draft==draft && activity.readerFeedback.status=="service_unconfigured" })
            capture("04-native-feedback").recycle()
            nativeClick("Back")
            ui { activity.inkView.openMenu(MenuPage.SETTINGS) };settle();click("Send feedback")
            verify("Reopening feedback restores the saved draft",findNative("Feedback message")?.text?.toString()==draft)
            var requestCount=0;var marker=""
            ui { activity.readerFeedback.transport={url,body->
                requestCount++
                if(body!=null) {
                    val digest=java.security.MessageDigest.getInstance("SHA-256").digest((body.getString("body")+'\u0000'+body.getString("version")).toByteArray()).joinToString("") { "%02x".format(it) }
                    marker="<!-- PAPERIS:${body.getString("requestId")}:$digest -->"
                    200 to JSONObject().put("accepted",true).put("requestId",body.getString("requestId")).put("receipt","https://github.com/dpan538/Paperis/issues/999999")
                } else {
                    check(url=="https://api.github.com/repos/dpan538/Paperis/issues/999999")
                    200 to JSONObject().put("html_url","https://github.com/dpan538/Paperis/issues/999999").put("body",marker)
                }
            } }
            nativeClick("Send feedback");waitFor("verified receipt") { ui { !activity.readerFeedback.busy } }
            verify("Feedback clears its draft only after independently verifying the receipt",ui { activity.readerFeedback.status=="received" && activity.readerFeedback.draft.isEmpty() } && requestCount==2)
            nativeClick("Back")
            ui { activity.inkView.openMenu(MenuPage.SETTINGS) };settle()
            activity.updateSource={ error("offline fixture") };click("Check for updates")
            waitFor("offline response") { ui { activity.inkView.updateState==4 } };settle()
            verify("An update network failure leaves settings usable with retry",ui { activity.inkView.toolsOpen && activity.inkView.menuPage==MenuPage.SETTINGS })
            click("Language 中文");click("返回阅读菜单")
            click("夜间模式 关闭")
            ui { activity.inkView.toggleTools() };settle()
            val night=capture("05-night-frontlight")
            val edge=(0 until 23).map { night.getPixel(142+it*142,2250) and 255 }
            verify("Normal front light has no exposed LED dots or periodic edge hotspots",edge.max()-edge.min()<12)
            val field=(1..10).map { night.getPixel(it*300,230) and 255 }
            verify("The front-light guide mixes illumination evenly across blank paper",field.max()-field.min()<12)
            verify("Night light does not enable dark polarity",ui { activity.inkView.nightEnabled && !activity.inkView.darkEnabled })
            val nightTone=night.getPixel(1704,230)
            verify("Night illumination retains a near-neutral paper substrate",((nightTone shr 16) and 255)-(nightTone and 255)<=2)
            verify("Enabling front light brightens the paper relative to the same unlit surface",(nightTone and 255)>(baseTone and 255))
            night.recycle()
            ui { activity.inkView.openMenu(MenuPage.SCREEN) };settle();click("灯光亮度 微光")
            ui { activity.inkView.toggleTools() };settle()
            val low=capture("05-night-low");val lowTone=low.getPixel(1704,230) and 255;low.recycle()
            ui { activity.inkView.openMenu(MenuPage.SCREEN) };settle();click("灯光亮度 明亮")
            ui { activity.inkView.toggleTools() };settle()
            val bright=capture("05-night-bright");val highTone=bright.getPixel(1704,230) and 255;bright.recycle()
            verify("All light levels brighten paper and increase monotonically without changing polarity",highTone>(nightTone and 255) && (nightTone and 255)>lowTone && lowTone>(baseTone and 255) && ui { !activity.inkView.darkEnabled })
            ui { activity.inkView.openMenu(MenuPage.SCREEN) };settle();click("灯光亮度 柔和")
            ui { activity.inkView.openMenu(MenuPage.ROOT) };settle();click("深色模式 关闭")
            ui { activity.inkView.toggleTools() };settle()
            val dark=capture("06-dark-with-light")
            verify("Dark mode has a black substrate",(dark.getPixel(1704,2100) and 255)<65)
            val pixels=IntArray(dark.width*dark.height);dark.getPixels(pixels,0,dark.width,0,0,dark.width,dark.height)
            verify("Dark mode draws pale text on the black substrate",pixels.count { (it and 255)>140 }>10000)
            dark.recycle()
            ui { activity.inkView.openMenu(MenuPage.ROOT) };settle();click("夜间模式 开启")
            verify("Night light can be off while dark mode stays on",ui { !activity.inkView.nightEnabled && activity.inkView.darkEnabled })
            capture("07-dark-menu").recycle()
            click("深色模式 开启");click("阅读预设")
            for((name,index) in listOf("白瓷" to 0,"护眼" to 1,"类纸" to 2)) {
                click("$name "+if(ui { activity.inkView.currentPreset==index }) "已选" else "选择")
                ui { activity.inkView.toggleTools() };settle()
                val surface=capture("08-surface-$index");val tone=surface.getPixel(1704,230)
                check(((tone shr 16) and 255)-(tone and 255)<=4) { "Preset is excessively yellow: $name" };surface.recycle()
                ui { activity.inkView.openMenu(MenuPage.PRESETS) };settle()
            }
            verify("Porcelain, Gentle and Paper are named, selectable and saved",ui { activity.inkView.currentPreset==2 } && targetContext.getSharedPreferences("files",0).getInt("preset",-1)==2)
            ui { activity.inkView.showFiles() };settle();capture("09-file-import").recycle()
            ui { activity.inkView.openMenu(MenuPage.ROOT) };settle()
            click("＋ 导入书籍")
            verify("File-page import remains available in the same persistent menu",ui { activity.inkView.libraryOpen && activity.inkView.menuPage==MenuPage.IMPORT })
            ui { activity.inkView.openBook(activity.inkView.files.demo,activity.inkView.files.text(activity.inkView.files.demo)) };settle()
            val before=ui { activity.inkView.drawCount };SystemClock.sleep(700)
            verify("New lighting remains idle on a stationary page",ui { activity.inkView.drawCount-before<=1 })
            ui { activity.inkView.pause();activity.inkView.resume() };settle()
        } catch(t:Throwable) { failure=t.stackTraceToString() }
        finally {
            File(out,"experience-report.json").writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("error",failure).toString(2))
            finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply { putString("stream",failure ?: "${checks.length()} reading experience checks passed\n") })
        }
    }
}
