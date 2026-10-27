package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
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
        try {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand("settings put secure immersive_mode_confirmations confirmed")).use {it.readBytes()}
            val file=File(targetContext.cacheDir,"source.pdf");val doc=PdfDocument()
            repeat(2) {i->val page=doc.startPage(PdfDocument.PageInfo.Builder(600,800,i+1).create());val p=Paint().apply {color=Color.RED};page.canvas.drawRect(200f,250f,400f,450f,p);p.color=Color.BLACK;p.textSize=28f;page.canvas.drawText("SOURCE PAGE ${i+1}",80f,140f,p);doc.finishPage(page)}
            file.outputStream().use {doc.writeTo(it)};doc.close()
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val dialog=ui {SourcePdfDialog(activity,file,0).also {it.show()}}
            val deadline=SystemClock.uptimeMillis()+20000
            while(!ui {dialog.ready}&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(60)
            SystemClock.sleep(400)
            val bitmap=checkNotNull(uiAutomation.takeScreenshot());val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
            File(out,"original-page.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            File(out,"pixel-counts.json").writeText(JSONObject().put("ready",ui {dialog.ready}).put("white",pixels.count {(it and 255)>245}).put("dark",pixels.count {(it and 255)<40}).put("total",pixels.size).toString())
            verify("Saved PDF original visibly renders text and vector artwork",ui {dialog.ready}&&pixels.count {(it and 255)>245}>pixels.size*.4&&pixels.count {(it and 255)<40}>3000)
            verify("Colored PDF source artwork is rendered as grayscale",pixels.all {val r=it shr 16 and 255;val g=it shr 8 and 255;val b=it and 255;maxOf(r,g,b)-minOf(r,g,b)<=4})
            File(out,"original-page.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            val d=targetContext.resources.displayMetrics.density;val x=ui {dialog.window!!.decorView.width}-110*d;val y=ui {dialog.window!!.decorView.height}-30*d
            val time=SystemClock.uptimeMillis();listOf(MotionEvent.obtain(time,time,0,x,y,0),MotionEvent.obtain(time,time+60,1,x,y,0)).forEach {e->e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;uiAutomation.injectInputEvent(e,true);e.recycle()}
            val end=SystemClock.uptimeMillis()+20000;while(!ui {dialog.ready&&dialog.pageIndex==1}&&SystemClock.uptimeMillis()<end)SystemClock.sleep(60)
            verify("Original-page next control responds to actual touchscreen input",ui {dialog.ready&&dialog.pageIndex==1})
            ui {dialog.dismiss();activity.finish()}
        } catch(t:Throwable){failure=t.stackTraceToString()}
        File(out,"source-report.json").writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("error",failure).toString(2))
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${checks.length()} original PDF viewer checks passed\n")})
    }
}
