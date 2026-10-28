package com.inkreader.nativeink

import android.app.Activity
import android.app.Dialog
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.util.concurrent.Executors

/** Native grayscale source pages for checking OCR and diagrams; bounded to one rendered page. */
internal class SourcePdfDialog(activity:Activity,file:File,start:Int):Dialog(activity,android.R.style.Theme_Material_Light_NoActionBar_Fullscreen) {
    private val worker=Executors.newSingleThreadExecutor()
    private var document:PdfRenderer?=null
    private var page=start;private var count=0;private var busy=true;private var error="";private var bitmap:Bitmap?=null
    internal val ready get()=bitmap!=null&&!busy
    internal val pageIndex get()=page
    private var generation=0
    private val view=object:View(activity) {
        private val destination=RectF()
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
        private val previous=RectF();private val next=RectF();private val close=RectF()
        private var insets=Insets.NONE
        init {
            setOnApplyWindowInsetsListener {_,value->insets=value.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout());invalidate();value}
        }
        override fun onDraw(canvas:Canvas) {
            val d=resources.displayMetrics.density
            val left=insets.left+12*d;val right=width-insets.right-12*d
            val bottom=height-insets.bottom-8*d;val top=insets.top+12*d;val toolbar=bottom-56*d
            canvas.drawColor(Color.rgb(232,232,229))
            bitmap?.let {b->val scale=minOf((right-left)/b.width,(toolbar-top)/b.height).coerceAtLeast(0f);val w=b.width*scale;val h=b.height*scale
                destination.set((left+right-w)/2,top+(toolbar-top-h)/2,(left+right+w)/2,top+(toolbar-top+h)/2);canvas.drawBitmap(b,null,destination,paint)}
            previous.set(left,bottom-48*d,left+48*d,bottom)
            next.set(right-104*d,bottom-48*d,right-56*d,bottom)
            close.set(right-48*d,bottom-48*d,right,bottom)
            paint.color=Color.DKGRAY;paint.textSize=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,16f,resources.displayMetrics)
            paint.textAlign=Paint.Align.CENTER
            for((rect,label) in listOf(previous to "‹",next to "›",close to "×"))canvas.drawText(label,rect.centerX(),rect.centerY()-(paint.fontMetrics.ascent+paint.fontMetrics.descent)/2,paint)
            val caption="PDF · ${page+1} / $count${if(busy)" …" else ""}"
            val available=(next.left-previous.right-16*d).coerceAtLeast(1f)
            val textPaint=android.text.TextPaint(paint)
            val value=android.text.TextUtils.ellipsize(caption,textPaint,available,android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(value,(previous.right+next.left)/2,previous.centerY()-(paint.fontMetrics.ascent+paint.fontMetrics.descent)/2,paint)
            paint.textAlign=Paint.Align.LEFT
            if(error.isNotEmpty())canvas.drawText(error,left,top+32*d,paint)
        }
        override fun onTouchEvent(event:MotionEvent):Boolean {
            if(event.action==MotionEvent.ACTION_UP) {
                when {
                    close.contains(event.x,event.y)->dismiss()
                    !busy&&previous.contains(event.x,event.y)->{page=(page-1).coerceAtLeast(0);load()}
                    !busy&&next.contains(event.x,event.y)->{page=(page+1).coerceAtMost((count-1).coerceAtLeast(0));load()}
                }
                performClick()
            };return true
        }
        override fun performClick():Boolean {super.performClick();return true}
    }
    init {
        setContentView(view)
        worker.execute {runCatching {document=PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY));count=document!!.pageCount;page=page.coerceIn(0,count-1)}.onFailure {error="无法打开 PDF 原页"};activity.runOnUiThread {load()}}
        setOnDismissListener {generation++;bitmap?.recycle();bitmap=null;worker.execute {document?.close()};worker.shutdown()}
    }
    private fun load() {
        if(worker.isShutdown)return
        busy=true;val token=++generation;view.invalidate()
        worker.execute {
            val result=runCatching {checkNotNull(document).openPage(page).use {p->
                val scale=2400f/maxOf(p.width,p.height);val b=Bitmap.createBitmap((p.width*scale).toInt().coerceAtLeast(1),(p.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                Canvas(b).drawColor(Color.WHITE);p.render(b,null,Matrix().apply {setScale(scale,scale)},PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val gray=Bitmap.createBitmap(b.width,b.height,Bitmap.Config.ARGB_8888);Canvas(gray).drawBitmap(b,0f,0f,Paint().apply {colorFilter=ColorMatrixColorFilter(ColorMatrix().apply {setSaturation(0f)})});b.recycle();gray
            }}
            view.post {if(token!=generation){result.getOrNull()?.recycle();return@post};bitmap?.recycle();bitmap=result.getOrNull();busy=false;view.contentDescription="PDF 原页 ${page+1} / $count";if(result.isFailure)error="无法读取此页";view.invalidate()}
        }
    }
}
