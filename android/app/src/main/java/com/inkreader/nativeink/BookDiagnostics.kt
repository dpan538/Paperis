package com.inkreader.nativeink

import android.text.StaticLayout
import org.json.JSONObject

/** Detection only: findings locate a problem and never authorize deleting or rewriting its text. */
internal object BookDiagnostics {
    data class Finding(val category:String,val block:Int,val offset:Int,val page:Int=-1,val line:Int=-1,val evidence:String="",val origin:String="") {
        val severity get()=if(category.startsWith("INVALID_")||category in setOf("EXCESSIVE_SPACING","EMPTY_PAGE"))"error" else "review"
        fun json()=JSONObject().put("category",category).put("severity",severity).put("origin",origin).put("block",block).put("offset",offset).put("page",page).put("line",line).put("evidence",evidence)
    }
    fun spaces(text:String,font:Float,native:(Int)->StaticLayout,lines:Int,captured:Array<TextDrawProbe.Metrics>?=null):List<Finding> {
        val result=mutableListOf<Finding>()
        val probes=java.util.IdentityHashMap<StaticLayout,Array<TextDrawProbe.Metrics>>()
        for(line in 0 until lines) {
            val layout=native(line)
            val metric=(captured?:probes.getOrPut(layout){TextDrawProbe.capture(layout)})[line]
            val at=(layout.getLineStart(line) until layout.getLineEnd(line)).firstOrNull {text[it]==' '}?:continue
            if(metric.width>font*.65f)result+=Finding("EXCESSIVE_SPACING",-1,at,line=line,evidence="drawnSpacePx=${metric.width} addedPx=${metric.added} fontPx=$font")
        }
        return result
    }
    fun inspect(book:ParsedBook,layout:BookLayout):List<Finding> {
        val result=mutableListOf<Finding>();val text=book.text;val blocks=book.structure.blocks
        val fragments=BookTextRepair.english(blocks.map {if(it.start>=0&&it.end<=text.length&&it.start<it.end)text.substring(it.start,it.end)else ""})
        fun finding(category:String,i:Int,at:Int=blocks.getOrNull(i)?.start?:0,detail:String="") {
            result+=Finding(category,i,at,layout.pageForOffset(at.coerceIn(0,text.lastIndex)),layout.layout.getLineForOffset(at.coerceIn(0,text.lastIndex)),detail.ifEmpty {text.substring(at.coerceIn(0,text.length),minOf(text.length,at+100))},blocks.getOrNull(i)?.origin.orEmpty())
        }
        val filePattern=Regex("(?i)^[^\n<>]{1,96}\\.x?html?(?:$| +(?:Chapter|Part|A Note)\\b)")
        for((i,block) in blocks.withIndex()) {
            if(block.start<0||block.end>text.length||block.start>=block.end){finding("INVALID_BLOCK_RANGE",i,0);continue}
            val value=text.substring(block.start,block.end)
            if(block.kind=="body"&&fragments[i]!=value) {
                val changed=value.indices.firstOrNull {it>=fragments[i].length||value[it]!=fragments[i][it]}?:0
                finding("ENGLISH_FRAGMENT",i,block.start+changed,"${value.take(100)} -> ${fragments[i].take(100)}")
            }
            if(block.kind !in setOf("verse","caption")&&filePattern.containsMatchIn(value))finding("HTML_FILE_HEADER",i)
            val leaders=Regex("[·.．…]{4,}").findAll(value).count()
            if(block.kind!="verse"&&leaders>=2&&Regex("[（(][0-9]{1,4}[）)]").containsMatchIn(value))finding("PRINTED_CONTENTS_REMAINS",i)
            if(block.kind !in setOf("verse","quote")&&value.length<=250&&Regex("(?:作者|出版社|出版者|Author|Editor|Translator|Publisher|ISBN)[：:]",RegexOption.IGNORE_CASE).findAll(TextSemantics.canonicalFields(value)).count()>=2)finding("METADATA_BOUNDARY",i)
            val previous=blocks.getOrNull(i-1)
            if(block.kind=="heading"&&previous?.kind=="heading"&&ChapterScanner.key(value)==ChapterScanner.key(text.substring(previous.start,previous.end)))finding("DUPLICATE_HEADING",i)
        }
        for(chapter in book.structure.chapters)if(chapter.offset !in text.indices)finding("INVALID_CHAPTER_DESTINATION",-1,0,chapter.title)
        layout.pages.forEachIndexed {page,columns->if(text.substring(columns.first().start,columns.last().end).isBlank())result+=Finding("EMPTY_PAGE",-1,columns.first().start,page)}
        result+=spaces(text,layout.font,{layout.layout},layout.layout.lineCount,TextDrawProbe.capture(layout.layout){layout.drawNativeText(it)}).map {f->val index=blocks.indexOfFirst {f.offset in it.start until it.end};f.copy(block=index,page=layout.pageForOffset(f.offset),origin=blocks.getOrNull(index)?.origin.orEmpty())}.filter {f->blocks.getOrNull(f.block)?.kind=="body"}
        return result
    }
}
