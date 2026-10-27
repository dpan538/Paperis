package com.inkreader.nativeink

import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.abs

/** Restore prose from PDF line geometry instead of treating every physical line as a paragraph. */
internal object PdfReflow {
    data class Line(val text:String,val box:RectF,val page:Int,val pageStart:Boolean=false,val ocr:Boolean=false,val confidence:Float=100f,val image:String="")
    fun read(file:File,progress:(Int,Int)->Unit={_,_->},context:android.content.Context?=null,workspace:File=File(file.parentFile,file.name+"-pages"),forceOcr:Boolean=false):BookAssembly {
        val pages=mutableListOf<List<Line>>();val sizes=mutableListOf<Pair<Int,Int>>();val assets=mutableMapOf<String,File>()
        var total=0
        PdfPages(context,workspace,forceOcr).use {processor->
            PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use {document->
                require(document.pageCount<=1500) {"PDF 页数过多，请拆分后导入"}
                repeat(document.pageCount) {i->progress(i+1,document.pageCount);document.openPage(i).use {page->
                    sizes+=page.width to page.height
                    val lines=processor.read(page,i);total+=lines.sumOf {it.text.length}
                    require(total<=BookImporter.MAX_TEXT) {"PDF 正文过长，请拆分后导入"};pages+=lines
                }}
            };assets.putAll(processor.images)
        }
        require(total>0) {"未找到 PDF 内容"}
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
        val out=BookAssembly().apply {images.putAll(assets);ocrPages=pages.count {page->page.any {it.ocr}};uncertainLines=pages.sumOf {page->page.count {it.ocr&&it.confidence<85f}}}
        pages.take(if(pages.size>3)3 else 0).flatten().filter {it.image.isEmpty()&&!TextSemantics.metadata(it.text)&&(!it.ocr||it.confidence>=85f)&&it.text.length in 3..64&&it.box.height()>18f}.maxByOrNull {it.box.height()}?.let {out.titleHint=it.text}
        var previous:Line?=null
        for((i,page) in pages.withIndex()) {
            val height=sizes[i].second
            var filtered=page.filterNot {l->
                val edge=l.box.bottom<height*.09f||l.box.top>height*.89f
                edge&&(l.text.matches(Regex("[0-9IVXLCivxlc]{1,7}"))||ChapterScanner.key(l.text) in marginal)
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
            for((lineIndex,l) in lines.withIndex()) {
                if(l.image.isNotEmpty()) {out.add(SourceBlock("【插图】","image",imageKey=l.image,page=i));previous=null;continue}
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
                if(newParagraph)out.add(SourceBlock(l.text,if(heading>0)"heading" else if(metadata)"metadata" else "body",heading,page=i))
                else {out.blocks.last().text=BookImporter.joinProse(out.blocks.last().text,l.text);out.repairedBreaks++}
                previous=l
            }
        }
        return out
    }
}
