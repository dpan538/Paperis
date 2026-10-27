package com.inkreader.nativeink

import android.text.Html

/** Small inert HTML reader: block semantics and local anchors, never a web renderer. */
internal object StructuredHtml {
    fun attribute(tag:String,name:String):String?=Regex("(?:^|\\s)${Regex.escape(name)}\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",RegexOption.IGNORE_CASE).find(tag)?.let {it.groupValues[1].ifEmpty {it.groupValues[2]}}
    fun plain(s:String)=Html.fromHtml(s,Html.FROM_HTML_MODE_COMPACT).toString().replace("\uFFFC","")
    fun parse(value:String,path:String="",css:String="",pdfConverted:Boolean=false,image:((String)->String?)?=null):List<SourceBlock> {
        val clean=value.replace(Regex("<!--.*?-->|<(head|script|style|noscript)\\b[^>]*>.*?</\\1\\s*>",setOf(RegexOption.IGNORE_CASE,RegexOption.DOT_MATCHES_ALL)),"")
        val result=mutableListOf<SourceBlock>();val pending=mutableListOf<String>();val buffer=StringBuilder()
        var kind="body";var level=0;var continuation=false;var centered=false;var bold=0;var explicitHeading=false
        val styles=Regex("\\.([a-zA-Z0-9_-]+)\\s*\\{([^}]+)\\}").findAll(css).associate {it.groupValues[1] to it.groupValues[2]}
        fun style(tag:String)=attribute(tag,"style").orEmpty()+attribute(tag,"class").orEmpty().split(' ').joinToString(" "){styles[it].orEmpty()}
        fun font(tag:String)=Regex("font-size\\s*:\\s*([0-9.]+)em").find(style(tag))?.groupValues?.get(1)?.toFloatOrNull()?:1f
        val fonts=mutableListOf<Float>();val weights=mutableListOf<Boolean>();var blockFont=1f;var large=0;var total=0;var largest=1f;var skipped=0
        fun flush() {
            var t=plain(buffer.toString()).replace('\u00a0',' ').replace(Regex(if(kind=="verse")"[\\t ]+" else "[\\t\\r\\n ]+")," ").trim(' ','　','\n')
            if(pdfConverted)t=t.replace(Regex("(?<=[\\p{IsHan}]) +(?=[\\p{IsHan}])"),"")
            if(t.isNotBlank()) {
                val detected=if(kind=="body")ChapterScanner.level(t) else 0
                if(kind=="body"&&t.length<=80&&large>0&&large>=total*.6f){kind="heading";level=if(largest>=1.6f)1 else 2}
                if(kind=="body"&&detected==0) {
                    if(TextSemantics.metadata(t))kind="metadata"
                    else if(t.length<=80&&(centered||bold>=total*.8f)&&(TextSemantics.englishTitle(t)||TextSemantics.shortChinese(t))){kind="heading";level=2}
                }
                result+=SourceBlock(t,if(detected>0)"heading" else kind,if(detected>0)detected else level,pending.toMutableList(),continuation,explicitHeading=explicitHeading)
                pending.clear()
            }
            buffer.clear();kind="body";level=0;continuation=false;large=0;total=0;largest=1f;blockFont=1f;centered=false;bold=0;explicitHeading=false
        }
        Regex("<[^>]+>|[^<]+",RegexOption.DOT_MATCHES_ALL).findAll(clean).forEach { match ->
            val token=match.value
            if(!token.startsWith('<')) {
                if(skipped>0)return@forEach
                buffer.append(token);val size=plain(token).count {!it.isWhitespace()};total+=size
                val factor=fonts.lastOrNull()?:blockFont
                if(weights.lastOrNull()==true)bold+=size
                if(factor>=1.25f){large+=size;largest=maxOf(largest,factor)}
                return@forEach
            }
            if(token.startsWith("<!")||token.startsWith("<?"))return@forEach
            val close=token.startsWith("</");val tag=token.removePrefix("</").removePrefix("<").takeWhile {it.isLetterOrDigit()||it==':'}.lowercase()
            val void=tag in setOf("img","image","br","hr","input","meta","link","source","wbr")||token.endsWith("/>")
            if(skipped>0){if(close)skipped-- else if(!void)skipped++;return@forEach}
            val toc=attribute(token,"class").orEmpty().split(' ').any {it.startsWith("sgc-toc")||it.startsWith("kindle-cn-toc")}||tag=="nav"&&(attribute(token,"epub:type").orEmpty().contains("toc")||attribute(token,"role")=="doc-toc")
            if(!close&&toc){flush();if(!void)skipped=1;return@forEach}
            val block=tag in setOf("p","div","section","article","li","blockquote","pre","h1","h2","h3","h4","h5","h6","dt","dd","tr","figure","figcaption")
            if(block)flush()
            if(close&&tag in setOf("span","b","i","em","strong")&&fonts.isNotEmpty()){fonts.removeAt(fonts.lastIndex);if(weights.isNotEmpty())weights.removeAt(weights.lastIndex)}
            if(!close) {
                if(tag in setOf("span","b","i","em","strong")){fonts+=maxOf(font(token),fonts.lastOrNull()?:blockFont);weights+=tag in setOf("b","strong")||weights.lastOrNull()==true||Regex("font-weight\\s*:\\s*(?:bold|[7-9]00)",RegexOption.IGNORE_CASE).containsMatchIn(style(token))}
                attribute(token,"id")?.let {pending+="$path#$it"}
                attribute(token,"name")?.takeIf {tag=="a"}?.let {pending+="$path#$it"}
                if(tag=="br") {if(kind=="verse")buffer.append("<br>") else flush()}
                if(tag=="figcaption")kind="caption"
                if(tag.matches(Regex("h[1-6]"))) {kind="heading";level=tag.last().digitToInt().coerceAtMost(3);explicitHeading=true}
                if(tag=="blockquote")kind="quote"
                if(tag=="pre")kind="verse"
                if(tag=="p") {
                    val classes=attribute(token,"class").orEmpty().split(' ')
                    val style=style(token);blockFont=font(token)
                    centered=Regex("text-align\\s*:\\s*center",RegexOption.IGNORE_CASE).containsMatchIn(style)||attribute(token,"align")=="center"
                    if(Regex("font-weight\\s*:\\s*(?:bold|[7-9]00)",RegexOption.IGNORE_CASE).containsMatchIn(style))bold=Int.MAX_VALUE/2
                    continuation=pdfConverted && !Regex("text-indent\\s*:\\s*[1-9]",RegexOption.IGNORE_CASE).containsMatchIn(style)
                    if(Regex("(?:chapter|heading|title)",RegexOption.IGNORE_CASE).containsMatchIn(classes.joinToString(" ")) && !classes.any {it.contains("copyright")}) {kind="heading";level=2}
                }
                if(tag=="img"||tag=="image") {
                    val src=attribute(token,"src")?:attribute(token,"xlink:href")?:attribute(token,"href")
                    val key=src?.let {image?.invoke(it)}
                    if(key!=null) {
                        flush();result+=SourceBlock("【插图】","image",0,pending.toMutableList(),imageKey=key);pending.clear()
                    } else attribute(token,"alt")?.takeIf {it.isNotBlank()&&!it.endsWith(".pdf",true)}?.let {buffer.append("［").append(it).append("］")}
                }
            }
        }
        flush()
        if(result.isNotEmpty()&&path.isNotEmpty())result.first().anchors+=path
        return result
    }
}
