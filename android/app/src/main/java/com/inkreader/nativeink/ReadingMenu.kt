package com.inkreader.nativeink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.ceil

internal enum class MenuPage { ROOT, NAVIGATION, BOOKMARKS, LAYOUT, SCREEN, PRESETS, SETTINGS, IMPORT, REFLOW, STORAGE, UPDATE }
internal enum class MenuAction {
    BACK, NAVIGATION, BOOKMARKS, LAYOUT, SCREEN, FONT_DOWN, FONT_UP,
    SPACING, COLUMNS, OPTICAL, REFRESH, DRAFT_DOWN, DRAFT_UP, PERCENT, GO,
    CHAPTER, BOOKMARK_TOGGLE, BOOKMARK_GO, BOOKMARK_REMOVE, BOOKMARK_PREVIOUS, BOOKMARK_NEXT,
    FILES, IMPORT, IMPORT_FILES, IMPORT_FOLDER, FONT_FACE, FONT_IMPORT, PRESETS, PRESET,
    SETTINGS, LANGUAGE, CHECK_UPDATE, DOWNLOAD_UPDATE, INSTALL_UPDATE, CANCEL_UPDATE, FEEDBACK, NIGHT, DARK, LIGHT, WARMTH, CONTRAST, REFLOW, REPROCESS, OCR, ORIGINAL, ORIGINAL_FILE, STORAGE, BACKUP_FOLDER, BACKUP, RESTORE, RESUME, PAUSE
}
internal data class MenuCommand(val action: MenuAction, val value: Int = 0)
internal data class MenuControl(val text: String, val command: MenuCommand?, val selected: Boolean = false)
internal data class MenuRow(val label: String, val command: MenuCommand? = null, val controls: List<MenuControl> = emptyList(), val controlsWidth: Float = 228f)
internal data class MenuModel(val title: String, val back: Boolean, val rows: List<MenuRow>, val minRows: Int = 0, val scroll: Float = 0f)

/** Measured labels, wrapping control grids and touch regions share one layout. */
internal class ReadingMenu(private val sp: (Float) -> Float = { it }) {
    internal data class TextRegion(val value: String, val bounds: RectF, val layout: StaticLayout)
    internal data class HitRegion(val bounds: RectF, val command: MenuCommand?)
    private data class Row(val bounds: RectF, val labels: List<TextRegion>, val hits: List<HitRegion>, val selected: List<RectF>)
    val bounds = RectF()
    private val fill = Paint().apply { color = Color.WHITE }
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(155,155,155);strokeWidth=.7f;style=Paint.Style.STROKE }
    private var header = 58f
    private var rows = emptyList<Row>()
    private var heading: TextRegion? = null
    private var backLabel: TextRegion? = null
    private var contentHeight = 0f
    var maxScroll = 0f; private set
    private var scroll = 0f
    internal val textRegions get() = rows.flatMap { it.labels }
    internal val hitRegions get() = rows.flatMap { it.hits }
    internal val headerHeight get() = header
    internal var minimumTouchSize = 48f; private set

    private fun typeset(value: String, size: Float, width: Float, gray: Int = 45, center: Boolean = false): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface=Typeface.create("sans-serif",Typeface.NORMAL);textSize=sp(size);color=Color.rgb(gray,gray,gray)
        }
        return StaticLayout.Builder.obtain(value,0,value.length,paint,width.toInt().coerceAtLeast(1))
            .setIncludePad(false).setAlignment(if(center)Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL).build()
    }
    private fun text(value:String,size:Float,x:Float,y:Float,width:Float,gray:Int=45,center:Boolean=false):TextRegion {
        val layout=typeset(value,size,width,gray,center)
        return TextRegion(value,RectF(x,y,x+width,y+layout.height),layout)
    }
    fun place(viewportWidth: Float, viewportHeight: Float, model: MenuModel) {
        val compact=ReaderSizing(viewportWidth,viewportHeight).phone
        minimumTouchSize=if(compact)36f else 48f
        val unit=if(compact)44f else 64f
        val padding=if(compact)12f else 16f
        val labelInset=if(compact)16f else 22f
        val vertical=if(compact)8f else 12f
        val gap=if(compact)8f else 12f
        val controlPad=if(compact)4f else 8f
        val side=(viewportWidth*.0493f).coerceIn(16f,84f)
        val width=minOf(if(compact)288f else 416f,(viewportWidth-side*2).coerceAtLeast(1f))
        val top=if(viewportHeight>=860f)110f else (viewportHeight*.08f).coerceAtLeast(12f)
        bounds.set(viewportWidth-side-width,top,viewportWidth-side,top)
        val titleX=bounds.left+if(model.back)if(compact)42f else 52f else labelInset
        val headingSize=if(compact)15f else 18f
        heading=text(model.title,headingSize,titleX,top+if(compact)12f else 18f,bounds.right-titleX-labelInset,90)
        header=maxOf(if(compact)44f else 58f,heading!!.layout.height+if(compact)24f else 32f)
        val backSize=if(compact)22f else 27f
        backLabel=if(model.back)text("‹",backSize,bounds.left+8f,top+(header-sp(backSize))/2,32f,90,true) else null
        val labelSize=if(compact)15f else 21f
        var cursor=0f
        rows=model.rows.map { row ->
            val y=top+header+cursor
            val labels=mutableListOf<TextRegion>();val hits=mutableListOf<HitRegion>();val selected=mutableListOf<RectF>()
            val left=bounds.left+padding;val available=width-padding*2
            val rowHeight:Float
            if(row.controls.isEmpty()) {
                val label=text(row.label,if(row.command==null)if(compact)12f else 17f else labelSize,
                    bounds.left+labelInset,y+vertical,(width-if(row.command==null)labelInset*2 else if(compact)52f else 70f).coerceAtLeast(1f),if(row.command==null)110 else 45)
                rowHeight=maxOf(unit,label.layout.height+vertical*2)
                labels+=label.copy(bounds=RectF(label.bounds).apply {offset(0f,(rowHeight-label.layout.height)/2-vertical)})
                if(row.command!=null) {
                    val size=if(compact)18f else 23f
                    labels+=text("›",size,bounds.right-if(compact)32f else 44f,y+(rowHeight-sp(size))/2,if(compact)24f else 32f,105,true)
                    hits+=HitRegion(RectF(bounds.left,y,bounds.right,y+rowHeight),row.command)
                }
            } else {
                val size=if(row.controls.size>=5)if(compact)12f else 14f else if(compact)13f else 19f
                val controlWidth=row.controls.maxOf { typeset(it.text,size,available).paint.measureText(it.text)+if(compact)8f else 16f }.coerceAtLeast(minimumTouchSize)
                val preferred=if(compact)controlWidth*row.controls.size else maxOf(row.controlsWidth,controlWidth*row.controls.size)
                val labelWidth=typeset(row.label,labelSize,available).paint.measureText(row.label)
                val inline=row.label.isNotEmpty()&&labelWidth+gap+preferred<=available-(labelInset-padding)
                val areaWidth=if(inline)preferred else available
                val possible=(areaWidth/controlWidth).toInt().coerceIn(1,row.controls.size)
                val gridRows=ceil(row.controls.size.toFloat()/possible).toInt()
                val columns=ceil(row.controls.size.toFloat()/gridRows).toInt()
                val start=if(inline)bounds.right-padding-areaWidth else left
                val label=if(row.label.isNotEmpty())text(row.label,labelSize,bounds.left+labelInset,y+vertical,
                    if(inline)(start-bounds.left-labelInset-gap).coerceAtLeast(1f) else available-(labelInset-padding)) else null
                val controlsTop=if(!inline&&label!=null)y+maxOf(minimumTouchSize,label.layout.height+vertical*2) else y+controlPad
                val cellWidth=areaWidth/columns
                val controlLayouts=row.controls.map {typeset(it.text,size,cellWidth-8f,if(it.command==null)120 else 45,true)}
                val cellHeight=maxOf(minimumTouchSize,controlLayouts.maxOf {it.height}+if(compact)10f else 16f)
                rowHeight=if(inline)maxOf(unit,(label?.layout?.height?:0)+vertical*2,cellHeight+controlPad*2)
                    else maxOf(unit,controlsTop-y+gridRows*cellHeight+controlPad)
                if(label!=null)labels+=if(inline)label.copy(bounds=RectF(label.bounds).apply {offset(0f,(rowHeight-label.layout.height)/2-vertical)}) else label
                if(row.command!=null)hits+=HitRegion(RectF(bounds.left,y,if(inline)start else bounds.right,if(inline)y+rowHeight else controlsTop),row.command)
                row.controls.forEachIndexed { index, control ->
                    val r=RectF(start+(index%columns)*cellWidth,controlsTop+(index/columns)*cellHeight,
                        start+(index%columns+1)*cellWidth,controlsTop+(index/columns+1)*cellHeight)
                    if(inline)r.offset(0f,(rowHeight-cellHeight)/2-controlPad)
                    val l=controlLayouts[index]
                    labels+=TextRegion(control.text,RectF(r.left+4f,r.top+(r.height()-l.height)/2,r.right-4f,r.top+(r.height()+l.height)/2),l)
                    hits+=HitRegion(r,control.command)
                    if(control.selected)selected+=r
                }
            }
            Row(RectF(bounds.left,y,bounds.right,y+rowHeight),labels,hits,selected).also {cursor+=rowHeight}
        }
        contentHeight=cursor
        val rowUnit=maxOf(unit,sp(labelSize)*2+if(compact)12f else 20f)
        val desired=header+maxOf(contentHeight,model.minRows*rowUnit)
        val height=minOf(desired,(viewportHeight-top-12f).coerceAtLeast(1f))
        bounds.bottom=top+height
        maxScroll=(contentHeight-(height-header)).coerceAtLeast(0f)
        scroll=model.scroll.coerceIn(0f,maxScroll)
    }
    private fun drawText(c:Canvas,label:TextRegion) {
        c.save();c.translate(label.bounds.left,label.bounds.top);label.layout.draw(c);c.restore()
    }
    fun draw(c: Canvas, model: MenuModel) {
        c.drawRect(bounds,fill);c.drawRect(bounds,rule)
        heading?.let {drawText(c,it)};backLabel?.let {drawText(c,it)}
        c.drawLine(bounds.left+20,bounds.top+header,bounds.right-20,bounds.top+header,rule)
        c.save();c.clipRect(bounds.left,bounds.top+header,bounds.right,bounds.bottom);c.translate(0f,-scroll)
        rows.forEachIndexed {i,row->
            row.labels.forEach {drawText(c,it)}
            row.selected.forEach {r->c.drawLine(r.centerX()-15,r.bottom-5,r.centerX()+15,r.bottom-5,rule)}
            if(i<rows.lastIndex)c.drawLine(bounds.left+20,row.bounds.bottom,bounds.right-20,row.bounds.bottom,rule)
        }
        c.restore()
        if(maxScroll>0f) {
            val travel=(bounds.height()-header-18f).coerceAtLeast(0f)
            val thumb=(travel*(bounds.height()-header)/contentHeight).coerceIn(minOf(24f,travel),travel)
            val y=bounds.top+header+9+(travel-thumb)*scroll/maxScroll
            c.drawLine(bounds.right-5,y,bounds.right-5,y+thumb,rule)
        }
    }
    fun hit(x: Float, y: Float, model: MenuModel): MenuCommand? {
        if(!bounds.contains(x,y))return null
        if(y<bounds.top+header)return if(model.back)MenuCommand(MenuAction.BACK) else null
        return hitRegions.lastOrNull {it.bounds.contains(x,y+scroll)}?.command
    }
}
