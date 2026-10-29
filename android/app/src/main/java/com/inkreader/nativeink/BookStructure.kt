package com.inkreader.nativeink

import org.json.JSONArray
import org.json.JSONObject

internal data class BookBlock(val start:Int,val end:Int,val kind:String="body",val level:Int=0,val image:String="",val page:Int=-1,val origin:String="")
internal data class BookChapter(val title:String,val offset:Int,val level:Int=1,val source:String="generated")
internal data class BookStructure(val blocks:List<BookBlock>,val chapters:List<BookChapter>,val directorySource:String="generated",val repairedBreaks:Int=0,val ocrPages:Int=0,val uncertainLines:Int=0,val comic:Boolean=false) {
    fun json()=JSONObject().put("schema",SCHEMA).put("revision",REVISION).put("source",directorySource).put("repairedBreaks",repairedBreaks).put("ocrPages",ocrPages).put("uncertainLines",uncertainLines).put("comic",comic)
        .put("blocks",JSONArray().apply { blocks.forEach { put(JSONArray(listOf(it.start,it.end,it.kind,it.level,it.image,it.page,it.origin))) } })
        .put("chapters",JSONArray().apply { chapters.forEach { put(JSONArray(listOf(it.title,it.offset,it.level,it.source))) } })
    companion object {
        const val SCHEMA=2
        const val REVISION=4
        fun read(value:String,text:String):BookStructure {
            val o=JSONObject(value);require(o.getInt("schema")==SCHEMA)
            val b=o.getJSONArray("blocks");val c=o.getJSONArray("chapters")
            val blocks=(0 until b.length()).map { b.getJSONArray(it).let { a->BookBlock(a.getInt(0),a.getInt(1),a.getString(2),a.getInt(3),a.optString(4),a.optInt(5,-1),a.optString(6)) } }
            require(blocks.all { it.start>=0 && it.end<=text.length && it.start<it.end })
            val chapters=(0 until c.length()).map {c.getJSONArray(it).let {a->BookChapter(a.getString(0),a.getInt(1),a.getInt(2),a.getString(3))}}
            require(chapters.all {it.offset in text.indices})
            return BookStructure(blocks,chapters,o.optString("source","generated"),o.optInt("repairedBreaks"),o.optInt("ocrPages"),o.optInt("uncertainLines"),o.optBoolean("comic",false))
        }
        fun infer(text:String):BookStructure {
            val blocks=mutableListOf<BookBlock>();var at=0
            val lines=text.split('\n')
            val following=IntArray(lines.size){-1};var seen=-1
            for(i in lines.indices.reversed()){following[i]=seen;if(lines[i].isNotBlank())seen=i}
            val candidates=lines.indices.filter {i->i>0&&lines.getOrNull(i-1)?.isBlank()==true&&lines.getOrNull(i+1)?.isBlank()==true&&TextSemantics.shortChinese(lines[i].trim())&&following[i]>=0&&lines[following[i]].length>=40}.toSet()
            lines.forEachIndexed {i,line->
                if(line.isNotBlank()) {
                    val t=line.trim();val isolated=lines.getOrNull(i-1)?.isBlank()==true&&lines.getOrNull(i+1)?.isBlank()==true
                    val known=ChapterScanner.level(t)
                    val level=if(at==0)1 else if(known>0)known else if(isolated&&TextSemantics.englishTitle(t)||candidates.size>=3&&i in candidates)2 else 0
                    val kind=if(level>0)"heading" else if(TextSemantics.metadata(t))"metadata" else "body"
                    blocks+=BookBlock(at,at+line.length,kind,level)
                };at+=line.length+1
            }
            return BookStructure(blocks,blocks.filter {it.kind=="heading" && it.start>0}.map {BookChapter(text.substring(it.start,it.end),it.start,it.level)})
        }
    }
}

/** Conservative offline candidates, never a fabricated equal-length chapter list. */
internal object ChapterScanner {
    private val numbered=Regex("^(?:第[零〇一二三四五六七八九十百千万兩两0-9]+[部卷篇章节章節回集]|[卷篇][零〇一二三四五六七八九十百0-9]+|(?:Chapter|Part|Book)\\s+(?:[IVXLCDM]+|[0-9]+|One|Two|Three|Four|Five|Six|Seven|Eight|Nine|Ten)\\b)",RegexOption.IGNORE_CASE)
    private val section=Regex("^(?:[一二三四五六七八九十百]+[、　 ]|[0-9]{1,3}[.．、]\\s*\\D|[0-9]{1,2}\\s+[\\p{IsHan}])")
    private val special=Regex("^(?:自序|序言|前言|序章|後記|后记|跋|導論|导论|引言|結語|结语|結論|结论|附錄|附录|譯者前言|译者前言|譯者的話|译者的话|序|小引|凡例|題記|题记|獻詞|献词|致讀者|致读者|出版說明|出版说明|編者按|编者按|編輯說明|编辑说明|譯序|译序|譯後記|译后记|致謝|致谢|謝辭|谢辞|跋語|跋语|參考文獻|参考文献|注釋|注释|註釋|索引|附註|附注|延伸閱讀|延伸阅读|作者簡介|作者简介|譯者簡介|译者简介|Preface|Foreword|Introduction|Prologue|Epilogue|Afterword|Acknowledg(?:e)?ments|Dedication|References|Bibliography|Endnotes|Notes|Index|About the Author|About the Translator|Translator[’']s (?:Note|Preface)|Editor[’']s Note|List of (?:Illustrations|Figures|Tables)|Further Reading)(?:[：:　 ].{0,35})?$",RegexOption.IGNORE_CASE)
    private val englishMatter=Regex("^(?:Preface|Foreword|Introduction|Prologue|Epilogue|Afterword|Acknowledg(?:e)?ments|Dedication|References|Bibliography|Endnotes|Notes|Index|About the Author|About the Translator|Translator[’']s (?:Note|Preface)|Editor[’']s Note|List of (?:Illustrations|Figures|Tables)|Further Reading)(?:\\s*[:—-]\\s*.{1,35}|\\s*\\([^)]{1,30}\\))?$",RegexOption.IGNORE_CASE)
    fun key(s:String)=s.filterNot {it.isWhitespace()}.lowercase()
    fun level(value:String):Int {
        val t=value.trim().replace(Regex("[\\p{Zs}]+")," ")
        if(t.length !in 1..72 || Regex("[。！？!?；;]|…|[·.．]{4,}").containsMatchIn(t))return 0
        if(numbered.containsMatchIn(t))return if(Regex("^(?:第.+[部卷篇]|[卷篇]|Part|Book)",RegexOption.IGNORE_CASE).containsMatchIn(t))1 else 2
        val latin=t.any {it in 'A'..'Z'||it in 'a'..'z'}&&!t.any {it.code in 0x3400..0x9fff}
        if(latin&&Regex("^(?:A |An )?Note on .{2,50}$").matches(t)&&TextSemantics.englishTitle(t))return 1
        if(if(latin)englishMatter.matches(t) else special.matches(t)||special.matches(key(t)))return 1
        if(section.containsMatchIn(t) && !t.endsWith('.') && t.length<=42 && !Regex("[，,：“”]").containsMatchIn(t)) {
            if(Regex("^[0-9]{1,2}\\s+[\\p{IsHan}]").containsMatchIn(t) && (t.length>18||Regex("[页頁行年图圖]").containsMatchIn(t)))return 0
            return 2
        }
        return 0
    }
}

internal data class SourceBlock(var text:String,var kind:String="body",var level:Int=0,val anchors:MutableList<String> = mutableListOf(),var continuation:Boolean=false,val imageKey:String="",val page:Int=-1,var explicitHeading:Boolean=false,val navigationTarget:String="",val origin:String="")
internal class BookAssembly {
    val blocks=mutableListOf<SourceBlock>()
    val images=mutableMapOf<String,java.io.File>()
    var titleHint=""
    var repairedBreaks=0
    var ocrPages=0
    var uncertainLines=0
    var comic=false
    fun add(block:SourceBlock) { if(block.text.isNotBlank())blocks+=block }
    fun finish(title:String,format:String,nav:List<Triple<String,Pair<Int,Int>,String>> = emptyList(),authoritative:Boolean=false):ParsedBook {
        val repaired=BookTextRepair.english(blocks.map {it.text})
        blocks.forEachIndexed {i,b->if(b.kind !in setOf("verse","image","quote","caption"))b.text=repaired[i]}
        blocks.filter {it.kind=="body"}.forEach {b->
            val level=ChapterScanner.level(b.text)
            if(level>0){b.kind="heading";b.level=level}else if(TextSemantics.metadata(b.text))b.kind="metadata"
        }
        val inserted=!comic&&blocks.firstOrNull()?.text!=title
        if(inserted) blocks.add(0,SourceBlock(title,"heading",1,origin="paperis:book-title")) else if(!comic&&blocks.isNotEmpty()) { blocks[0].kind="heading";blocks[0].level=1 }
        val combined=mutableMapOf<Int,String>()
        if(nav.isEmpty())for((i,b) in blocks.withIndex()) {
            val key=ChapterScanner.key(b.text)
            if(b.kind=="heading"&&(Regex("^第[一二三四五六七八九十0-9]+[部卷篇]$").matches(key)||key in setOf("導論","导论","結語","结语"))) {
                val next=(i+1 until minOf(i+5,blocks.size)).firstOrNull {blocks[it].kind=="heading"&&blocks[it].text.length in 2..50}
                if(next!=null){combined[i]=b.text.trim()+" · "+blocks[next].text.trim();blocks[next].kind="subtitle";blocks[next].level=0}
            }
        }
        val text=StringBuilder();val ranges=mutableListOf<BookBlock>();val chapters=mutableListOf<BookChapter>()
        blocks.forEach { b->
            if(text.isNotEmpty())text.append(if(comic)"\n" else "\n\n")
            val start=text.length;text.append(b.text);ranges+=BookBlock(start,text.length,b.kind,b.level,b.imageKey,b.page,b.origin.ifEmpty {if(b.page>=0)"pdf:page=${b.page+1}" else ""})
        }
        nav.forEach { (label,target,source)->
            // The parser passes a block index before the synthetic book-title insertion.
            val index=target.first+(if(inserted)1 else 0)
            if(index in ranges.indices)chapters+=BookChapter(label,ranges[index].start,target.second,source)
        }
        val sourceFirst=chapters.minOfOrNull {it.offset}?:0
        val sourceLast=chapters.maxOfOrNull {it.offset}?:Int.MAX_VALUE
        ranges.forEachIndexed {i,b->
            val label=combined[i]?:text.substring(b.start,b.end)
            val matter=ChapterScanner.level(label)>0&&!Regex("^(?:第|卷|篇|Chapter|Part|Book)",RegexOption.IGNORE_CASE).containsMatchIn(label.trim())
            val outsideSourceRange=b.start<sourceFirst||b.start>sourceLast
            val supplementary=if(authoritative)matter&&outsideSourceRange else nav.isEmpty() || b.level<=2 && label.any {it.isLetter()} && !label.trim().matches(Regex("[一二三四五六七八九十百千零〇0-9IVXLCDMivxlcdm]+"))
            if(supplementary&&b.kind=="heading"&&b.start>0&&(label.any {it.isLetter()}||blocks[i].explicitHeading&&label.any {it.isDigit()})&&ChapterScanner.key(label)!=ChapterScanner.key(title)&&chapters.none {it.offset==b.start})chapters+=BookChapter(label,b.start,b.level)
        }
        val source=if(chapters.any {it.source=="epub"})if(chapters.any {it.source=="generated"})"mixed" else "epub" else "generated"
        return ParsedBook(title,text.toString(),format,BookStructure(ranges,chapters.distinctBy {it.offset to ChapterScanner.key(it.title)}.sortedBy {it.offset},source,repairedBreaks,ocrPages,uncertainLines,comic),images)
    }
}
