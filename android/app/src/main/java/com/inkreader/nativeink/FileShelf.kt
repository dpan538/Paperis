package com.inkreader.nativeink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils

internal data class ShelfItem(val label: String, val detail: String, val id: String, val folder: Boolean)
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
        return folders + files.filter { it.folder == folder }.sortedBy { it.name.lowercase() }.map { ShelfItem(it.name, it.format, it.id, false) }
    }
    fun draw(c: Canvas, width: Float, height: Float, files: List<BookFile>, folder: String, page: Int, notice: String, busy: Boolean) {
        hits.clear()
        fun label(s: String, x: Float, y: Float, size: Float = 20f, gray: Int = 65, max: Float = width - 184) {
            text.textSize = size; text.color = Color.rgb(gray,gray,gray)
            c.drawText(TextUtils.ellipsize(s, text, max, TextUtils.TruncateAt.END).toString(), x, y, text)
        }
        fun hit(rect: RectF, command: ShelfCommand) { if (!busy) hits += rect to command }
        label("文件", 92f, 75f, 24f)
        label("导入文件", width - 420, 73f, 18f); hit(RectF(width-442,28f,width-304,102f), ShelfCommand("import"))
        label("导入文件夹", width - 270, 73f, 18f); hit(RectF(width-292,28f,width-125,102f), ShelfCommand("tree"))
        label(if (folder.isEmpty()) "本地文件" else "‹  $folder",92f,145f,18f,110)
        if (folder.isNotEmpty()) hit(RectF(72f,105f,width-92,167f), ShelfCommand("up"))
        val entries = items(files, folder); val last = ((entries.size - 1) / 7).coerceAtLeast(0); val selected = page.coerceIn(0,last)
        entries.drop(selected*7).take(7).forEachIndexed { i, entry ->
            val top = 180f + i * 94f
            label(if (entry.folder) "▱  ${entry.label}" else entry.label, 108f, top+39, 22f, max=width-400)
            label(entry.detail,width-240,top+39,17f,115,max=148f)
            c.drawLine(92f,top+70,width-92,top+70,line)
            hit(RectF(92f,top,width-92,top+86), ShelfCommand(if (entry.folder) "folder" else "book",entry.id))
        }
        if (entries.isEmpty()) label("此文件夹还没有书籍，点右上角导入。",108f,240f,21f,110)
        val status = notice.ifEmpty { "TXT / EPUB / MD / HTML / DOCX / 文本 PDF" }
        status.lineSequence().take(3).forEachIndexed { i,value -> label(value,92f,height-152+i*25,17f,110) }
        label("‹",96f,height-61,25f); hit(RectF(72f,height-100,180f,height-24),ShelfCommand("previous"))
        label("${selected+1} / ${last+1}",width/2-25,height-62,17f,110)
        label("›",width-108,height-61,25f); hit(RectF(width-180,height-100,width-72,height-24),ShelfCommand("next"))
    }
    fun hit(x: Float,y: Float) = hits.lastOrNull { it.first.contains(x,y) }?.second
}
