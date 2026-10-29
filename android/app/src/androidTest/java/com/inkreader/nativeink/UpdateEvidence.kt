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

/** An independently built future-version fixture exercises the download, verifier and installer URI. */
internal class UpdateEvidence(private val host:Instrumentation) {
    /** Run only after publishing the installed version; this uses the actual public release feed. */
    fun runLive(expectNewer:Boolean=false)=with(host) {
        var failure:String?=null
        File(targetContext.filesDir,"updates").deleteRecursively()
        val updates=AppUpdates(targetContext){}
        try {
            val ready=SystemClock.uptimeMillis()+30000
            while(updates.working&&SystemClock.uptimeMillis()<ready)SystemClock.sleep(80)
            check(!updates.working)
            updates.check()
            val end=SystemClock.uptimeMillis()+60000
            while((updates.working||updates.ui.phase in setOf(UpdatePhase.IDLE,UpdatePhase.CHECKING))&&SystemClock.uptimeMillis()<end)SystemClock.sleep(100)
            if(expectNewer) {
                val installed=targetContext.packageManager.getPackageInfo(targetContext.packageName,0)
                val item=checkNotNull(updates.candidate) {"No candidate from the real public channel: ${updates.ui}"}
                check(installed.longVersionCode<item.versionCode&&item.versionCode==BuildConfig.VERSION_CODE.toLong()&&item.versionName==BuildConfig.VERSION_NAME) {"Unexpected public upgrade: $item"}
                check(updates.ui.phase in setOf(UpdatePhase.AVAILABLE,UpdatePhase.READY)) {"Public release channel: ${updates.ui}"}
                updates.download()
                val downloaded=SystemClock.uptimeMillis()+240000
                while((updates.working||updates.ui.phase!=UpdatePhase.READY)&&SystemClock.uptimeMillis()<downloaded) {
                    check(updates.ui.phase!=UpdatePhase.ERROR) {"Public APK download: ${updates.ui}"}
                    SystemClock.sleep(100)
                }
                check(!updates.working&&updates.ui.phase==UpdatePhase.READY) {"Public APK did not reach verified READY: ${updates.ui}"}
            }else check(updates.ui.phase==UpdatePhase.CURRENT&&updates.ui.message=="No newer version") {"Public release channel: ${updates.ui}"}
        } catch(t:Throwable){failure=t.stackTraceToString()}
        finally {updates.close()}
        val out=File(targetContext.getExternalFilesDir(null),"update-verification").also {it.mkdirs()}
        File(out,if(expectNewer)"available-report.json" else "channel-report.json").writeText(JSONObject().put("passed",failure==null).put("expectedVersion",BuildConfig.VERSION_NAME).put("installedVersion",targetContext.packageManager.getPackageInfo(targetContext.packageName,0).versionName).put("candidate",updates.candidate?.json()).put("phase",updates.ui.phase.name).put("error",failure).toString(2))
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:if(expectNewer)"The previous release downloads and verifies the real public update APK\n" else "The installed version resolves the real public GitHub update manifest\n")})
    }
    fun run()=with(host) {
        val output=File(targetContext.getExternalFilesDir(null),"update-verification").also {it.mkdirs()}
        val checks=JSONArray();var failure:String?=null;var retained:AppUpdates?=null
        fun <T> ui(f:()->T):T {val r=AtomicReference<Result<T>>();runOnMainSync {r.set(runCatching(f))};return r.get().getOrThrow()}
        fun waitFor(name:String,f:()->Boolean) {val end=SystemClock.uptimeMillis()+90000;while(SystemClock.uptimeMillis()<end){if(f())return;SystemClock.sleep(80)};error("Timeout: $name")}
        fun verify(name:String,yes:Boolean){checks.put(JSONObject().put("check",name).put("passed",yes));check(yes){name}}
        fun shell(value:String){android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(value)).use {it.readBytes()}}
        fun installerConfirmation():Boolean {
            val root=uiAutomation.rootInActiveWindow?:return false
            if(root.packageName?.toString()?.contains("packageinstaller")!=true)return false
            val nodes=java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>();nodes.add(root)
            val labels=mutableListOf<String>()
            while(nodes.isNotEmpty()) {val node=nodes.removeFirst();node.text?.toString()?.let {labels+=it};for(i in 0 until node.childCount)node.getChild(i)?.let {nodes.add(it)}}
            return labels.any {it=="Paperis"}&&labels.any {it.equals("Update",true)||it.equals("Install",true)||it in setOf("更新","安装")}
        }
        var installed:Intent?=null;var browser=false
        val monitor=object:Instrumentation.ActivityMonitor(){override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
            if(intent.action==Intent.ACTION_VIEW&&intent.data?.scheme=="https"){browser=true;return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)}
            @Suppress("DEPRECATION")
            if(intent.action==Intent.ACTION_INSTALL_PACKAGE){installed=intent;return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)}
            return null
        }}
        addMonitor(monitor)
        try {
            File(targetContext.filesDir,"updates").deleteRecursively()
            val future=File(targetContext.getExternalFilesDir(null),"update-fixture.apk");require(future.isFile){"Provide an independently built newer APK fixture"}
            val archive=checkNotNull(targetContext.packageManager.getPackageArchiveInfo(future.path,0))
            val version=checkNotNull(archive.versionName)
            val installedVersion=checkNotNull(targetContext.packageManager.getPackageInfo(targetContext.packageName,0).versionName)
            require(ReleaseVersion.parse(version)!!>ReleaseVersion.parse(installedVersion)!!){"APK fixture must be newer than the installed app"}
            val cert=AppUpdates.digest(targetContext.packageManager.getPackageInfo(targetContext.packageName,android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners.single().toByteArray())
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val updates=activity.appUpdates;waitFor("updater initialization"){!updates.working}
            val root="https://github.com/dpan538/Paperis/releases/download/v$version/"
            val apk="Paperis-$version.apk"
            var sample=future
            updates.releaseFeed={JSONArray().put(JSONObject().put("tag_name","v$version").put("assets",JSONArray().put(JSONObject().put("name","paperis-update.json").put("browser_download_url",root+"paperis-update.json")).put(JSONObject().put("name",apk).put("size",sample.length()).put("browser_download_url",root+apk)))).toString()}
            updates.manifestText={JSONObject().put("schemaVersion",1).put("packageName",targetContext.packageName).put("apk",apk).put("versionCode",archive.longVersionCode).put("versionName",version).put("sha256",AppUpdates.sha(sample)).put("certificateSha256",cert).toString()}
            val offsets=mutableListOf<Long>();var cut=true
            updates.testDownload={_,offset->
                offsets+=offset;val input=sample.inputStream();input.skip(offset)
                if(cut)object:java.io.FilterInputStream(input){var left=1024*1024
                    override fun read(b:ByteArray,off:Int,len:Int):Int {if(left<=0)throw java.io.IOException("Interrupted fixture connection");val n=super.read(b,off,minOf(len,left));if(n>0)left-=n;return n}
                } else input
            }
            ui {activity.inkView.menuCommand(MenuCommand(MenuAction.CHECK_UPDATE))}
            waitFor("available"){!updates.working&&updates.ui.phase==UpdatePhase.AVAILABLE}
            verify("Update opens an in-app panel without a browser",!browser&&ui {activity.inkView.menuPage==MenuPage.UPDATE})
            ui {activity.inkView.menuCommand(MenuCommand(MenuAction.DOWNLOAD_UPDATE))}
            waitFor("retained partial"){!updates.working&&updates.ui.phase==UpdatePhase.AVAILABLE&&offsets.size==1}
            verify("Interrupted download retains bytes for resuming",offsets.single()==0L&&File(targetContext.filesDir,"updates").listFiles().orEmpty().any {it.extension=="part"&&it.length()==1024L*1024})
            // Set deny from the host before instrumentation: Android kills the app when this grant is revoked.
            check(!targetContext.packageManager.canRequestPackageInstalls()) {"Reset REQUEST_INSTALL_PACKAGES to deny before starting this suite"}
            cut=false
            ui {activity.inkView.menuCommand(MenuCommand(MenuAction.DOWNLOAD_UPDATE))}
            waitFor("system install permission"){uiAutomation.rootInActiveWindow?.packageName?.toString()=="com.android.settings"}
            verify("The resumed download verifies the complete APK before requesting installation",offsets.last()==1024L*1024&&updates.ui.phase==UpdatePhase.READY&&!browser)
            uiAutomation.takeScreenshot()?.let {b->File(output,"install-permission.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            shell("appops set ${targetContext.packageName} REQUEST_INSTALL_PACKAGES allow")
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor("installer URI"){installed!=null}
            val install=checkNotNull(installed);val uri=checkNotNull(install.data)
            verify("Android receives a read-granted private APK URI, not a GitHub link",uri.scheme=="content"&&uri.authority==targetContext.packageName+".updates"&&install.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            val received=File(output,"installer-copy.apk")
            targetContext.contentResolver.openInputStream(uri)!!.use {input->received.outputStream().use {input.copyTo(it)}}
            verify("The installer reads the exact hash-verified, same-signer future APK",AppUpdates.sha(received)==AppUpdates.sha(future));received.delete()
            retained=AppUpdates(targetContext){}
            waitFor("cache restore"){retained!!.ui.phase==UpdatePhase.READY&&!retained!!.working}
            verify("A restarted updater reuses the verified package without another download",retained!!.reusedPackages==1&&retained!!.downloadRequests==0)
            retained!!.close();retained=null
            removeMonitor(monitor)
            ui {updates.install(activity)}
            waitFor("real Android update confirmation"){installerConfirmation()}
            verify("The real Android installer opens its update confirmation",true)
            uiAutomation.takeScreenshot()?.let {b->File(output,"install-confirmation.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            waitFor("installer cancelled"){uiAutomation.rootInActiveWindow?.packageName?.toString()==targetContext.packageName}
            addMonitor(monitor)
            installed=null
            val damaged=File(output,"damaged.apk");future.copyTo(damaged,true)
            java.io.RandomAccessFile(damaged,"rw").use {file->val at=file.length()/2;file.seek(at);val old=file.read();file.seek(at);file.write(old xor 0x40)}
            sample=damaged
            ui {activity.inkView.menuCommand(MenuCommand(MenuAction.CHECK_UPDATE))};waitFor("corrupt fixture offered"){!updates.working&&updates.ui.phase==UpdatePhase.AVAILABLE}
            ui {activity.inkView.menuCommand(MenuCommand(MenuAction.DOWNLOAD_UPDATE))};waitFor("signature rejection"){!updates.working&&updates.ui.phase==UpdatePhase.ERROR}
            verify("A tampered APK is rejected even when its announced file hash matches",installed==null&&!browser&&File(targetContext.filesDir,"updates").listFiles().orEmpty().none {it.name.contains(AppUpdates.sha(damaged).take(16))})
            damaged.delete()
            verify("Update URLs remain restricted to this repository",runCatching {AppUpdates.requireRepoAsset("https://github.com/other/project/releases/download/v1/app.apk")}.isFailure)
            ui {activity.finish()}
        }catch(t:Throwable){failure=t.stackTraceToString()}
        finally {
            retained?.close();removeMonitor(monitor)
            File(output,"update-report.json").writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("error",failure).toString(2))
            finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${checks.length()} in-app update, resumed download, APK signature and installer checks passed\n")})
        }
    }
}
