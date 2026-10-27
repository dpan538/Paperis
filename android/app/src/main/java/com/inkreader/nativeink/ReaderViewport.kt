package com.inkreader.nativeink

/** Actual app-window pixels mapped to density-independent content coordinates. */
internal data class ReaderViewport(
    val windowWidth: Int, val windowHeight: Int, val density: Float, val fontScale: Float,
    val left: Int, val top: Int, val right: Int, val bottom: Int
) {
    val width get() = (windowWidth-left-right)/density
    val height get() = (windowHeight-top-bottom)/density
    fun screenX(x:Float)=left+x*density
    fun screenY(y:Float)=top+y*density
    fun contentX(x:Float)=(x-left)/density
    fun contentY(y:Float)=(y-top)/density
    companion object {
        fun calculate(w:Int,h:Int,density:Float,fontScale:Float=1f,left:Int=0,top:Int=0,right:Int=0,bottom:Int=0):ReaderViewport {
            val width=w.coerceAtLeast(1);val height=h.coerceAtLeast(1)
            val l=left.coerceIn(0,width-1);val t=top.coerceIn(0,height-1)
            return ReaderViewport(width,height,density.takeIf { it.isFinite() && it>0 } ?: 1f,
                fontScale.takeIf { it.isFinite() && it>0 } ?: 1f,l,t,right.coerceIn(0,width-l-1),bottom.coerceIn(0,height-t-1))
        }
    }
}
