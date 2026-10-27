package com.inkreader.nativeink

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil

/** Page-at-a-time processing and restart checkpoints; image assets are streamed from disk. */
internal class PdfPages(private val context:Context?,private val workspace:File,private val forceOcr:Boolean):java.io.Closeable {
    private val engineVersion="p12-line-3/"+(context?.assets?.open("ocr/models.json")?.use {BookFiles.hashBytes(it.readBytes())}?:"native")
    private var ocr:LocalOcr?=null
    val images=mutableMapOf<String,File>()
    fun read(page:PdfRenderer.Page,index:Int):List<PdfReflow.Line> {
        check(!Thread.currentThread().isInterrupted) {"已取消，进度已保留"}
        workspace.mkdirs()
        val checkpoint=File(workspace,"page-$index.json")
        if(checkpoint.isFile)runCatching {
            val json=JSONObject(checkpoint.readText());check(json.optString("engine")==engineVersion);val saved=json.getJSONArray("images")
            repeat(saved.length()) {val key=saved.getString(it);val image=File(workspace,key);check(image.isFile);images[key]=image}
            val list=json.getJSONArray("lines")
            return (0 until list.length()).map {list.getJSONArray(it).let {a->PdfReflow.Line(a.getString(0),RectF(a.getDouble(1).toFloat(),a.getDouble(2).toFloat(),a.getDouble(3).toFloat(),a.getDouble(4).toFloat()),index,a.optBoolean(5),a.optBoolean(6),a.optDouble(7,100.0).toFloat(),a.optString(8))}}
        }
        var lines=if(!forceOcr&&Build.VERSION.SDK_INT>=35)nativeLines(page,index) else emptyList()
        val needsOcr=lines.sumOf {it.text.count {c->c.isLetterOrDigit()}}<8
        val scale=(2400f/maxOf(page.width,page.height)).coerceIn(1f,4f)
        val bitmap=Bitmap.createBitmap(ceil(page.width*scale).toInt(),ceil(page.height*scale).toInt(),Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        page.render(bitmap,null,Matrix().apply {setScale(scale,scale)},PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val pageImages=mutableListOf<String>()
        try {
            if(needsOcr) {
                val ctx=context?:error("扫描 PDF 需要本地 OCR 模型")
                val engine=ocr?:LocalOcr(ctx).also {ocr=it};lines=engine.lines(bitmap,index,scale)
                // Low-confidence cover/art pages stay as original images, rather than invented prose.
                if(lines.size<=6&&lines.sumOf {it.text.length}<120&&lines.map {it.confidence}.average()<55.0)lines=emptyList()
            }
            val regions=figureRegions(bitmap,lines,scale)
            // Image-only/blank OCR pages remain visible rather than silently disappearing.
            val figures=if(lines.isEmpty()) {
                var ink=0;for(y in 0 until bitmap.height step 12)for(x in 0 until bitmap.width step 12)if((bitmap.getPixel(x,y) and 255)<180)ink++
                if(ink>=10)listOf(Rect(0,0,bitmap.width,bitmap.height)) else emptyList()
            } else regions
            val added=mutableListOf<PdfReflow.Line>()
            for((n,region) in figures.withIndex()) {
                val key=BookFiles.hashBytes("page-$index-figure-$n".toByteArray())
                val image=File(workspace,key)
                val crop=Bitmap.createBitmap(region.width(),region.height(),Bitmap.Config.ARGB_8888)
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {colorFilter=ColorMatrixColorFilter(ColorMatrix().apply {setSaturation(0f)})}
                Canvas(crop).apply {drawColor(Color.WHITE);drawBitmap(bitmap,region,Rect(0,0,crop.width,crop.height),paint)}
                image.outputStream().use {crop.compress(Bitmap.CompressFormat.JPEG,90,it)};crop.recycle()
                images[key]=image;pageImages+=key
                added+=PdfReflow.Line("\uFFFC",RectF(region.left/scale,region.top/scale,region.right/scale,region.bottom/scale),index,image=key)
            }
            // Tess supplies reading order (including columns); place figures without re-sorting text.
            val merged=lines.toMutableList()
            for(figure in added) {val at=merged.indexOfFirst {it.box.top>=figure.box.bottom};merged.add(if(at<0)merged.size else at,figure)}
            val json=JSONObject().put("engine",engineVersion).put("lines",JSONArray().apply {merged.forEach {l->put(JSONArray(listOf(l.text,l.box.left,l.box.top,l.box.right,l.box.bottom,l.pageStart,l.ocr,l.confidence,l.image)))}}).put("images",JSONArray(pageImages))
            val partial=File(workspace,"page-$index.partial");partial.writeText(json.toString());check(partial.renameTo(checkpoint))
            return merged
        } finally {bitmap.recycle()}
    }
    @android.annotation.TargetApi(35)
    private fun nativeLines(page:PdfRenderer.Page,index:Int):List<PdfReflow.Line> {
        val lines=mutableListOf<PdfReflow.Line>()
        page.textContents.forEach {content->
            var cursor=0
            content.text.replace("\r\n","\n").replace('\r','\n').split('\n').filter {it.isNotBlank()}.forEachIndexed {j,s->
                val at=content.text.indexOf(s,cursor).coerceAtLeast(cursor);cursor=at+s.length
                val box=content.bounds.getOrNull(j)?:page.searchText(s.trim()).minByOrNull {abs(it.textStartIndex-at)}?.bounds?.takeIf {it.isNotEmpty()}?.let {r->RectF(r.first()).apply {r.drop(1).forEach {union(it)}}}?:RectF(0f,j*12f,page.width.toFloat(),j*12f+10f)
                val t=s.replace(Regex("[\\t 　]+")," ").trim().replace(Regex("(?<=[\\p{IsHan}]) +(?=[\\p{IsHan}])"),"")
                if(t.isNotBlank())lines+=PdfReflow.Line(t,RectF(box),index)
            }
        }
        return lines.sortedWith(compareBy<PdfReflow.Line>{it.box.top}.thenBy {it.box.left})
    }
    /** Find raster AND vector ink outside recognized text. Labels stay in the original crop. */
    private fun figureRegions(bitmap:Bitmap,lines:List<PdfReflow.Line>,scale:Float):List<Rect> {
        val step=4;val w=bitmap.width/step;val h=bitmap.height/step
        val mask=BooleanArray(w*h)
        for(l in lines) {
            val b=l.box;val x0=((b.left*scale-5)/step).toInt().coerceIn(0,w);val x1=((b.right*scale+5)/step).toInt().coerceIn(0,w)
            val y0=((b.top*scale-5)/step).toInt().coerceIn(0,h);val y1=((b.bottom*scale+5)/step).toInt().coerceIn(0,h)
            for(y in y0 until y1)for(x in x0 until x1)mask[y*w+x]=true
        }
        val rows=IntArray(h);val left=IntArray(h){w};val right=IntArray(h)
        for(y in 2 until h-2)for(x in 2 until w-2)if(!mask[y*w+x]&&(bitmap.getPixel(x*step,y*step) and 255)<170){rows[y]++;left[y]=minOf(left[y],x);right[y]=maxOf(right[y],x)}
        val regions=mutableListOf<Rect>();var start=-1;var end=-1
        fun finish() {
            if(start>=0&&end-start>=14) {
                val l=(start..end).minOf {left[it]};val r=(start..end).maxOf {right[it]};val ink=(start..end).sumOf {rows[it]}
                if(r-l>=18&&ink>=110)regions+=Rect((l*step-12).coerceAtLeast(0),(start*step-12).coerceAtLeast(0),(r*step+16).coerceAtMost(bitmap.width),(end*step+16).coerceAtMost(bitmap.height))
            };start=-1;end=-1
        }
        for(y in rows.indices) {
            if(rows[y]>=3){if(start<0)start=y;end=y} else if(start>=0&&y-end>10)finish()
        };finish()
        return regions.take(20)
    }
    override fun close() {ocr?.close()}
}
