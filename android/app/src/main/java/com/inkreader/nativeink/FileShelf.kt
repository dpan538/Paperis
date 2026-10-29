package com.inkreader.nativeink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

internal data class ShelfItem(val label: String, val detail: String, val id: String, val folder:Boolean,val subtitle:String="",val chapters:Int=-1)
internal data class ShelfCommand(val action: String, val id: String = "")

/** A quiet paged file list. Geometry and labels are shared with hit testing. */
internal class FileShelf(private val sp:(Float)->Float={it}) {
    internal data class Geometry(val margin:Float,val headerY:Float,val importBounds:RectF,val listTop:Float,val rowHeight:Float,val footerY:Float,val previous:RectF,val next:RectF,val pageSize:Int)
    internal data class TitleRegion(val source:String,val bounds:RectF,val layout:StaticLayout,val metadataTop:Float)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170,170,170); strokeWidth = .7f;style=Paint.Style.STROKE }
    private val hits = mutableListOf<Pair<RectF, ShelfCommand>>()
    internal val hitRegions get()=hits.toList()
    private val titles=mutableListOf<TitleRegion>()
    internal val titleRegions get()=titles.toList()
    internal fun moreBounds(id:String)=hits.firstOrNull {it.second==ShelfCommand("more",id)}?.first?.let(::RectF)
    fun items(files: List<BookFile>, folder: String): List<ShelfItem> {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val folders = files.map { it.folder }.filter { it.startsWith(prefix) && it != folder }
            .map { it.removePrefix(prefix).substringBefore('/') }.distinct().sorted()
            .map { ShelfItem(it, "文件夹", prefix + it, true) }
        return folders + files.filter { it.folder == folder }.sortedBy { it.name.lowercase() }.map { ShelfItem(it.title,it.format,it.id,false,it.name,if(it.structured)it.chapters else -1) }
    }
    internal fun geometry(width:Float,height:Float,busy:Boolean=false,english:Boolean=false):Geometry {
        val compact=ReaderSizing(width,height).phone;val short=height<480f
        val margin=if(compact)(width*.045f).coerceIn(16f,32f) else (width*.054f).coerceIn(24f,92f)
        fun paint(size:Float)=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {textSize=sp(size)}
        fun lineHeight(size:Float)=paint(size).fontMetrics.let {it.bottom-it.top}
        val header=if(compact)maxOf(if(short)32f else 36f,sp(18f)+10f) else (height*.067f).coerceIn(56f,75f)
        val menuX=width-(width*.0634f).coerceIn(36f,108f)
        val button=if(compact) {
            val buttonPaint=paint(13f)
            val w=maxOf(88f,buttonPaint.measureText(if(english)"+ Import" else "＋ 导入")+20f)
            val h=maxOf(36f,lineHeight(13f)+10f)
            val end=menuX-30f
            val stacked=end-w-margin<paint(18f).measureText(if(english)"Files" else "文件")+12f
            val top=if(stacked)header+10f else (header-sp(6f)-h/2).coerceAtLeast(4f)
            if(stacked)RectF(margin,top,width-margin,top+h) else RectF(end-w,top,end,top+h)
        } else RectF(menuX-212f,header-34f,menuX-52f,header+14f)
        val listTop=if(compact) {
            if(short)maxOf(header+28f,button.bottom+12f)
            else maxOf(header+48f,button.bottom+lineHeight(12f)+20f)
        } else header+(height*.062f).coerceAtMost(70f)+35f
        val titleSize=if(compact)15f else 22f;val metaSize=if(compact)11f else 14f
        val row=maxOf(if(compact)if(short)52f else 60f else 90f,
            lineHeight(titleSize)*(if(compact&&!short)2 else 1)+lineHeight(metaSize)+if(compact)24f else 40f)
        val footer=height-if(compact)18f else 24f
        val previous=if(compact)RectF(margin-4f,footer-24f,margin+32f,footer+12f) else RectF(margin-8f,footer-30f,margin+40f,footer+18f)
        val next=if(compact)RectF(width-margin-32f,footer-24f,width-margin+4f,footer+12f) else RectF(width-margin-40f,footer-30f,width-margin+8f,footer+18f)
        val noticeHeight=if(busy)maxOf(50f,sp(12f)*3) else 0f
        val count=((footer-(if(compact)30f else 42f)-noticeHeight-listTop)/row).toInt().coerceIn(1,if(compact)10 else 7)
        return Geometry(margin,header,button,listTop,row,footer,previous,next,count)
    }
    fun pageSize(width:Float,height:Float,english:Boolean=false)=geometry(width,height,english=english).pageSize
    fun draw(c: Canvas, width: Float, height: Float, files: List<BookFile>, folder: String, page: Int, notice: String, busy: Boolean, english: Boolean = false) {
        hits.clear();titles.clear();val narrow=ReaderSizing(width,height).phone;val short=height<480f;val g=geometry(width,height,busy,english)
        fun t(zh: String,en: String) = if(english) en else zh
        fun label(s: String, x: Float, y: Float, size: Float = 20f, gray: Int = 65, max: Float = width-g.margin*2,align:Paint.Align=Paint.Align.LEFT) {
            text.textSize=sp(size);text.color=Color.rgb(gray,gray,gray);text.textAlign=align
            c.drawText(TextUtils.ellipsize(s,text,max.coerceAtLeast(1f),TextUtils.TruncateAt.END).toString(),x,y,text)
        }
        fun hit(rect: RectF, command: ShelfCommand) { if(!busy)hits+=RectF(rect) to command }
        label(if(short&&folder.isNotEmpty())t("‹ 文件","‹ Files") else t("文件","Files"),g.margin,g.headerY,if(narrow)18f else 24f,max=if(g.importBounds.top>g.headerY)width-g.margin-72f else g.importBounds.left-g.margin-12f)
        c.drawRoundRect(g.importBounds,3f,3f,line)
        val importText=if(narrow)t("＋ 导入","+ Import") else t("＋ 导入文件","+ Import files")
        val importSize=if(narrow)13f else 18f
        label(importText,g.importBounds.centerX(),g.importBounds.centerY()+sp(importSize)*.35f,importSize,if(busy)120 else 45,max=g.importBounds.width()-16f,align=Paint.Align.CENTER)
        hit(g.importBounds,ShelfCommand("import"))
        val path=if(folder.isEmpty())t("本地文件","Local files") else "‹  $folder"
        if(!short)label(path,g.margin,g.listTop-if(narrow)14f else 18f,if(narrow)12f else 18f,110)
        if(folder.isNotEmpty())hit(if(short)RectF(g.margin-4f,g.headerY-28f,if(g.importBounds.top>g.headerY)width-72f else g.importBounds.left-6f,g.headerY+8f) else RectF(g.margin,g.listTop-if(narrow)32f else 48f,width-g.margin,g.listTop),ShelfCommand("up"))
        val entries=items(files,folder);val count=g.pageSize;val last=((entries.size-1)/count).coerceAtLeast(0);val selected=page.coerceIn(0,last)
        entries.drop(selected*count).take(count).forEachIndexed { i, entry ->
            val top=g.listTop+i*g.rowHeight
            val titlePaint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {textSize=sp(if(narrow)15f else 22f);color=Color.rgb(65,65,65)}
            val actionSize=if(narrow)36f else 48f
            val rowBounds=RectF(g.margin,top,width-g.margin,top+g.rowHeight-4f)
            val more=RectF(rowBounds.right-actionSize,rowBounds.centerY()-actionSize/2,rowBounds.right,rowBounds.centerY()+actionSize/2)
            val titleWidth=(more.left-g.margin-24f).toInt().coerceAtLeast(1)
            val title=if(entry.folder)"▱  ${entry.label}" else entry.label
            val layout=StaticLayout.Builder.obtain(title,0,title.length,titlePaint,titleWidth).setIncludePad(false)
                .setMaxLines(if(narrow&&!short)2 else 1).setEllipsize(TextUtils.TruncateAt.END).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            val metaPaint=TextPaint().apply {textSize=sp(if(narrow)11f else 14f)}
            val metaBaseline=if(narrow)top+10f+layout.height+8f-metaPaint.fontMetrics.top else top+g.rowHeight-18f
            titles+=TitleRegion(title,RectF(g.margin+12f,top+10f,g.margin+12f+titleWidth,top+10f+layout.height),layout,metaBaseline+metaPaint.fontMetrics.top)
            c.save();c.translate(g.margin+12f,top+10f);layout.draw(c);c.restore()
            val detail=if(entry.folder)t("文件夹","Folder") else entry.detail+" · "+if(entry.chapters>=0)t("${entry.chapters} 章节","${entry.chapters} chapters") else if(entry.id=="sample")t("试读","Sample") else t("本机书籍","Local book")
            label(detail,g.margin+12f,metaBaseline,if(narrow)11f else 14f,115,max=titleWidth.toFloat())
            if(!entry.folder) {
                text.color=Color.rgb(if(busy)140 else 85,if(busy)140 else 85,if(busy)140 else 85)
                val gap=if(narrow)5f else 6f;val radius=if(narrow)1.35f else 1.6f
                for(dot in -1..1)c.drawCircle(more.centerX()+dot*gap,more.centerY(),radius,text)
            }
            c.drawLine(g.margin,top+g.rowHeight-6f,width-g.margin,top+g.rowHeight-6f,line)
            hit(RectF(rowBounds).apply {if(!entry.folder)right=more.left},ShelfCommand(if(entry.folder)"folder" else "book",entry.id))
            if(!entry.folder)hit(more,ShelfCommand("more",entry.id))
        }
        if(entries.isEmpty())label(t("还没有书籍，请从上方导入。","No books yet. Import above."),g.margin+12f,g.listTop+36f,if(narrow)14f else 21f,110,max=width-g.margin*2-24f)
        if(busy)notice.lineSequence().take(2).forEachIndexed {i,value->label(value,g.margin,g.footerY-70f+i*sp(18f),14f,110)}
        label("‹",g.previous.centerX(),g.footerY,if(narrow)20f else 25f,max=36f,align=Paint.Align.CENTER);hit(g.previous,ShelfCommand("previous"))
        label("${selected+1} / ${last+1}",width/2,g.footerY-1,if(narrow)12f else 13f,110,max=(g.next.left-g.previous.right-8f).coerceAtLeast(1f),align=Paint.Align.CENTER)
        label("›",g.next.centerX(),g.footerY,if(narrow)20f else 25f,max=36f,align=Paint.Align.CENTER);hit(g.next,ShelfCommand("next"))
    }
    fun hit(x: Float,y: Float) = hits.lastOrNull { it.first.contains(x,y) }?.second
}
