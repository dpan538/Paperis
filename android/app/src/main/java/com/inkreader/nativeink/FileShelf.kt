package com.inkreader.nativeink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils

internal data class ShelfItem(val label: String, val detail: String, val id: String, val folder:Boolean,val subtitle:String="",val chapters:Int=-1)
internal data class ShelfCommand(val action: String, val id: String = "")

/** A quiet paged file list. Geometry and labels are shared with hit testing. */
internal class FileShelf {
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(170,170,170); strokeWidth = .7f }
    private val hits = mutableListOf<Pair<RectF, ShelfCommand>>()
    fun items(files: List<BookFile>, folder: String): List<ShelfItem> {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val folders = files.map { it.folder }.filter { it.startsWith(prefix) && it != folder }
            .map { it.removePrefix(prefix).substringBefore('/') }.distinct().sorted()
            .map { ShelfItem(it, "文件夹", prefix + it, true) }
        return folders + files.filter { it.folder == folder }.sortedBy { it.name.lowercase() }.map { ShelfItem(it.title,it.format,it.id,false,it.name,if(it.structured)it.chapters else -1) }
    }
    private fun headerY(height:Float)=(height*.067f).coerceIn(56f,75f)
    private fun listTop(height:Float)=headerY(height)+(height*.062f).coerceAtMost(70f)+35f
    fun pageSize(height:Float)=((height-listTop(height)-152f)/94f).toInt().coerceIn(1,7)
    fun draw(c: Canvas, width: Float, height: Float, files: List<BookFile>, folder: String, page: Int, notice: String, busy: Boolean, english: Boolean = false) {
        hits.clear()
        fun t(zh: String,en: String) = if(english) en else zh
        fun label(s: String, x: Float, y: Float, size: Float = 20f, gray: Int = 65, max: Float = width - 184) {
            text.textSize = size; text.color = Color.rgb(gray,gray,gray)
            c.drawText(TextUtils.ellipsize(s, text, max, TextUtils.TruncateAt.END).toString(), x, y, text)
        }
        fun hit(rect: RectF, command: ShelfCommand) { if (!busy) hits += rect to command }
        val margin=(width*.054f).coerceIn(24f,92f)
        val head=headerY(height)
        label(t("文件","Files"), margin,head,24f)
        line.style=Paint.Style.STROKE
        val menuX=width-(width*.0634f).coerceIn(36f,108f)
        val buttonWidth=if(width<480f)140f else 160f
        val importBounds=RectF(menuX-52f-buttonWidth,head-34f,menuX-52f,head+14f)
        c.drawRoundRect(importBounds,3f,3f,line)
        label(t("＋ 导入文件","+ Import files"),importBounds.left+12f,head-2f,18f,if(busy)120 else 45,max=140f)
        hit(importBounds,ShelfCommand("import"))
        label(if (folder.isEmpty()) t("本地文件 · 可直接导入文件","Local files · Import files here or from the menu") else "‹  $folder",margin,listTop(height)-35f,18f,110)
        if (folder.isNotEmpty()) hit(RectF(margin-12f,head+22f,width-margin,listTop(height)-13f), ShelfCommand("up"))
        val entries = items(files, folder); val count=pageSize(height); val last = ((entries.size - 1) / count).coerceAtLeast(0); val selected = page.coerceIn(0,last)
        entries.drop(selected*count).take(count).forEachIndexed { i, entry ->
            val top = listTop(height) + i * 94f
            label(if (entry.folder) "▱  ${entry.label}" else entry.label, margin+16f, top+32, 22f, max=(width-margin*2-160f).coerceAtLeast(80f))
            label(if(entry.folder) t("文件夹","Folder") else entry.detail,width-margin-100f,top+32,17f,115,max=100f)
            if(entry.subtitle.isNotEmpty())label(if(entry.chapters>=0)t("${entry.chapters} 章节","${entry.chapters} chapters") else if(entry.id=="sample")t("试读","Sample") else t("本机书籍","Local book"),margin+16f,top+60f,14f,115,max=width-margin*2-32f)
            c.drawLine(margin,top+80,width-margin,top+70,line)
            hit(RectF(margin,top,width-margin,top+86), ShelfCommand(if (entry.folder) "folder" else "book",entry.id))
        }
        if (entries.isEmpty()) label(t("此文件夹还没有书籍，可从上方导入。","No books here yet. Choose Import above."),margin+16f,listTop(height)+60f,21f,110)
        if(busy)notice.lineSequence().take(2).forEachIndexed {i,value->label(value,margin,height-152+i*25,17f,110)}
        label("‹",96f,height-61,25f); hit(RectF(72f,height-100,180f,height-24),ShelfCommand("previous"))
        label("${selected+1} / ${last+1}",width/2-25,height-62,17f,110)
        label("›",width-108,height-61,25f); hit(RectF(width-180,height-100,width-72,height-24),ShelfCommand("next"))
    }
    fun hit(x: Float,y: Float) = hits.lastOrNull { it.first.contains(x,y) }?.second
}
