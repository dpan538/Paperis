package com.inkreader.nativeink

import android.graphics.BitmapFactory
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject

internal data class ReflowWindow(val width:Float,val height:Float) {
    fun json()=JSONObject().put("width",width).put("height",height)
    companion object {
        fun read(value:JSONObject?):ReflowWindow?=value?.let {
            val w=it.optDouble("width").toFloat();val h=it.optDouble("height").toFloat()
            if(w.isFinite()&&h.isFinite()&&w in 240f..4096f&&h in 240f..4096f)ReflowWindow(w,h) else null
        }
    }
}

/** Production pre-commit audit. Only evidence-backed local edits may improve a derived copy. */
internal object ReflowGuard {
    const val MAX_REPAIRS=3
    data class Profile(val name:String,val width:Float,val height:Float,val font:Float,val spacing:Float=.67f,val columns:Int=0,val face:Typeface=Typeface.create("serif",Typeface.NORMAL))
    data class Issue(val finding:BookDiagnostics.Finding,val profile:String="structure") {
        fun json()=finding.json().put("profile",profile)
    }
    data class Audit(val issues:List<Issue>,val profiles:JSONArray) {
        val errors get()=issues.count {it.finding.severity=="error"}
        val reviews get()=issues.size-errors
        fun betterThan(other:Audit)=errors<other.errors||errors==other.errors&&reviews<other.reviews
        fun json()=JSONObject().put("errors",errors).put("reviews",reviews).put("findings",JSONArray(issues.map {it.json()})).put("profiles",profiles)
    }
    data class Result(val book:ParsedBook,val report:JSONObject)
    data class Edit(val start:Int,val end:Int,val replacement:String,val pieces:List<Piece>,val rule:String)
    data class Piece(val text:String,val kind:String,val level:Int=0)
    data class Repair(val book:ParsedBook,val events:List<JSONObject>,val sourceEdits:List<Edit> = emptyList())

    fun audit(book:ParsedBook,profiles:List<Profile>):Audit {
        val issues=mutableListOf<Issue>();val rendered=JSONArray();val text=book.text;val blocks=book.structure.blocks
        fun issue(category:String,index:Int,offset:Int=blocks.getOrNull(index)?.start?:0,detail:String="") {
            issues+=Issue(BookDiagnostics.Finding(category,index,offset,evidence=detail,origin=blocks.getOrNull(index)?.origin.orEmpty()))
        }
        if(text.isBlank()){issue("INVALID_EMPTY_BOOK",-1,0);return Audit(issues,rendered)}
        var cursor=0
        blocks.forEachIndexed {i,b->
            if(b.start<cursor||b.start<0||b.end>text.length||b.start>=b.end)issue("INVALID_BLOCK_RANGE",i)
            else {if(text.substring(cursor,b.start).isNotBlank())issue("INVALID_BLOCK_COVERAGE",i,cursor);cursor=b.end}
            if(b.kind=="image") {
                val image=book.images[b.image]
                val info=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                if(image?.isFile==true)BitmapFactory.decodeFile(image.path,info)
                if(image?.isFile!=true||info.outWidth !in 1..20000||info.outHeight !in 1..20000)issue("INVALID_IMAGE_ASSET",i,b.start,b.image)
            }
        }
        if(cursor<text.length&&text.substring(cursor).isNotBlank())issue("INVALID_BLOCK_COVERAGE",-1,cursor)
        book.structure.chapters.forEach {chapter->
            if(chapter.offset !in text.indices)issue("INVALID_CHAPTER_DESTINATION",-1,0,chapter.title)
            else if(chapter.source=="generated") {
                val target=blocks.firstOrNull {chapter.offset in it.start until it.end}
                if(target==null||target.kind !in setOf("heading","subtitle")||!chapterMatches(text,chapter,target))issue("GENERATED_CHAPTER_TARGET",-1,chapter.offset,chapter.title)
            }
        }
        // Do not replace an authoritative EPUB directory with every short line in the book.
        val hasSource=book.structure.chapters.any {it.source=="epub"}
        val firstSource=book.structure.chapters.filter {it.source=="epub"}.minOfOrNull {it.offset}?:0
        val lastSource=book.structure.chapters.filter {it.source=="epub"}.maxOfOrNull {it.offset}?:Int.MAX_VALUE
        blocks.forEachIndexed {i,b->
            if(b.start<0||b.end>text.length||b.start>=b.end)return@forEachIndexed
            val value=text.substring(b.start,b.end)
            val known=ChapterScanner.level(value)
            if(b.kind=="body"&&known>0)issue("BODY_HEADING",i)
            if(b.kind=="heading"&&b.start>0&&known>0&&ChapterScanner.key(value)!=ChapterScanner.key(book.title)&&book.structure.chapters.none {it.offset==b.start}&&
                (!hasSource||b.start<firstSource||b.start>lastSource)&&(!hasSource||!Regex("^(?:第|卷|篇|Chapter|Part|Book)",RegexOption.IGNORE_CASE).containsMatchIn(value.trim())))issue("MISSING_CHAPTER",i)
        }
        // Invalid ranges must never reach Android's span/layout APIs.
        if(issues.any {it.finding.category in setOf("INVALID_BLOCK_RANGE","INVALID_BLOCK_COVERAGE","INVALID_EMPTY_BOOK","INVALID_IMAGE_ASSET")})return Audit(issues,rendered)
        val sizes=mutableMapOf<String,Pair<Int,Int>>()
        fun imageSize(key:String)=sizes[key]?:book.images[key]?.let {file->
            val info=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,info)
            if(info.outWidth>0&&info.outHeight>0)(info.outWidth to info.outHeight).also {sizes[key]=it} else null
        }
        for(profile in profiles) {
            check(!Thread.currentThread().isInterrupted){"已暂停，整理进度已保留"}
            try {
                val layout=BookLayout(text,profile.width,profile.height,profile.font,profile.spacing,profile.columns,profile.face,structure=book.structure,imageSize=::imageSize)
                val found=BookDiagnostics.inspect(book,layout).toMutableList()
                if(!book.structure.comic&&!PaginationGuard.coverage(text,layout.pages,layout.layout.lineCount))found+=BookDiagnostics.Finding("INVALID_PAGE_COVERAGE",-1,0)
                layout.pages.forEachIndexed {page,columns->columns.forEach {column->
                    if(column.firstLine !in 0 until layout.layout.lineCount||column.lastLine !in column.firstLine until layout.layout.lineCount)found+=BookDiagnostics.Finding("INVALID_COLUMN_LINES",-1,column.start,page)
                    else if(layout.layout.getLineBottom(column.lastLine)-layout.layout.getLineTop(column.firstLine)>layout.bodyHeight+.5f)found+=BookDiagnostics.Finding("INVALID_VERTICAL_OVERFLOW",-1,column.start,page,column.firstLine)
                }}
                for(line in 0 until layout.layout.lineCount)if(layout.layout.getLineLeft(line)<-.5f||layout.layout.getLineRight(line)>layout.columnWidth+.5f) {
                    val at=layout.layout.getLineStart(line);val block=blocks.indexOfFirst {at in it.start until it.end}
                    found+=BookDiagnostics.Finding("INVALID_HORIZONTAL_OVERFLOW",block,at,layout.pageForOffset(at),line,origin=blocks.getOrNull(block)?.origin.orEmpty())
                }
                issues+=found.map {Issue(it,profile.name)}
                rendered.put(JSONObject().put("name",profile.name).put("width",profile.width).put("height",profile.height).put("font",profile.font).put("pages",layout.pages.size).put("paginationRepairs",JSONArray(layout.paginationRepairs)))
            } catch(t:Exception) {
                if(Thread.currentThread().isInterrupted)throw t
                issues+=Issue(BookDiagnostics.Finding("INVALID_NATIVE_LAYOUT",-1,0,evidence=t.javaClass.simpleName+": "+t.message.orEmpty().take(160)),profile.name)
            }
        }
        return Audit(issues.distinctBy {listOf(it.finding.category,it.finding.block,it.finding.offset,it.profile)},rendered)
    }

    /** The best checked candidate wins. Rejected edits never reach BookFiles.commit. */
    fun run(book:ParsedBook,profiles:List<Profile>,progress:(Int)->Unit={},repairer:(ParsedBook,List<Issue>)->Repair=::repair):Result {
        require(profiles.isNotEmpty())
        var best=book;progress(1);var checked=audit(best,profiles);val initial=checked.json();val rounds=JSONArray();val seen=mutableSetOf(fingerprint(book));var stop="clean"
        for(round in 1..MAX_REPAIRS) {
            check(!Thread.currentThread().isInterrupted){"已暂停，整理进度已保留"}
            if(checked.issues.isEmpty())break
            val candidate=repairer(best,checked.issues);val hash=fingerprint(candidate.book)
            if(candidate.events.isEmpty()||hash==fingerprint(best)){stop="no-safe-change";break}
            if(!seen.add(hash)){stop="cycle";break}
            progress(round+1)
            val next=audit(candidate.book,profiles)
            val protected=protectedContent(best)==protectedContent(candidate.book)
            val intact=sourceContentIntact(best,candidate)
            val oldErrors=checked.issues.filter {it.finding.severity=="error"}.map {it.finding.category}.toSet()
            val noNewErrors=next.issues.none {it.finding.severity=="error"&&it.finding.category !in oldErrors}
            val accepted=protected&&intact&&noNewErrors&&next.betterThan(checked)
            rounds.put(JSONObject().put("round",round).put("accepted",accepted).put("protectedContentIntact",protected).put("sourceContentIntact",intact).put("edits",JSONArray(candidate.events)).put("audit",next.json()))
            if(!accepted){stop="no-improvement";break}
            best=candidate.book;checked=next;stop=if(checked.issues.isEmpty())"clean" else "limit"
        }
        val report=JSONObject().put("schema",1).put("maxRepairs",MAX_REPAIRS).put("stop",stop).put("initial",initial).put("rounds",rounds).put("final",checked.json()).put("passed",checked.errors==0)
        return Result(best,report)
    }
    private fun fingerprint(book:ParsedBook)=BookFiles.hashBytes((book.text+"\u0000"+book.structure.json()).toByteArray())
    private fun chapterMatches(text:String,chapter:BookChapter,block:BookBlock):Boolean {
        if(block.start<0||block.end>text.length||block.start>=block.end)return false
        val label=ChapterScanner.key(chapter.title);val target=ChapterScanner.key(text.substring(block.start,block.end))
        return label==target||label.startsWith("$target·")
    }
    private fun protectedContent(book:ParsedBook)=book.structure.blocks.filter {it.kind in setOf("image","verse","quote","caption")}.map {listOf(it.kind,it.image,book.text.substring(it.start.coerceIn(0,book.text.length),it.end.coerceIn(it.start.coerceIn(0,book.text.length),book.text.length)))} to book.images.keys
    private fun sourceContentIntact(before:ParsedBook,repair:Repair):Boolean {
        fun key(s:String)=s.filterNot(Char::isWhitespace)
        for(e in repair.sourceEdits) {
            if(e.start<0||e.end>before.text.length||e.start>=e.end)return false
            val value=before.text.substring(e.start,e.end)
            if(key(value)==key(e.replacement))continue
            if(e.rule=="remove-proven-synthetic-title-duplicate"&&before.structure.blocks.firstOrNull()?.origin=="paperis:book-title"&&key(value)==key(before.title)&&e.replacement.isEmpty())continue
            if(e.rule in setOf("repeated-document-title-with-heading-evidence","document-title-and-front-matter-boundary","repeated-empty-document-title-before-heading")) {
                val name=Regex("(?i)^[^\n<>]{1,96}?\\.x?html?").find(value)?.value?:return false
                if(key(value.removePrefix(name))==key(e.replacement))continue
            }
            return false
        }
        return runCatching {
            val expected=applyEdits(before,repair.sourceEdits)
            expected.text==repair.book.text&&expected.structure.chapters.filter {it.source=="epub"}==repair.book.structure.chapters.filter {it.source=="epub"}
        }.getOrDefault(false)
    }

    fun repair(book:ParsedBook,issues:List<Issue>):Repair {
        val text=book.text;val blocks=book.structure.blocks
        if(blocks.any {it.start<0||it.end>text.length||it.start>=it.end}||blocks.zipWithNext().any {(a,b)->a.end>b.start})return Repair(book,emptyList())
        val findings=issues.map {it.finding}.distinctBy {it.category to it.block}
        val texts=blocks.map {text.substring(it.start,it.end)}
        val english=BookTextRepair.english(texts)
        val edits=mutableListOf<Edit>();val events=mutableListOf<JSONObject>()
        fun add(index:Int,pieces:List<Piece>,rule:String) {
            val b=blocks[index];val replacement=pieces.joinToString("\n\n"){it.text}
            if(replacement==texts[index]&&pieces.size==1&&pieces[0].kind==b.kind&&pieces[0].level==b.level)return
            edits+=Edit(b.start,b.end,replacement,pieces,rule)
            events+=JSONObject().put("rule",rule).put("block",index).put("offset",b.start).put("origin",b.origin).put("before",texts[index].take(160)).put("after",replacement.take(160))
        }
        val fields=Regex("(?:作者|著者|译者|譯者|出版社|出版者|Author|Editor|Translator|Publisher|ISBN)[：:]",RegexOption.IGNORE_CASE)
        val syntheticDuplicate=blocks.size>=2&&blocks[0].origin=="paperis:book-title"&&blocks[1].kind=="heading"&&ChapterScanner.key(texts[0])==ChapterScanner.key(texts[1])
        if(syntheticDuplicate&&findings.any {it.category=="DUPLICATE_HEADING"&&it.block==1}) {
            edits+=Edit(blocks[0].start,blocks[1].start,"",emptyList(),"remove-proven-synthetic-title-duplicate")
            events+=JSONObject().put("rule","remove-proven-synthetic-title-duplicate").put("block",0).put("offset",0).put("origin",blocks[0].origin).put("before",texts[0].take(160)).put("after","")
        }
        for((i,b)in blocks.withIndex()) {
            val categories=findings.filter {it.block==i}.map {it.category}.toSet();val value=texts[i]
            if(b.kind in setOf("image","verse","quote","caption"))continue
            if(syntheticDuplicate&&i==0)continue
            if("METADATA_BOUNDARY" in categories) {
                val canonical=TextSemantics.canonicalFields(value);val labels=fields.findAll(canonical).toList()
                val prefix=canonical.substring(0,labels.firstOrNull()?.range?.first?:canonical.length).trim()
                val eligible=b.kind=="metadata"||prefix in setOf("版权信息","版權資訊","Copyright Information")
                if(eligible&&labels.size>=2) {
                    val pieces=mutableListOf<Piece>();if(prefix.isNotEmpty())pieces+=Piece(prefix,"heading",2)
                    labels.forEachIndexed {n,m->pieces+=Piece(canonical.substring(m.range.first,labels.getOrNull(n+1)?.range?.first?:canonical.length).trim(),"metadata")}
                    add(i,pieces,"split-labelled-metadata");continue
                }
            }
            if("ENGLISH_FRAGMENT" in categories&&b.kind=="body"&&english[i]!=value) {add(i,listOf(Piece(english[i],b.kind,b.level)),"same-book-english-evidence");continue}
            if("BODY_HEADING" in categories&&b.kind=="body") {add(i,listOf(Piece(value,"heading",ChapterScanner.level(value))),"standalone-known-heading");continue}
            if("HTML_FILE_HEADER" in categories&&b.kind=="body") {
                val match=Regex("(?i)^([^\n<>]{1,96}?\\.x?html?)(?: +|$)(.*)$").find(value)
                if(match!=null) {
                    val name=match.groupValues[1];val tail=match.groupValues[2]
                    val repeated=texts.count {it==name||it.startsWith("$name ")}>=2
                    val heading=ChapterScanner.level(tail)>0||TextSemantics.englishTitle(tail)
                    if(repeated&&heading){add(i,listOf(Piece(tail,"heading",ChapterScanner.level(tail).coerceAtLeast(1))),"repeated-document-title-with-heading-evidence");continue}
                    val note=Regex("^((?:A |An )?Note on [A-Z][a-z]+(?: [A-Z][a-z]+){0,5})(?= [A-Z][a-z]+ [a-z])").find(tail)
                    if(repeated&&note!=null){add(i,listOf(Piece(note.value,"heading",1),Piece(tail.removePrefix(note.value).trim(),"body")),"document-title-and-front-matter-boundary");continue}
                    val adjacent=blocks.getOrNull(i+1)?.let {it.kind=="heading"&&ChapterScanner.level(texts[i+1])>0}==true
                    val headingEvidence=texts.any {it.startsWith("$name ")&&(ChapterScanner.level(it.removePrefix("$name "))>0||TextSemantics.englishTitle(it.removePrefix("$name ")))}
                    if(repeated&&tail.isBlank()&&adjacent&&headingEvidence){add(i,emptyList(),"repeated-empty-document-title-before-heading");continue}
                }
            }
        }
        val changed=applyEdits(book,edits)
        val chapters=changed.structure.chapters.toMutableList();val changedBlocks=changed.structure.blocks
        // Repair an ambiguous generated destination only when a unique semantic heading proves it.
        for((index,chapter)in chapters.toList().withIndex())if(chapter.source=="generated") {
            val target=changedBlocks.firstOrNull {chapter.offset in it.start until it.end}
            if(chapter.offset !in changed.text.indices||target?.kind !in setOf("heading","subtitle")||target!=null&&!chapterMatches(changed.text,chapter,target)) {
                val matches=changedBlocks.filter {it.kind=="heading"&&ChapterScanner.key(changed.text.substring(it.start,it.end))==ChapterScanner.key(chapter.title)}
                if(matches.size==1) {chapters[index]=chapter.copy(offset=matches.single().start);events+=JSONObject().put("rule","unique-generated-chapter-destination").put("beforeOffset",chapter.offset).put("afterOffset",matches.single().start)}
            }
        }
        val hasSource=chapters.any {it.source=="epub"};val sourceFirst=chapters.filter {it.source=="epub"}.minOfOrNull {it.offset}?:0;val sourceLast=chapters.filter {it.source=="epub"}.maxOfOrNull {it.offset}?:Int.MAX_VALUE
        for(b in changedBlocks)if(b.kind=="heading"&&b.start>0&&chapters.none {it.offset==b.start}) {
            val title=changed.text.substring(b.start,b.end);val level=ChapterScanner.level(title)
            if(level>0&&ChapterScanner.key(title)!=ChapterScanner.key(book.title)&&(!hasSource||b.start<sourceFirst||b.start>sourceLast)&&(!hasSource||!Regex("^(?:第|卷|篇|Chapter|Part|Book)",RegexOption.IGNORE_CASE).containsMatchIn(title.trim()))) {
                chapters+=BookChapter(title,b.start,level);events+=JSONObject().put("rule","missing-reliable-chapter").put("offset",b.start)
            }
        }
        val source=if(chapters.any {it.source=="epub"})if(chapters.any {it.source=="generated"})"mixed" else "epub" else "generated"
        return Repair(changed.copy(structure=changed.structure.copy(chapters=chapters.sortedBy {it.offset},directorySource=source)),events,edits)
    }

    private fun applyEdits(book:ParsedBook,edits:List<Edit>):ParsedBook {
        if(edits.isEmpty())return book
        val ordered=edits.sortedBy {it.start};val out=StringBuilder();var cursor=0
        for(edit in ordered){require(edit.start>=cursor);out.append(book.text,cursor,edit.start).append(edit.replacement);cursor=edit.end};out.append(book.text,cursor,book.text.length)
        fun map(at:Int):Int {var delta=0;for(e in ordered){if(at<e.start)break;if(at<e.end)return e.start+delta+(at-e.start).coerceAtMost(e.replacement.length);delta+=e.replacement.length-(e.end-e.start)};return at+delta}
        val blocks=book.structure.blocks.flatMap {b->
            val edit=ordered.firstOrNull {it.start<=b.start&&it.end>=b.end}
            if(edit==null)listOf(b.copy(start=map(b.start),end=map(b.end))) else {
                var at=map(b.start);edit.pieces.map {piece->b.copy(start=at,end=at+piece.text.length,kind=piece.kind,level=piece.level).also {at+=piece.text.length+2}}
            }
        }
        return book.copy(text=out.toString(),structure=book.structure.copy(blocks=blocks,chapters=book.structure.chapters.map {it.copy(offset=map(it.offset))}))
    }
}
