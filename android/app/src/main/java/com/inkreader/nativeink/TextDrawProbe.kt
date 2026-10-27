package com.inkreader.nativeink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.StaticLayout

/** Observe actual native draw-call paint spacing; caret positions omit draw-time justification. */
internal object TextDrawProbe {
    data class Metrics(var natural:Float=0f,var added:Float=0f,var width:Float=0f,var observed:Boolean=false)
    class CaptureCanvas(bitmap:Bitmap,private val layout:StaticLayout,val records:Array<Metrics>):Canvas(bitmap) {
        private val measure=Paint()
        private var limitLine:Int?=null
        fun limit(line:Int){limitLine=line}
        fun resetLimit(){limitLine=null}
        override fun getClipBounds(bounds:Rect):Boolean {val line=limitLine;bounds.set(0,if(line==null)0 else layout.getLineTop(line),layout.width,if(line==null)layout.height else layout.getLineBottom(line));return true}
        private fun record(y:Float,paint:Paint,hasSpace:Boolean) {
            if(!hasSpace)return
            val line=layout.getLineForVertical(y.toInt());val metric=records[line]
            measure.set(paint);measure.wordSpacing=0f
            val natural=measure.measureText(" ");val added=paint.wordSpacing
            metric.natural=maxOf(metric.natural,natural);metric.added=maxOf(metric.added,added)
            metric.width=maxOf(metric.width,natural+added);metric.observed=true
        }
        override fun drawTextRun(text:CharSequence,start:Int,end:Int,contextStart:Int,contextEnd:Int,x:Float,y:Float,isRtl:Boolean,paint:Paint) {record(y,paint,(start until end).any {text[it]==' '})}
        override fun drawTextRun(text:CharArray,index:Int,count:Int,contextIndex:Int,contextCount:Int,x:Float,y:Float,isRtl:Boolean,paint:Paint) {record(y,paint,(index until index+count).any {text[it]==' '})}
        override fun drawText(text:String,x:Float,y:Float,paint:Paint) {record(y,paint,text.contains(' '))}
        override fun drawText(text:String,start:Int,end:Int,x:Float,y:Float,paint:Paint) {record(y,paint,(start until end).any {text[it]==' '})}
        override fun drawText(text:CharSequence,start:Int,end:Int,x:Float,y:Float,paint:Paint) {record(y,paint,(start until end).any {text[it]==' '})}
        override fun drawText(text:CharArray,index:Int,count:Int,x:Float,y:Float,paint:Paint) {record(y,paint,(index until index+count).any {text[it]==' '})}
    }
    fun capture(layout:StaticLayout,draw:(Canvas)->Unit={layout.draw(it)}):Array<Metrics> {
        val records=Array(layout.lineCount){Metrics()};val pixel=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
        try {draw(CaptureCanvas(pixel,layout,records))} finally {pixel.recycle()}
        return records
    }
}
