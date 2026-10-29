package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Two phases straddle an actual replacement of the published 1.0.0 by 1.0.1. */
internal class UpgradeEvidence(private val host:Instrumentation,private val prepare:Boolean) {
    fun run()=with(host) {
        val checks=JSONArray();var failure:String?=null
        val folder=File(targetContext.getExternalFilesDir(null),"upgrade-verification").also {it.mkdirs()}
        fun verify(name:String,ok:Boolean){checks.put(JSONObject().put("check",name).put("passed",ok));check(ok){name}}
        fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        fun iconMetrics(name:String):JSONObject {
            val b=android.graphics.Bitmap.createBitmap(160,160,android.graphics.Bitmap.Config.ARGB_8888)
            val icon=targetContext.packageManager.getApplicationIcon(targetContext.packageName);icon.setBounds(0,0,160,160);icon.draw(android.graphics.Canvas(b))
            File(folder,name).outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            var x0=160;var y0=160;var x1=-1;var y1=-1;var mass=0.0;var mx=0.0;var my=0.0
            for(y in 0 until 160)for(x in 0 until 160) {
                val p=b.getPixel(x,y);val red=android.graphics.Color.red(p)
                if(android.graphics.Color.alpha(p)>128&&red<180) {
                    x0=minOf(x0,x);x1=maxOf(x1,x);y0=minOf(y0,y);y1=maxOf(y1,y)
                    val weight=(224-red).toDouble();mass+=weight;mx+=(x+.5)*weight;my+=(y+.5)*weight
                }
            }
            b.recycle();check(mass>0)
            return JSONObject().put("width",x1-x0+1).put("height",y1-y0+1).put("cx",mx/mass).put("cy",my/mass)
        }
        try {
            val info=targetContext.packageManager.getPackageInfo(targetContext.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
            val cert=sha(info.signingInfo!!.apkContentsSigners.single().toByteArray())
            val library=BookFiles(targetContext);val marker=targetContext.getSharedPreferences("upgrade-evidence",0)
            if(prepare) {
                verify("Preparation runs on the actual published 1.0.0",info.versionName=="1.0.0"&&info.longVersionCode==18L)
                val file=File(targetContext.cacheDir,"Stable upgrade regression.txt")
                file.writeText("第一章 保留书库\n\n"+(1..30).joinToString("\n\n"){"This original file, reading position and bookmarks survive the application upgrade. 原文件、字号与书签在覆盖安装后保留。 $it"})
                library.import(android.net.Uri.fromFile(file));val book=library.list().first {it.name==file.name}
                library.preferences.edit().putString("lastBook",book.id).putInt("preset",2).putInt("warmth",3).putInt("contrast",0)
                    .putInt("refreshMode",2).putBoolean("readingAwake",true).putInt("orientationAxis",targetContext.resources.configuration.orientation).commit()
                library.reading(book).edit().putInt("offset",77).putInt("font",2).putInt("spacing",0).putStringSet("bookmarks",setOf("123")).commit()
                marker.edit().putString("id",book.id).putString("original",sha(library.original(book)!!.readBytes())).putString("text",sha(library.text(book).toByteArray())).putString("cert",cert).putString("icon",iconMetrics("1.0.0-icon.png").toString()).putInt("orientationAxis",targetContext.resources.configuration.orientation).commit()
                verify("Previous release stores an original file, reading preferences and bookmark",library.original(book)!!.isFile)
            }else {
                verify("Stable APK is 1.0.1 with a higher Android version code",info.versionName=="1.0.1"&&info.longVersionCode==19L)
                verify("Stable APK is not debuggable",info.applicationInfo!!.flags and ApplicationInfo.FLAG_DEBUGGABLE==0)
                verify("Patch and previous release use exactly the same signing certificate",marker.getString("cert",null)==cert)
                val book=checkNotNull(library.get(marker.getString("id","").orEmpty()))
                verify("Original bytes survive actual replacement",sha(library.original(book)!!.readBytes())==marker.getString("original",null))
                verify("Imported reading text survives actual replacement",sha(library.text(book).toByteArray())==marker.getString("text",null))
                val saved=library.reading(book)
                verify("Source position, font, spacing and bookmarks survive",saved.getInt("offset",-1)==77&&saved.getInt("font",-1)==2&&saved.getInt("spacing",-1)==0&&saved.getStringSet("bookmarks",emptySet())==setOf("123"))
                verify("Selected paper, tone and contrast survive",library.preferences.getInt("preset",-1)==2&&library.preferences.getInt("warmth",-1)==3&&library.preferences.getInt("contrast",-1)==0)
                verify("Existing screen behavior preferences survive",library.preferences.getInt("refreshMode",0)==2&&library.preferences.getBoolean("readingAwake",false)&&library.preferences.getInt("orientationAxis",0)==marker.getInt("orientationAxis",-1))
                val old=JSONObject(marker.getString("icon","{}")!!);val icon=iconMetrics("stable-icon.png")
                verify("Launcher foreground keeps its accepted size and centering",listOf("width","height").all {icon.getInt(it)==old.getInt(it)}&&
                    kotlin.math.abs(icon.getDouble("cx")-80)<1.5&&kotlin.math.abs(icon.getDouble("cy")-80)<1.5&&
                    kotlin.math.abs(icon.getDouble("cx")-old.getDouble("cx"))<1&&kotlin.math.abs(icon.getDouble("cy")-old.getDouble("cy"))<1)
                File(folder,"icon-metrics.json").writeText(JSONObject().put("1.0.0",old).put("1.0.1",icon).toString(2))
            }
        }catch(t:Throwable){failure=t.stackTraceToString()}
        val name=if(prepare)"upgrade-prepare-report.json" else "upgrade-report.json"
        File(folder,name).writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("error",failure).toString(2))
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${checks.length()} actual upgrade checks passed\n")})
    }
}
