package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowInsets
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real PDF rendering and footer touch navigation, independent of OCR/layout helpers. */
internal class SourcePdfEvidence(private val host:Instrumentation) {
    private fun <T> ui(block:()->T):T {val r=AtomicReference<Result<T>>();host.runOnMainSync {r.set(runCatching(block))};return r.get().getOrThrow()}
    fun run()=with(host) {
        val checks=JSONArray();var failure:String?=null
        val out=File(targetContext.getExternalFilesDir(null),"source-verification").also {it.mkdirs()}
        fun verify(label:String,ok:Boolean){checks.put(JSONObject().put("check",label).put("passed",ok));check(ok){label}}
        fun shell(value:String){android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(value)).use {it.readBytes()}}
        // Detect the white PDF page independently of the viewer's destination rectangle.
        fun rasterValid(pixels:IntArray,w:Int,h:Int):Boolean {
            var left=w;var top=h;var right=-1;var bottom=-1;var white=0;var dark=0
            pixels.forEachIndexed {i,p->
                if((p and 255)>245&&(p shr 8 and 255)>245&&(p shr 16 and 255)>245){val x=i%w;val y=i/w;left=minOf(left,x);right=maxOf(right,x);top=minOf(top,y);bottom=maxOf(bottom,y);white++}
                if((p and 255)<40)dark++
            }
            if(right<=left||bottom<=top)return false
            val pw=right-left+1;val ph=bottom-top+1;val area=pw.toLong()*ph
            if(kotlin.math.abs(pw.toFloat()/ph-.75f)>.01f||white<area*.88||dark<area*.001)return false
            val figure=pixels[(top+ph*350/800)*w+left+pw/2]
            return (figure and 255) in 25..100
        }
        var activity:MainActivity?=null;var dialog:SourcePdfDialog?=null
        try {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("settings put secure immersive_mode_confirmations confirmed")).use {it.readBytes()}
            val file=File(targetContext.cacheDir,"source.pdf");val doc=PdfDocument()
            repeat(2) {i->val page=doc.startPage(PdfDocument.PageInfo.Builder(600,800,i+1).create());val p=Paint().apply {color=Color.RED};page.canvas.drawRect(200f,250f,400f,450f,p);p.color=Color.BLACK;p.textSize=28f;page.canvas.drawText("SOURCE PAGE ${i+1}",80f,140f,p);doc.finishPage(page)}
            file.outputStream().use {doc.writeTo(it)};doc.close()
            for((index,profile) in listOf(Triple(3408,2272,320),Triple(1080,2400,480)).withIndex()) {
            val (w,h,dpi)=profile;shell("wm size ${w}x$h");shell("wm density $dpi")
            val current=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            activity=current
            val resized=SystemClock.uptimeMillis()+20000
            while(!ui {current.inkView.width==w&&current.inkView.height==h&&kotlin.math.abs(current.resources.displayMetrics.density-dpi/160f)<.01f}&&SystemClock.uptimeMillis()<resized)SystemClock.sleep(60)
            val source=ui {SourcePdfDialog(current,file,0).also {it.show()}};dialog=source
            val deadline=SystemClock.uptimeMillis()+20000
            while(!ui {source.ready}&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(60)
            SystemClock.sleep(400)
            val bitmap=checkNotNull(uiAutomation.takeScreenshot());val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            File(out,"$index-original-page.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            verify("Profile $index: PDF source visibly renders text, artwork and the complete page",ui {source.ready}&&rasterValid(pixels,bitmap.width,bitmap.height))
            verify("Profile $index: colored source artwork is grayscale",pixels.all {val r=it shr 16 and 255;val g=it shr 8 and 255;val b=it and 255;maxOf(r,g,b)-minOf(r,g,b)<=4})
            if(index==0)verify("PDF raster detector rejects an empty viewer",!rasterValid(IntArray(pixels.size){Color.rgb(232,232,229)},bitmap.width,bitmap.height))
            bitmap.recycle()
            val (x,y)=ui {
                val decor=source.window!!.decorView;val d=decor.resources.displayMetrics.density
                val insets=decor.rootWindowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val location=IntArray(2);decor.getLocationOnScreen(location)
                location[0]+decor.width-insets.right-92*d to location[1]+decor.height-insets.bottom-32*d
            }
            val time=SystemClock.uptimeMillis();listOf(MotionEvent.obtain(time,time,0,x,y,0),MotionEvent.obtain(time,time+60,1,x,y,0)).forEach {e->e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;uiAutomation.injectInputEvent(e,true);e.recycle()}
            val end=SystemClock.uptimeMillis()+20000;while(!ui {source.ready&&source.pageIndex==1}&&SystemClock.uptimeMillis()<end)SystemClock.sleep(60)
            verify("Profile $index: original-page next responds to touchscreen input",ui {source.ready&&source.pageIndex==1})
            ui {source.dismiss();current.finish()};dialog=null;activity=null
            }
        } catch(t:Throwable){failure=t.stackTraceToString()}
        finally {ui {dialog?.dismiss();activity?.finish()};shell("wm size 3408x2272");shell("wm density 320")}
        File(out,"source-report.json").writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("error",failure).toString(2))
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${checks.length()} original PDF viewer checks passed\n")})
    }
}
