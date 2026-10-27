package com.inkreader.nativeink

/** Repair early flattened imports. Source navigation still requires the original file. */
internal object LegacyBookRepair {
    data class RepairEvent(val rule:String,val inputOffset:Int,val before:String,val after:String)
    data class Piece(val text:String,val sourceOffset:Int,val hardBreakBefore:Boolean)
    private fun key(s:String)=s.filterNot {it.isWhitespace()}.lowercase()
    private val contents=setOf("目录","目錄","contents","tableofcontents")
    fun pieces(value:String):List<Piece> {
        val normalized=StringBuilder();val positions=mutableListOf<Int>()
        value.forEachIndexed {i,c->if(!c.isWhitespace()){normalized.append(c.lowercaseChar());positions+=i}}
        val source=normalized.toString();var cursor=0;var previousEnd=0
        return lines(value).map {line->
            val token=key(line);val at=source.indexOf(token,cursor)
            if(at<0)Piece(line,-1,true) else {
                val start=positions[at];val end=positions[at+token.length-1]+1
                val hard=Regex("\\n\\s*\\n").containsMatchIn(value.substring(previousEnd.coerceAtMost(start),start))
                cursor=at+token.length;previousEnd=end;Piece(line,start,hard)
            }
        }
    }
    fun probablePdfWrap(previous:String,next:String):Boolean {
        if(next.isBlank()||previous.isEmpty()||previous.last() in "。！？.!?：:”’」』")return false
        val han=previous.count {it.code in 0x3400..0x9fff}
        return han>=12&&previous.length>=20||Regex("[A-Za-z]+").findAll(previous).count()>=6&&previous.length>=45
    }
    fun lines(value:String,trace:((RepairEvent)->Unit)?=null):List<String> {
        fun changed(rule:String,before:String,after:String){trace?.invoke(RepairEvent(rule,value.indexOf(before),before.take(160),after.take(160)))}
        var source=value.replace("\r","")
        // Early flattening copied repeated HTML document titles into the saved text.
        val titleMatches=Regex("(?im)^([^\\n<>]{1,96}?\\.x?html?)(?=\\s|$)([^\\n]*)").findAll(source).toList()
        val fileTitles=titleMatches.groupBy {it.groupValues[1]}.filterValues {matches->matches.size>=2&&matches.any {m->
            val tail=m.groupValues[2].trim();TextSemantics.englishTitle(tail)||Regex("^(?:Chapter|Part|Book|(?:A |An )?Note on)\\b").containsMatchIn(tail)
        }}.keys
        for(title in fileTitles){changed("repeated-document-title-with-heading-evidence",title,"");source=source.replace(Regex("(?m)^"+Regex.escape(title)+"[ \\t]*"),"")}
        source=source.replace(Regex("(?m)^(版权信息|版權資訊)(?=\\S)"),"$1\n")
        var copyright=false
        val fields=Regex("(?:作者|著者|译者|譯者|出版社|出版者|Author|Editor|Translator|Publisher|ISBN)[：:]",RegexOption.IGNORE_CASE)
        source=source.lineSequence().joinToString("\n") {line->
            val candidate=TextSemantics.canonicalFields(line)
            if(candidate.trim() in setOf("版权信息","版權資訊","Copyright Information"))copyright=true
            else if(ChapterScanner.level(candidate)>0||candidate.length>250)copyright=false
            val labels=fields.findAll(candidate).toList()
            val metadata=copyright||labels.firstOrNull()?.range?.first==0||labels.size>=2&&!Regex("[。！？.!?]").containsMatchIn(candidate.substringBefore(labels.getOrNull(1)?.value?:"\u0000"))
            var t=if(metadata)candidate else line
            if(t.length<=250&&metadata&&labels.isNotEmpty())t=t.replace(Regex("(?<=\\S)[ \t]*(?=(?:作者|著者|译者|譯者|出版社|出版者|Author|Editor|Translator|Publisher|ISBN)[：:])",RegexOption.IGNORE_CASE),"\n")
            t.replace(Regex("^((?:A |An )?Note on [A-Z][a-z]+(?: [A-Z][a-z]+){0,5})(?= [A-Z][a-z]+ [a-z])"),"$1\n")
                .also {if(it!=line)changed("metadata-or-front-matter-boundary",line,it)}
        }
        val original=source.lineSequence().map {it.trim()}.filter {it.isNotEmpty()}.toList()
        val leaders=Regex("[·.．…](?:[ \\t]*[·.．…]){3,}")
        val page=Regex("[（(]\\s*[0-9IVXLCivxlc]{1,7}\\s*[）)]")
        val removed=original.indices.filter {leaders.containsMatchIn(original[it])&&(page.containsMatchIn(original[it])||Regex("[0-9]{1,4}\\s*$").containsMatchIn(original[it])||original.getOrNull(it+1)?.let {next->page.find(next)?.range?.first==0}==true)}.toMutableSet()
        // A prose ellipsis or a technical example is not a contents page. Require a nearby cluster
        // and a contents/part label before removing dot-leader entries from a text-only book.
        val candidates=removed.toList()
        removed.removeAll {i->
            val neighbours=candidates.count {kotlin.math.abs(it-i)<=6}
            val labelled=(maxOf(0,i-5)..i).any {key(original[it]) in contents||Regex("^第.{1,35}[部卷篇]").containsMatchIn(original[it])}
            neighbours<2||neighbours<3&&!labelled
        }
        for(i in removed.toList()) {
            for(back in 1..2) {
                val previous=i-back
                if(previous>=0&&original[previous].length<110&&Regex("^(?:[0-9]{1,3}[.．]|第.+[部卷篇])").containsMatchIn(original[previous])&&(back==1||previous+1 in removed))removed+=previous
            }
            if(i+1<original.size&&original[i+1].matches(Regex("[（(]?[0-9IVXLCivxlc]{1,7}[）)]?")))removed+=i+1
        }
        removed.sorted().forEach {changed("clustered-printed-contents",original[it],"")}
        val input=original.filterIndexed {i,_->i !in removed}
        val start=input.indexOfFirst {key(it) in contents}
        val confirmed=start>=0&&input.drop(start+1).takeWhile {it.length<80}.count {ChapterScanner.level(it)>0||TextSemantics.englishTitle(it)}>=2
        val cleaned=if(confirmed) {
            val prose=(start+1 until input.size).firstOrNull {input[it].length>=80&&input[it].any {c->c in "。！？.!?"}}
            if(prose!=null) {
                var body=if(input[prose-1].length<=100)prose-1 else prose
                while(body>start+1&&key(input[body-1])==key(input[body]))body--
                changed("labelled-contents-before-real-prose",input.subList(start,body).joinToString("\n"),"")
                input.take(start)+input.drop(body)
            } else input
        } else input
        val result=mutableListOf<String>()
        for(line in cleaned) {
            if(confirmed&&key(line) in contents)continue
            // Old HTML imports included both <head><title> and the visible heading.
            val heading=line.length<=72&&(ChapterScanner.level(line)>0||line in setOf("版权信息","版權資訊","Copyright Information"))
            if(heading&&result.lastOrNull()?.let {key(it)==key(line)}==true){changed("adjacent-duplicate-short-heading",line,"");continue}
            result+=line
        }
        return result
    }
}
