package com.inkreader.nativeink

import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.abs

/** Restore prose from PDF line geometry instead of treating every physical line as a paragraph. */
internal object PdfReflow {
    data class Line(val text:String,val box:RectF,val page:Int)
    fun read(file:File,progress:(Int,Int)->Unit={_,_->}):BookAssembly {
        if(Build.VERSION.SDK_INT<35)throw IllegalArgumentException("PDF 文本重排需要 Android 15 及以上；可先转为 EPUB/TXT")
        val pages=mutableListOf<List<Line>>();val sizes=mutableListOf<Pair<Int,Int>>()
        var total=0
        PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use { document->
            require(document.pageCount<=1500) {"PDF 页数过多，请拆分后导入"}
            repeat(document.pageCount) {i->progress(i+1,document.pageCount);document.openPage(i).use {page->
                sizes+=page.width to page.height
                val lines=mutableListOf<Line>()
                page.textContents.forEach {content->
                    val pieces=content.text.replace("\r\n","\n").replace('\r','\n').split('\n').filter {it.isNotBlank()}
                    var cursor=0
                    pieces.forEachIndexed {j,s->
                        val at=content.text.indexOf(s,cursor).coerceAtLeast(cursor);cursor=at+s.length
                        // Several system PDF implementations expose raw page text with an empty bounds list.
                        // Native text matching supplies the real line rectangle in that case.
                        val box=content.bounds.getOrNull(j)?:run {
                            val match=page.searchText(s.trim()).minByOrNull {kotlin.math.abs(it.textStartIndex-at)}
                            match?.bounds?.takeIf {it.isNotEmpty()}?.let {rects->RectF(rects.first()).apply {rects.drop(1).forEach {union(it)}}}
                        }?:RectF(0f,j*12f,page.width.toFloat(),j*12f+10f)
                        val t=s.replace(Regex("[\\t 　]+")," ").trim().replace(Regex("(?<=[\\p{IsHan}]) +(?=[\\p{IsHan}])"),"")
                        if(t.isNotBlank())lines+=Line(t,RectF(box),i)
                    }
                }
                total+=lines.sumOf {it.text.length};require(total<=BookImporter.MAX_TEXT) {"PDF 正文过长，请拆分后导入"}
                pages+=lines.sortedWith(compareBy<Line>{it.box.top}.thenBy {it.box.left})
            }}
        }
        require(total>0) {"此 PDF 没有文本层，扫描图片暂不能重排"}
        val marginal=pages.flatten().filter {l->val h=sizes[l.page].second;l.box.bottom<h*.09f||l.box.top>h*.89f}
            .groupingBy {ChapterScanner.key(it.text)}.eachCount().filterValues {it>=3}.keys
        val tocLine=Regex("^(.{2,100}?)[·.．…]{4,}\\s*[（(]?\\s*[0-9IVXLCivxlc]{1,7}\\s*[）)]?\\s*$")
        val printed=mutableListOf<String>()
        for(page in pages)for((index,line) in page.withIndex()) {
            val label=tocLine.find(line.text)?.groupValues?.get(1)?.trim()?:continue
            val previous=page.getOrNull(index-1)?.text.orEmpty()
            printed+=if(!Regex("^[0-9]+[.．]").containsMatchIn(label)&&Regex("^[0-9]+[.．]").containsMatchIn(previous)&&!Regex("[·.．…]{4,}").containsMatchIn(previous))BookImporter.joinProse(previous,label) else label
        }
        val out=BookAssembly()
        pages.take(if(pages.size>3)3 else 0).flatten().filter {it.text.length in 3..64&&it.box.height()>18f}.maxByOrNull {it.box.height()}?.let {out.titleHint=it.text}
        var previous:Line?=null
        for((i,page) in pages.withIndex()) {
            val height=sizes[i].second
            var filtered=page.filterNot {l->
                val edge=l.box.bottom<height*.09f||l.box.top>height*.89f
                edge&&(l.text.matches(Regex("[0-9IVXLCivxlc]{1,7}"))||ChapterScanner.key(l.text) in marginal)
            }
            // Printed dot-leader contents are replaced by actual body destinations.
            val tocRows=filtered.indices.filter {tocLine.matches(filtered[it].text)}.toMutableSet()
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
            val bodyHeights=lines.map {it.box.height()}.filter {it>0}.sorted()
            val em=bodyHeights.getOrNull(bodyHeights.size/2)?.coerceAtLeast(5f)?:10f
            val left=lines.minOf {it.box.left};val right=lines.maxOf {it.box.right}
            val leading=lines.zipWithNext().map {(a,b)->b.box.top-a.box.top}.filter {it>em*.7f&&it<em*3}.sorted().let {it.getOrNull(it.size/2)?:em*1.6f}
            for((index,l) in lines.withIndex()) {
                val recognized=if(printed.any {ChapterScanner.key(it)==ChapterScanner.key(l.text)})if(l.text.startsWith("第"))1 else 2 else ChapterScanner.level(l.text)
                val displayHeading=l.text.length in 2..64&&l.box.height()>em*1.23f
                val heading=if(recognized>0)recognized else if(displayHeading)1 else 0
                val prev=previous
                val indented=l.box.left-left>em*1.15f
                val gap=prev!=null&&prev.page==i&&l.box.top-prev.box.top>leading*1.48f
                val shortPrevious=prev!=null&&prev.box.right<right-em*2.5f&&Regex("[。！？.!?：:]?[”’」』）)]?$").find(prev.text)?.value?.any {it in "。！？.!?：:"}==true
                val crossPage=prev!=null&&prev.page!=i
                val newParagraph=out.blocks.isEmpty()||heading>0||out.blocks.last().kind!="body"||indented||gap||shortPrevious||(crossPage&&prev!!.text.last() in "。！？.!?" )
                if(newParagraph)out.add(SourceBlock(l.text,if(heading>0)"heading" else "body",heading))
                else {out.blocks.last().text=BookImporter.joinProse(out.blocks.last().text,l.text);out.repairedBreaks++}
                previous=l
            }
        }
        return out
    }
}
