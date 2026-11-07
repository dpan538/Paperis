package com.inkreader.nativeink

import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.abs

/** Restore prose from PDF line geometry instead of treating every physical line as a paragraph. */
internal object PdfReflow {
    data class Line(val text:String,val box:RectF,val page:Int,val pageStart:Boolean=false,val ocr:Boolean=false,val confidence:Float=100f,val image:String="",val plate:Boolean=false)
    fun read(file:File,progress:(Int,Int)->Unit={_,_->},context:android.content.Context?=null,workspace:File=File(file.parentFile,file.name+"-pages"),forceOcr:Boolean=false):BookAssembly {
        val pages=mutableListOf<List<Line>>();val sizes=mutableListOf<Pair<Int,Int>>();val assets=mutableMapOf<String,File>()
        val signatures=mutableListOf<String>()
        var total=0
        // A real PDF text layer is read from the page tree by PDFBox. Android's textContents can
        // expose a page object twice (with both folio numbers in one stream), which reorders and
        // duplicates poetry. Raster-only PDFs keep the existing renderer/OCR path below.
        val box=if(!forceOcr)PdfBoxText.read(file,context=context)?.takeIf {it.hasTextLayer} else null
        val outline=box?.outline.orEmpty()
        if(box!=null) {
            require(box.pageCount<=1500) {"PDF 页数过多，请拆分后导入"}
            // Rendering stays in the existing pipeline for illustration/vector crops only; the
            // renderer's own text is discarded so its duplicate page objects never reach the book.
            PdfPages(context,workspace,forceOcr).use {processor->
                PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use {document->
                    require(document.pageCount==box.pageCount) {"PDF 页结构无法确认，请重新导入"}
                    repeat(document.pageCount) {i->
                        progress(i+1,document.pageCount)
                        document.openPage(i).use {page->
                            sizes+=box.sizes.getOrElse(i){page.width to page.height}
                            val raw=box.pages.getOrElse(i){emptyList()}
                            val figures=processor.read(page,i).filter {it.image.isNotEmpty()}
                            val chars=raw.sumOf {it.text.count {c->c.isLetterOrDigit()}}
                            // A sparse page whose composition is carried by an illustration is a plate:
                            // keep it as ONE original page, scaled to fit, instead of reflowing its
                            // fragments into columns (covers, title pages, full-page art).
                            if(figures.isNotEmpty()&&chars<220) {
                                val full=processor.fullPage(page,i)
                                val text=raw.joinToString(" ") {it.text.trim()}.trim().ifEmpty {"【插图】"}
                                pages+=listOf(full.copy(text=text,page=i,plate=true))
                            } else pages+=mergeFigures(raw,figures)
                        }
                    }
                }
                val used=pages.flatten().mapNotNull {it.image}.toSet()
                assets.putAll(processor.images.filterKeys {it in used})
            }
            total=pages.sumOf {page->page.sumOf {it.text.length}}
            require(total<=BookImporter.MAX_TEXT) {"PDF 正文过长，请拆分后导入"}
        } else PdfPages(context,workspace,forceOcr).use {processor->
            PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use {document->
                require(document.pageCount<=1500) {"PDF 页数过多，请拆分后导入"}
                repeat(document.pageCount) {i->progress(i+1,document.pageCount);document.openPage(i).use {page->
                    sizes+=page.width to page.height
                    val lines=processor.read(page,i)
                    // Some generators expose the same page object twice (and two folio numbers in
                    // one text stream). Keep the first copy; never duplicate the book text.
                    val numericLine=Regex("^[0-9IVXLCivxlc]{1,7}(?:[ ]+[0-9IVXLCivxlc]{1,7}){0,3}$")
                    val signature=lines.filter {l->l.image.isEmpty()&&!numericLine.matches(l.text.trim())}
                        .joinToString("\u0001"){l->l.text.trim().replace(Regex("\\s+")," ")} + "|"+
                        lines.filter {it.image.isNotEmpty()}.joinToString{processor.images[it.image]?.let {img->BookFiles.hashFile(img)}?:""}
                    signatures+=signature
                    val duplicate=signatures.size>1&&signature==signatures[signatures.size-2]&&lines.isNotEmpty()
                    if(!duplicate){total+=lines.sumOf {it.text.length};require(total<=BookImporter.MAX_TEXT){"PDF 正文过长，请拆分后导入"}}
                    pages+=if(duplicate)emptyList() else lines
                }}
            }
            val used=pages.flatten().mapNotNull {it.image}.toSet()
            assets.putAll(processor.images.filterKeys {it in used})
        }
        // Some producers expose one page twice with a different text-object order, or split it
        // across two page objects. Keep the longer copy when adjacent normalized texts contain
        // each other; never concatenate the same text twice.
        val normalized=pages.map {page->
            page.filter {it.image.isEmpty()}.map {it.text.trim().replace(Regex("\\s+")," ")}.sorted().joinToString("")
        }.toMutableList()
        for(i in 1 until pages.size) {
            if(normalized[i].isEmpty()||normalized[i-1].isEmpty())continue
            val a=normalized[i-1];val b=normalized[i]
            if(a==b||a.contains(b)||b.contains(a)) {
                val keepLonger=b.length>a.length
                val drop=if(keepLonger)i-1 else i
                pages[drop]=emptyList();normalized[drop]=""
            }
        }
        var keptChars=0;for(page in pages)keptChars+=page.sumOf {it.text.length}
        require(keptChars>0) {"未找到 PDF 内容"}
        total=keptChars
        val marginal=pages.flatten().filter {l->val h=sizes[l.page].second;l.box.bottom<h*.09f||l.box.top>h*.89f}
            .groupingBy {ChapterScanner.key(it.text)}.eachCount().filterValues {it>=3}.keys
        val tocLine=Regex("^(.{2,100}?)[·.．…]{4,}\\s*[（(]?\\s*[0-9IVXLCivxlc]{1,7}\\s*[）)]?\\s*$")
        val tocPages=pages.indices.filter {i->
            val lines=pages[i];val explicit=lines.any {ChapterScanner.key(it.text) in setOf("目录","目錄","contents","tableofcontents")}
            explicit||i<minOf(24,maxOf(4,pages.size/6))&&lines.count {tocLine.matches(it.text)}>=3
        }.toMutableSet()
        // A contents continuation may contain only one or two final entries and no repeated label.
        for(i in pages.indices)if(i>0&&i-1 in tocPages&&pages[i].any {tocLine.matches(it.text)})tocPages+=i
        val printed=mutableListOf<String>()
        for(pageIndex in tocPages)for((index,line) in pages[pageIndex].withIndex()) {
            val label=tocLine.find(line.text)?.groupValues?.get(1)?.trim()?:continue
            val previous=pages[pageIndex].getOrNull(index-1)?.text.orEmpty()
            printed+=if(!Regex("^[0-9]+[.．]").containsMatchIn(label)&&Regex("^[0-9]+[.．]").containsMatchIn(previous)&&!Regex("[·.．…]{4,}").containsMatchIn(previous))BookImporter.joinProse(previous,label) else label
        }
        val out=BookAssembly().apply {images.putAll(assets);ocrPages=pages.count {page->page.any {it.ocr}};uncertainLines=pages.sumOf {page->page.count {it.ocr&&it.confidence<85f}};box?.pageLabels?.forEachIndexed {index,label->if(label.isNotBlank())pageLabels[index]=label}}
        // First real text block and all text blocks of each PDF page, used to anchor outline
        // destinations. Some producers print the chapter heading mid-page after a poem tail.
        val pageFirstBlock=mutableMapOf<Int,Int>()
        val pageBlocks=mutableMapOf<Int,MutableList<Int>>()
        fun emit(block:SourceBlock) {
            if(block.kind!="image"&&block.page>=0) {
                pageFirstBlock.getOrPut(block.page){out.blocks.size}
                pageBlocks.getOrPut(block.page){mutableListOf()}.add(out.blocks.size)
            }
            out.add(block)
        }
        pages.take(if(pages.size>3)3 else 0).flatten().filter {it.image.isEmpty()&&!TextSemantics.metadata(it.text)&&(!it.ocr||it.confidence>=85f)&&it.text.length in 3..64&&it.box.height()>18f}.maxByOrNull {it.box.height()}?.let {out.titleHint=it.text}
        var previous:Line?=null
        for((i,page) in pages.withIndex()) {
            if(i in tocPages) {previous=null;continue}
            val height=sizes[i].second
            val singleNumber=Regex("^[0-9IVXLCivxlc]{1,7}$")
            val pairedNumber=Regex("^[0-9]{1,3}(?:[ ]+[0-9]{1,3}){1,3}$")
            var filtered=page.filterNot {l->
                val edge=l.box.bottom<height*.09f||l.box.top>height*.89f
                // Some renderers report both folio numbers of a spread in one line, often with
                // inaccurate bounds; strip standalone/paired numerals wherever they occur.
                pairedNumber.matches(l.text.trim())||
                    (edge&&(singleNumber.matches(l.text.trim())||ChapterScanner.key(l.text) in marginal))
            }
            // Printed dot-leader contents are replaced by actual body destinations.
            val tocRows=filtered.indices.filter {i in tocPages&&tocLine.matches(filtered[it].text)}.toMutableSet()
            if(tocRows.isNotEmpty()) {
                for(row in tocRows.toList()) {
                    val label=tocLine.find(filtered[row].text)?.groupValues?.get(1).orEmpty()
                    if(!Regex("^[0-9]+[.．]").containsMatchIn(label)&&row>0&&Regex("^[0-9]+[.．]").containsMatchIn(filtered[row-1].text)&&!tocLine.matches(filtered[row-1].text))tocRows+=row-1
                }
                val last=tocRows.max()
                filtered=filtered.filterIndexed {at,l->at !in tocRows&&!(at<=last&&(ChapterScanner.key(l.text) in setOf("目录","目錄","contents")||Regex("^第.+[部卷篇]").containsMatchIn(l.text)))}
                previous=null
            }
            val lines=mutableListOf<Line>();var cursor=0
            while(cursor<filtered.size) {
                val line=filtered[cursor];val prefix=ChapterScanner.key(line.text)
                val target=printed.firstOrNull {ChapterScanner.key(it).startsWith(prefix)&&prefix.length>=3}
                if(target!=null) {
                    var combined=line.text;var end=cursor
                    while(ChapterScanner.key(combined)!=ChapterScanner.key(target)&&end+1<filtered.size&&end-cursor<3){end++;combined=BookImporter.joinProse(combined,filtered[end].text)}
                    if(ChapterScanner.key(combined)==ChapterScanner.key(target)) {lines+=line.copy(text=target);cursor=end+1;continue}
                }
                lines+=line;cursor++
            }
            if(lines.isEmpty())continue
            val bodyHeights=lines.filter {it.image.isEmpty()}.map {it.box.height()}.filter {it>0}.sorted()
            val em=bodyHeights.getOrNull(bodyHeights.size/2)?.coerceAtLeast(5f)?:10f
            val left=lines.minOf {it.box.left};val right=lines.maxOf {it.box.right}
            val leading=lines.zipWithNext().map {(a,b)->b.box.top-a.box.top}.filter {it>em*.7f&&it<em*3}.sorted().let {it.getOrNull(it.size/2)?:em*1.6f}
            val textLines=lines.filter {it.image.isEmpty()}
            val contentWidth=(right-left).coerceAtLeast(1f)
            val headingKeys=printed.map {ChapterScanner.key(it)}.toSet()
            val verseRanges=mutableListOf<IntRange>()
            run {
                var at=0
                while(at<lines.size) {
                    val startLine=lines[at]
                    if(startLine.image.isNotEmpty()||startLine.box.height()>em*1.25f||ChapterScanner.key(startLine.text) in headingKeys){at++;continue}
                    var end=at
                    while(end+1<lines.size) {
                        val a=lines[end];val b=lines[end+1]
                        if(b.image.isNotEmpty()||b.box.height()>em*1.25f||ChapterScanner.key(b.text) in headingKeys)break
                        val topGap=b.box.top-a.box.top
                        if(topGap<em*.7f||topGap>em*3.2f)break
                        val sameLeft=kotlin.math.abs(b.box.left-a.box.left)<=em*.65f
                        if(!sameLeft)break
                        end++
                    }
                    val count=end-at+1
                    if(count>=4) {
                        val run=lines.subList(at,end+1)
                        val short=run.count {it.box.width()<contentWidth*.88f}
                        if(short>=count*.6)verseRanges+=at..end
                    }
                    at=end+1
                }
            }
            val verseStarts=verseRanges.associateBy {it.first}
            var skipUntil=-1
            for((lineIndex,l) in lines.withIndex()) {
                if(lineIndex<=skipUntil)continue
                verseStarts[lineIndex]?.let {range->
                    val run=lines.subList(range.first,range.last+1)
                    val sb=StringBuilder()
                    var prevTop=Float.NaN
                    for((k,line) in run.withIndex()) {
                        if(k>0&&line.box.top-prevTop>em*1.9f)sb.append('\n')
                        if(k>0)sb.append('\n')
                        sb.append(line.text.trim());prevTop=line.box.top
                    }
                    emit(SourceBlock(sb.toString(),"verse",page=i));previous=null;skipUntil=range.last;return@let
                }
                if(lineIndex<=skipUntil)continue
                if(l.plate) {emit(SourceBlock(l.text,"plate",imageKey=l.image,page=i));previous=null;continue}
                if(l.image.isNotEmpty()) {emit(SourceBlock("【插图】","image",imageKey=l.image,page=i));previous=null;continue}
                val recognized=if(printed.any {ChapterScanner.key(it)==ChapterScanner.key(l.text)})if(l.text.startsWith("第"))1 else 2 else ChapterScanner.level(l.text)
                val metadata=TextSemantics.metadata(l.text)
                val before=lines.getOrNull(lineIndex-1);val after=lines.getOrNull(lineIndex+1)
                val isolated=(before==null||l.box.top-before.box.bottom>em*.8f)&&(after==null||after.box.top-l.box.bottom>em*.8f)
                val centered=kotlin.math.abs(l.box.centerX()-sizes[i].first/2f)<sizes[i].first*.08f
                val displayHeading=!metadata&&l.text.length in 2..64&&(l.box.height()>em*1.23f||isolated&&(centered&&TextSemantics.shortChinese(l.text)||TextSemantics.englishTitle(l.text)))
                val heading=if(recognized>0)recognized else if(displayHeading)1 else 0
                val prev=previous
                // A whole quotation may be inset or centered. Its changing left edge is not
                // a fresh paragraph when typography, alignment and normal leading agree.
                val insetContinuation=prev!=null&&prev.page==i&&prev.box.left-left>em*1.15f&&l.box.left-left>em*1.15f&&
                    abs(l.box.height()-prev.box.height())<em*.25f&&l.box.top-prev.box.top<=leading*1.48f&&
                    (abs(l.box.left-prev.box.left)<em*.5f||abs(l.box.centerX()-prev.box.centerX())<em*.5f)&&
                    !Regex("[。！？.!?：:；;][”’」』）)]?$").containsMatchIn(prev.text)
                val indented=l.box.left-left>em*1.15f&&!insetContinuation
                val gap=prev!=null&&prev.page==i&&l.box.top-prev.box.top>leading*1.48f
                val shortPrevious=prev!=null&&prev.box.right<right-em*2.5f&&Regex("[。！？.!?：:]?[”’」』）)]?$").find(prev.text)?.value?.any {it in "。！？.!?：:"}==true
                val crossPage=prev!=null&&prev.page!=i
                val newParagraph=out.blocks.isEmpty()||heading>0||metadata||out.blocks.last().kind!="body"||(l.ocr&&l.pageStart)||indented||gap||shortPrevious||(crossPage&&prev!!.text.last() in "。！？.!?" )
                if(newParagraph)emit(SourceBlock(l.text,if(heading>0)"heading" else if(metadata)"metadata" else "body",heading,page=i))
                else {out.blocks.last().text=BookImporter.joinProse(out.blocks.last().text,l.text);out.repairedBreaks++}
                previous=l
            }
        }
        // Native PDF outline becomes the authoritative directory; printed contents only remain a
        // fallback when the producer ships no usable outline.
        if(box!=null&&outline.isNotEmpty()) {
            val nav=PdfOutlineMapping.map(outline,pageFirstBlock,pages.size,blocksByPage=pageBlocks,blockTexts=out.blocks.map {it.text})
            if(nav.isNotEmpty()) {
                nav.forEach { entry->
                    val label=entry.first
                    val index=entry.second.first;val level=entry.second.second
                    val block=out.blocks.getOrNull(index)
                    if(block!=null&&block.kind!="image"&&PdfOutlineMapping.matchesTitle(block.text,label)) {
                        block.kind="heading"
                        block.level=level
                    }
                }
                out.outline+=nav
                out.outlineAuthoritative=true
            }
        }
        return out
    }
    /** Keep renderer-found illustration crops while replacing the renderer's unreliable text. */
    private fun mergeFigures(text:List<Line>,figures:List<Line>):List<Line> {
        if(figures.isEmpty())return text
        val merged=text.toMutableList()
        for(figure in figures) {val at=merged.indexOfFirst {it.box.top>=figure.box.bottom};merged.add(if(at<0)merged.size else at,figure)}
        return merged
    }
}
