package com.inkreader.nativeink

import android.graphics.RectF
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Page-tree/text/outline acquisition through PDFBox-Android.
 *
 * Android's `PdfRenderer.textContents` is the only text API before API 35 and it can report the
 * same page object twice (and both folio numbers of a spread in one stream). PDFBox reads the
 * actual page tree and content streams instead, so this path is used whenever a document has a
 * real text layer; [PdfReflow] keeps the renderer/OCR path for raster-only documents.
 */
internal object PdfBoxText {
    data class Result(
        val pageCount:Int,
        val sizes:List<Pair<Int,Int>>,
        val pages:List<List<PdfReflow.Line>>,
        val outline:List<PdfOutlineMapping.Entry>,
        val proseCharacters:Int,
        val prosePages:Int,
        val pageLabels:List<String> = emptyList()
    ) {
        /** A real text layer, not a scanned book that only exposes a few printed folio digits. */
        val hasTextLayer:Boolean get()=proseCharacters>=20&&prosePages>=maxOf(1,ceil(pageCount*.25).toInt())
    }

    /** Extract pages in true page-tree order. Returns null when PDFBox cannot read the file. */
    fun read(file:File,progress:(Int,Int)->Unit={_,_->},context:android.content.Context?=null):Result? {
        if(context!=null&&!PDFBoxResourceLoader.isReady())runCatching {PDFBoxResourceLoader.init(context)}
        return runCatching { extract(file,progress) }.getOrElse {e->
            if(e is OutOfMemoryError)throw e
            null
        }
    }

    private fun extract(file:File,progress:(Int,Int)->Unit):Result? {
        PDDocument.load(file).use {document->
            val count=document.numberOfPages
            require(count<=1500) {"PDF 页数过多，请拆分后导入"}
            val sizes=ArrayList<Pair<Int,Int>>(count)
            val pages=ArrayList<List<PdfReflow.Line>>(count)
            val stripper=LineCollector()
            stripper.sortByPosition=true
            for(i in 0 until count) {
                check(!Thread.currentThread().isInterrupted) {"已取消，进度已保留"}
                progress(i+1,count)
                val page=document.getPage(i)
                sizes+=displaySize(page)
                // A page that cannot be read means the whole extraction is unreliable; fall back
                // to the renderer/OCR path instead of silently shipping a partial book.
                val lines=runCatching {
                    stripper.pageIndex=i
                    stripper.lines.clear()
                    stripper.startPage=i+1
                    stripper.endPage=i+1
                    stripper.getText(document)
                    stripper.lines.toList()
                }.getOrNull()?:return null
                pages+=lines.sortedWith(compareBy<PdfReflow.Line> {it.box.top}.thenBy {it.box.left})
            }
            val outline=runCatching {outline(document)}.getOrElse {emptyList()}
            // Printed page labels (roman front matter / arabic body) are useful when the reflowed
            // page no longer has a 1:1 relationship with the original page.
            val pageLabels=runCatching {document.documentCatalog.pageLabels?.labelsByPageIndices?.toList().orEmpty()}.getOrDefault(emptyList())
            var proseCharacters=0;var prosePages=0
            pages.forEach {page->
                var pageChars=0
                page.forEach {line->
                    val value=line.text.trim()
                    if(value.isNotEmpty()&&!PdfOutlineMapping.pageNumber(value))pageChars+=value.count {it.isLetterOrDigit()}
                }
                if(pageChars>0)prosePages++
                proseCharacters+=pageChars
            }
            return Result(count,sizes,pages,outline,proseCharacters,prosePages,pageLabels.ifEmpty {List(count){""}})
        }
    }

    private fun displaySize(page:PDPage):Pair<Int,Int> {
        val box=page.cropBox
        val rotated=page.rotation%180!=0
        val width=if(rotated)box.height else box.width
        val height=if(rotated)box.width else box.height
        return width.roundToInt().coerceAtLeast(1) to height.roundToInt().coerceAtLeast(1)
    }

    /** Depth-first outline flatten; levels are nesting depth, not guessed typography. */
    private fun outline(document:PDDocument):List<PdfOutlineMapping.Entry> {
        val root=document.documentCatalog.documentOutline?:return emptyList()
        val entries=mutableListOf<PdfOutlineMapping.Entry>()
        val seen=Collections.newSetFromMap(IdentityHashMap<PDOutlineItem,Boolean>())
        // PDPageTree hands out fresh PDPage wrappers, so page identity has to be resolved through
        // the underlying COS object. Outline entries in the wild use /Dest *or* /A GoTo actions.
        val pageIndex=IdentityHashMap<Any,Int>()
        for(i in 0 until document.numberOfPages)pageIndex[document.pages.get(i).cosObject]=i
        fun pageOf(item:PDOutlineItem):Int? {
            val direct=runCatching {item.findDestinationPage(document)}.getOrNull()
            if(direct!=null)pageIndex[direct.cosObject]?.let {return it}
            val destination=(runCatching {item.destination}.getOrNull() as? PDPageDestination)
                ?: (runCatching {(item.action as? com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo)?.destination}.getOrNull() as? PDPageDestination)
            if(destination!=null) {
                val page=runCatching {destination.page}.getOrNull()
                if(page!=null)pageIndex[page.cosObject]?.let {return it}
                val number=runCatching {destination.pageNumber}.getOrDefault(-1)
                if(number>0)return number-1
            }
            return null
        }
        fun walk(first:PDOutlineItem?,depth:Int) {
            var item=first
            while(item!=null&&entries.size<2000&&seen.add(item)) {
                PdfOutlineMapping.normalizeTitle(runCatching {item.title}.getOrDefault(""))?.let {label->
                    val page=pageOf(item)
                    if(page!=null)entries+=PdfOutlineMapping.Entry(label,page,(depth+1).coerceIn(1,3))
                }
                if(item.firstChild!=null)walk(item.firstChild,depth+1)
                item=runCatching {item.nextSibling}.getOrNull()
            }
        }
        walk(root.firstChild,0)
        return entries
    }

    /** Word-level callbacks are merged back into physical lines with font-metric boxes. */
    private class LineCollector:PDFTextStripper() {
        val lines=mutableListOf<PdfReflow.Line>()
        var pageIndex=0
        private val text=StringBuilder()
        private var pendingSpace=false
        private var baseline=Float.NaN
        private var left=Float.MAX_VALUE;private var right=-Float.MAX_VALUE
        // A single fallback CJK punctuation glyph must not inflate an English line box. Glyph counts
        // pick the dominant font, whose ascent/descent define the line's typographic height.
        private val fontCounts=mutableMapOf<String,Int>()
        private val fontMetrics=mutableMapOf<String,FloatArray>()

        private fun fontKey(position:TextPosition):String {
            val size=maxOf(position.fontSize,position.fontSizeInPt.toFloat()).takeIf {it>0f}?:(position.heightDir*2f).coerceAtLeast(1f)
            return (position.font?.name?:"")+"@"+size.roundToInt()
        }

        private fun ascentOf(position:TextPosition):Float {
            val size=maxOf(position.fontSize,position.fontSizeInPt.toFloat()).takeIf {it>0f}?:(position.heightDir*2f).coerceAtLeast(1f)
            val descriptor=runCatching {position.font?.fontDescriptor}.getOrNull()
            return descriptor?.ascent?.takeIf {it>0f}?.div(1000f)?.times(size)?:(position.heightDir*1.6f).coerceAtLeast(1f)
        }

        private fun descentOf(position:TextPosition):Float {
            val size=maxOf(position.fontSize,position.fontSizeInPt.toFloat()).takeIf {it>0f}?:(position.heightDir*2f).coerceAtLeast(1f)
            val descriptor=runCatching {position.font?.fontDescriptor}.getOrNull()
            return descriptor?.descent?.takeIf {it<0f}?.let {-it/1000f*size}?:(position.heightDir*.45f).coerceAtLeast(0f)
        }

        private fun wordMetrics(position:TextPosition,textWidth:Float):FloatArray {
            val ascent=ascentOf(position);val descent=descentOf(position)
            return floatArrayOf(position.yDirAdj,position.xDirAdj,position.xDirAdj+textWidth,position.yDirAdj-ascent,position.yDirAdj+descent)
        }

        protected override fun writeString(text:String,positions:List<TextPosition>) {
            if(text.isBlank()||positions.isEmpty())return
            // Spread-produced PDFs also draw the facing page outside the crop box, and PDFBox can
            // report both halves as one stretched word. Keep only glyphs visible on this page.
            val visible=positions.filter {position->
                val metrics=wordMetrics(position,position.widthDirAdj)
                minOf(metrics[2],pageWidth)-maxOf(metrics[1],0f)>.05f&&
                    minOf(metrics[4],pageHeight)-maxOf(metrics[3],0f)>.05f
            }
            if(visible.isEmpty())return
            val value=if(visible.size==positions.size)text else visible.joinToString("") {it.unicode}
            if(value.isBlank())return
            var startX=Float.MAX_VALUE;var endX=-Float.MAX_VALUE
            var baselineY=visible[0].yDirAdj;var lineHeight=1f
            visible.forEach {position->
                val metrics=wordMetrics(position,position.widthDirAdj)
                startX=minOf(startX,metrics[1]);endX=maxOf(endX,metrics[2])
                baselineY=maxOf(baselineY,metrics[0])
                lineHeight=maxOf(lineHeight,metrics[4]-metrics[3])
            }
            if(startX>endX)return
            // Flush before this word contributes, so the new line starts with its own font census.
            if(!baseline.isNaN()&&abs(baselineY-baseline)>lineHeight*.6f)finishLine()
            visible.forEach {position->
                val font=fontKey(position)
                fontCounts[font]=(fontCounts[font]?:0)+(position.unicode?.length?:1)
                if(font !in fontMetrics)fontMetrics[font]=floatArrayOf(ascentOf(position),descentOf(position))
            }
            if(this.text.isNotEmpty()&&pendingSpace)this.text.append(' ')
            this.text.append(value)
            pendingSpace=false
            if(baseline.isNaN())baseline=baselineY
            left=minOf(left,startX);right=maxOf(right,endX)
        }

        private var pageWidth=Float.MAX_VALUE
        private var pageHeight=Float.MAX_VALUE

        protected override fun startPage(page:PDPage) {
            val box=page.cropBox
            if(page.rotation%180==0) {pageWidth=box.width;pageHeight=box.height} else {pageWidth=box.height;pageHeight=box.width}
            super.startPage(page)
        }

        protected override fun writeWordSeparator() {pendingSpace=true}
        protected override fun writeLineSeparator() {finishLine()}
        protected override fun endPage(page:PDPage) {finishLine();super.endPage(page)}

        private fun finishLine() {
            val value=text.toString().replace(Regex("[\\t 　]+")," ").trim()
                .replace(Regex("(?<=[\\p{IsHan}]) +(?=[\\p{IsHan}])"),"")
            val dominant=fontCounts.maxByOrNull {it.value}?.key
            val metrics=dominant?.let {fontMetrics[it]}
            if(value.isNotEmpty()&&left<right&&!baseline.isNaN()&&metrics!=null&&metrics[0]+metrics[1]>0f) {
                lines+=PdfReflow.Line(value,RectF(left,baseline-metrics[0],right,baseline+metrics[1]),pageIndex)
            }
            text.setLength(0);pendingSpace=false;baseline=Float.NaN
            left=Float.MAX_VALUE;right=-Float.MAX_VALUE
            fontCounts.clear();fontMetrics.clear()
        }
    }
}

/**
 * Pure outline/page mapping. Kept free of PDFBox types so it can be verified by JVM unit tests.
 */
internal object PdfOutlineMapping {
    data class Entry(val title:String,val page:Int,val level:Int)

    private val numberOnly=Regex("^[0-9IVXLCivxlc\\s.．-]+$")
    private val skippedLabels=setOf("cover","contents","tableofcontents","目录","目錄","封面","table of contents")

    fun pageNumber(value:String)=numberOnly.matches(value.trim())&&value.any {it.isDigit()||it in "IVXLCivxlc"}

    fun normalizeTitle(value:String):String? {
        val title=value.replace(Regex("\\s+")," ").trim()
        if(title.length !in 1..200)return null
        if(pageNumber(title))return null
        if(ChapterScanner.key(title) in skippedLabels)return null
        return title
    }

    /** A destination block that names the outline entry, e.g. the heading printed mid-page. */
    fun matchesTitle(block:String,title:String):Boolean {
        val b=ChapterScanner.key(block);val t=ChapterScanner.key(title)
        if(b.isEmpty()||t.isEmpty())return false
        if(b==t)return true
        if(b.length<3||t.length<3)return false
        return (b.startsWith(t)||t.startsWith(b))&&block.length<=maxOf(title.length+24,64)
    }

    /**
     * Map an outline destination page to the block that names the chapter on that page; when the
     * heading is absent (blank/contents page) the first text block at or after it is used. A
     * bounded lookahead keeps a far-away block from being attached to a stale destination.
     */
    fun map(entries:List<Entry>,firstBlockByPage:Map<Int,Int>,pageCount:Int,lookahead:Int=8,blocksByPage:Map<Int,List<Int>> = emptyMap(),blockTexts:List<String> = emptyList()):List<Triple<String,Pair<Int,Int>,String>> {
        if(pageCount<=0)return emptyList()
        val nav=mutableListOf<Triple<String,Pair<Int,Int>,String>>()
        entries.forEach {entry->
            val title=normalizeTitle(entry.title)?:return@forEach
            if(entry.page !in 0 until pageCount)return@forEach
            val page=entry.page
            var index=blocksByPage[page]?.firstOrNull {block->blockTexts.getOrNull(block)?.let {matchesTitle(it,title)}==true}
            if(index==null)for(candidate in page..minOf(page+lookahead,pageCount-1)) {
                val block=firstBlockByPage[candidate]
                if(block!=null) {index=block;break}
            }
            if(index==null)return@forEach
            if(nav.any {it.second.first==index&&ChapterScanner.key(it.first)==ChapterScanner.key(title)})return@forEach
            nav+=Triple(title,index to entry.level.coerceIn(1,3),"pdf")
        }
        return nav
    }
}
