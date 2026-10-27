package com.inkreader.nativeink

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

internal data class ParsedBook(val title:String,val text:String,val format:String,val structure:BookStructure=BookStructure.infer(text),val images:Map<String,File> = emptyMap())

/** Reads local data only. No HTML execution, external XML entities or archive extraction. */
internal object BookImporter {
    const val MAX_BYTES = 64 * 1024 * 1024
    const val MAX_TEXT = 2_000_000
    val extensions = setOf("txt", "epub", "md", "markdown", "html", "htm", "docx", "pdf", "cbz", "zip")

    fun parse(file:File,name:String,progress:(Int,Int)->Unit={_,_->},context:android.content.Context?=null,workspace:File=File(file.parentFile,file.name+"-assets"),forceOcr:Boolean=false):ParsedBook {
        require(file.length() in 1..MAX_BYTES.toLong()) { "文件为空或超过 64 MB" }
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in extensions) { "暂不支持 .$extension；请选择 TXT、EPUB、CBZ/图片 ZIP 漫画、MD、HTML、DOCX 或 PDF" }
        var title = name.substringBeforeLast('.').ifBlank { "未命名" }
        if(extension=="txt") {
            val body=normalize(decode(file.readBytes()))
            require(body.any {it.isLetterOrDigit()}) {"未找到可重排正文"}
            title=title.replace(Regex("\\s+")," ").take(100)
            val text=if(body.lineSequence().first()==title)body else "$title\n\n$body"
            require(text.length<=MAX_TEXT) {"正文超过 200 万字，请拆分后导入"}
            return ParsedBook(title,text,"TXT",BookStructure.infer(text))
        }
        val assembly=when(extension) {
            "epub" -> return epub(file,title,progress,workspace)
            "cbz","zip" -> return ComicArchive.parse(file,title,progress,workspace,extension.uppercase())
            "pdf" -> PdfReflow.read(file,progress,context,workspace,forceOcr)
            "docx" -> docx(file)
            "md","markdown" -> markdownBlocks(decode(file.readBytes()))
            else -> BookAssembly().apply {
                val value=decode(file.readBytes())
                if(extension in setOf("html","htm"))blocks+=StructuredHtml.parse(value)
                else {
                    val body=normalize(if(extension in setOf("md","markdown"))markdown(value) else value)
                    body.split(Regex("\\n\\s*\\n")).forEach { paragraph->
                        // TXT keeps explicit single line breaks; HTML/PDF own their block boundaries.
                        val level=ChapterScanner.level(paragraph)
                        add(SourceBlock(paragraph,if(level>0)"heading" else "body",level))
                    }
                }
            }
        }
        title=title.replace(Regex("\\s+")," ").take(100)
        require(assembly.blocks.any {it.text.any {c->c.isLetterOrDigit()}||it.imageKey.isNotEmpty()}) {"未找到可重排正文"}
        val result=assembly.finish(title,extension.uppercase())
        require(result.text.any {it.isLetterOrDigit()} && assembly.blocks.isNotEmpty()) { "未找到可重排正文；扫描图片或受保护文件暂不能重排" }
        require(result.text.length<=MAX_TEXT) { "正文超过 200 万字，请拆分后导入" }
        return result
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
        val utf8 = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
        val value = utf8 ?: runCatching { charset("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }.getOrElse { error("文字编码无法识别，请另存为 UTF-8") }
        require(value.count { it == '\u0000' || it == '\uFFFD' } < maxOf(2, value.length / 200)) { "文件不像有效文本，请检查编码或格式" }
        return value.removePrefix("\uFEFF")
    }

    fun normalize(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ')
        .replace(Regex("[\\t ]+\\n"), "\n").replace(Regex("\\n[\\t 　]+"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n").trim()

    private fun markdown(value: String) = value
        .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s+"), "")
        .replace("**", "").replace("__", "").replace("```", "")

    private fun xml(value: String, visit: (XmlPullParser, Int) -> Unit) {
        require(!value.contains("<!ENTITY", true) && !Regex("<!DOCTYPE[^>]*\\[",RegexOption.IGNORE_CASE).containsMatchIn(value)) { "文档包含不支持的 XML 声明" }
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(value.replace(Regex("<!DOCTYPE[^>]*>",RegexOption.IGNORE_CASE),"").reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) { visit(parser, event); event = parser.next() }
    }
    private class Archive(file: File) : java.io.Closeable {
        val zip = ZipFile(file)
        private var expanded = 0
        init { require(zip.size() <= 4000) { "压缩文档条目过多" } }
        fun read(path: String): String {
            val entry = zip.getEntry(path) ?: error("文档缺少 $path")
            val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_BYTES + 1) }
            expanded += bytes.size
            require(expanded <= 256 * 1024 * 1024) { "解压后正文过大" }
            return decode(bytes)
        }
        fun asset(path:String,target:File):File {
            val entry=zip.getEntry(path) ?: error("插图缺失")
            target.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input->target.outputStream().use { output->
                val b=ByteArray(32768);var size=0
                while(true) {val n=input.read(b);if(n<0)break;size+=n;expanded+=n
                    require(size<=32*1024*1024&&expanded<=256*1024*1024) {"文档资源过大"};output.write(b,0,n)}
            }}
            return target
        }
        fun has(path: String) = zip.getEntry(path) != null
        override fun close() = zip.close()
    }
    private fun resolve(base: String, href: String): String {
        val path = android.net.Uri.decode(href.substringBefore('#'))
        require(!path.contains(':') && !path.startsWith('/')) { "不支持外部章节地址" }
        val parts = mutableListOf<String>()
        (base.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.plus("/").orEmpty() + path).split('/').forEach {
            when (it) { "", "." -> Unit; ".." -> { require(parts.isNotEmpty()) { "章节地址越界" }; parts.removeAt(parts.lastIndex) }; else -> parts += it }
        }
        return parts.joinToString("/")
    }
    private data class NavRef(val label:String,val href:String,val level:Int)
    private fun epub(file:File,fallback:String,progress:(Int,Int)->Unit,workspace:File):ParsedBook=Archive(file).use { a ->
        val encrypted=mutableSetOf<String>()
        if(a.has("META-INF/encryption.xml"))xml(a.read("META-INF/encryption.xml")) {p,e->if(e==XmlPullParser.START_TAG&&p.name=="CipherReference")encrypted+=android.net.Uri.decode(p.getAttributeValue(null,"URI").orEmpty())}
        var opf=""
        xml(a.read("META-INF/container.xml")) {p,e->if(e==XmlPullParser.START_TAG&&p.name=="rootfile"&&opf.isEmpty())opf=p.getAttributeValue(null,"full-path").orEmpty()}
        require(opf.isNotBlank()&&!opf.startsWith('/')&&".." !in opf.split('/')) {"EPUB 的书籍索引无效"}
        data class Item(val href:String,val mime:String,val properties:String)
        val manifest=mutableMapOf<String,Item>();val spine=mutableListOf<String>();var title="";var readingTitle=false
        xml(a.read(opf)) {p,e->
            if(e==XmlPullParser.START_TAG)when(p.name) {
                "title"->readingTitle=true
                "item"->manifest[p.getAttributeValue(null,"id").orEmpty()]=Item(p.getAttributeValue(null,"href").orEmpty(),p.getAttributeValue(null,"media-type").orEmpty(),p.getAttributeValue(null,"properties").orEmpty())
                "itemref"->{val id=p.getAttributeValue(null,"idref").orEmpty();if(p.getAttributeValue(null,"linear")!="no"||manifest[id]?.href?.contains("cover",true)==true)spine+=id}
            }
            if(e==XmlPullParser.TEXT&&readingTitle)title+=p.text
            if(e==XmlPullParser.END_TAG&&p.name=="title")readingTitle=false
        }
        require(spine.isNotEmpty()) {"EPUB 没有可读章节"}
        title=title.trim().ifBlank {fallback}.replace(Regex("\\s+")," ").take(100)
        val nav=mutableListOf<NavRef>();var navPath=""
        val navigation=manifest.values.firstOrNull {"nav" in it.properties.split(' ')}
        val ncx=manifest.values.firstOrNull {it.mime=="application/x-dtbncx+xml"}
        if(navigation!=null) {
            navPath=resolve(opf,navigation.href);var depth=0;var tocDepth=0;var label=StringBuilder();var href="";var link=false;var listDepth=0
            xml(a.read(navPath)) {p,e->
                if(e==XmlPullParser.START_TAG) {
                    depth++
                    if(p.name=="nav"&&(p.getAttributeValue("http://www.idpf.org/2007/ops","type").orEmpty().contains("toc")||p.getAttributeValue(null,"role")=="doc-toc"))tocDepth=depth
                    if(tocDepth>0&&p.name=="ol")listDepth++
                    if(tocDepth>0&&p.name=="a") {href=p.getAttributeValue(null,"href").orEmpty();label=StringBuilder();link=true}
                }
                if(e==XmlPullParser.TEXT&&link)label.append(p.text)
                if(e==XmlPullParser.END_TAG) {
                    if(p.name=="a"&&link) {nav+=NavRef(label.toString().trim(),href,listDepth.coerceIn(1,3));link=false}
                    if(tocDepth>0&&p.name=="ol")listDepth--
                    if(depth==tocDepth)tocDepth=0
                    depth--
                }
            }
        }
        if(nav.isEmpty()&&ncx!=null) {
            navPath=resolve(opf,ncx.href);val stack=mutableListOf<NavRef>();var label=false
            xml(a.read(navPath)) {p,e->
                if(e==XmlPullParser.START_TAG)when(p.name) {
                    "navPoint"->stack+=NavRef("","",(stack.size+1).coerceAtMost(3))
                    "navLabel"->label=true
                    "content"->if(stack.isNotEmpty()){val n=stack.last().copy(href=p.getAttributeValue(null,"src").orEmpty());stack[stack.lastIndex]=n;nav+=n}
                }
                if(e==XmlPullParser.TEXT&&label&&stack.isNotEmpty())stack[stack.lastIndex]=stack.last().copy(label=stack.last().label+p.text)
                if(e==XmlPullParser.END_TAG)when(p.name) {"navLabel"->label=false;"navPoint"->if(stack.isNotEmpty())stack.removeAt(stack.lastIndex)}
            }
        }
        val css=manifest.values.filter {it.mime=="text/css"}.take(30).joinToString("\n") {a.read(resolve(opf,it.href))}
        val assembly=BookAssembly();val anchors=mutableMapOf<String,Int>();val documents=mutableMapOf<String,List<SourceBlock>>();var convertedBook=false
        for((number,id) in spine.withIndex()) {
            progress(number+1,spine.size)
            val item=manifest[id]?:error("EPUB 的章节引用缺失");val path=resolve(opf,item.href)
            require(path !in encrypted) {"此 EPUB 的正文章节受保护，暂不能读取"}
            val raw=a.read(path)
            // A native navigable directory replaces a duplicated printed HTML contents page.

            val converted=raw.contains("PDF Reflow conversion",true);convertedBook=convertedBook||converted
            var parsed=StructuredHtml.parse(raw,path,css,converted) {src->
                runCatching {
                    val imagePath=resolve(path,src)
                    if(!imagePath.substringAfterLast('.').lowercase().let {it in setOf("jpg","jpeg","png","webp","gif")})return@runCatching null
                    val key=java.security.MessageDigest.getInstance("SHA-256").digest(imagePath.toByteArray()).joinToString("") {"%02x".format(it)}
                    if(key !in assembly.images)assembly.images[key]=a.asset(imagePath,File(workspace,key))
                    key
                }.getOrNull()
            }
            val tocStart=parsed.indexOfFirst {ChapterScanner.key(it.text) in setOf("目录","目錄","contents","tableofcontents")}
            val proseStart=if(tocStart>=0)(tocStart+1 until parsed.size).firstOrNull {parsed[it].text.length>=80&&parsed[it].navigationTarget.isEmpty()&&parsed[it].text.any {c->c in "。！？.!?"}} else null
            val linkedRows=if(tocStart>=0)(tocStart+1 until (proseStart?:parsed.size)).filter {parsed[it].navigationTarget.isNotEmpty()} else emptyList()
            if(tocStart>=0&&linkedRows.size>=2) {
                parsed=parsed.take(tocStart)+parsed.drop(linkedRows.last()+1)
            } else if(converted&&tocStart>=0) {
                val prose=(tocStart+1 until parsed.size).firstOrNull {j->val b=parsed[j];b.kind=="body"&&b.text.length>=20&&b.text.any {it in "，。！？、；"}&&!Regex("\\s+[0-9ivxlc]+$",RegexOption.IGNORE_CASE).containsMatchIn(b.text.trim())}
                if(prose!=null) {
                    val start=(tocStart+1 until prose).lastOrNull {parsed[it].kind=="heading"&&!Regex("[0-9ivxlc]+$",RegexOption.IGNORE_CASE).containsMatchIn(parsed[it].text.trim())}?:prose
                    parsed=parsed.take(tocStart)+parsed.drop(start)
                } else parsed=parsed.take(tocStart)
            }
            parsed=parsed.filterNot {ChapterScanner.key(it.text) in setOf("目录","目錄","contents","tableofcontents")}
            parsed.firstOrNull()?.anchors?.add(path)
            val documentStart=assembly.blocks.size
            parsed.forEach { block->
                val compact=ChapterScanner.key(block.text)
                val titleKey=ChapterScanner.key(title.substringBefore('：').substringBefore(':'))
                val running=converted && (compact.matches(Regex("[0-9]{1,4}"+Regex.escape(titleKey)))||compact.matches(Regex(Regex.escape(titleKey)+"[0-9]{1,4}")))
                if(!running) {
                    if(converted&&block.continuation&&block.kind=="body"&&assembly.blocks.lastOrNull()?.kind=="body") {
                        val prev=assembly.blocks.last();prev.text=joinProse(prev.text,block.text);prev.anchors+=block.anchors;assembly.repairedBreaks++
                    } else assembly.add(block)
                }
            }
            documents[path]=assembly.blocks.subList(documentStart,assembly.blocks.size).toList()
            require(assembly.blocks.sumOf {it.text.length}<=MAX_TEXT) {"正文超过 200 万字"}
        }
        // Remove converted running heads only when their non-page-number title exists as a real block.
        val blockKeys=assembly.blocks.map {ChapterScanner.key(it.text)}.toMutableSet()
        for((i,b) in assembly.blocks.withIndex())if(b.kind=="heading") {
            val next=assembly.blocks.drop(i+1).take(4).firstOrNull {it.kind=="heading"&&it.text.length in 2..50}
            if(next!=null)blockKeys+=ChapterScanner.key(b.text)+ChapterScanner.key(next.text)
        }
        if(convertedBook)assembly.blocks.removeAll {b->
            val key=ChapterScanner.key(b.text)
            val withoutPage=key.replace(Regex("(?:[0-9]{1,4}|[ivxlcdm]{1,7})$"),"")
            b.text.length<45&&withoutPage!=key&&withoutPage.length>=2&&withoutPage in blockKeys
        }
        val positions=java.util.IdentityHashMap<SourceBlock,Int>()
        assembly.blocks.forEachIndexed {index,b->positions[b]=index;b.anchors.forEach {anchors[it]=index}}
        val resolved=mutableListOf<Triple<String,Pair<Int,Int>,String>>()
        if(nav.size>1)for(ref in nav) {
            val path=runCatching {resolve(navPath,ref.href)}.getOrNull()?:continue
            val fragment=android.net.Uri.decode(ref.href.substringAfter('#',""))
            val indices=documents[path]?.mapNotNull {positions[it]}.orEmpty();if(indices.isEmpty())continue
            val span=indices.first()..indices.last()
            val anchored=anchors[if(fragment.isEmpty())path else "$path#$fragment"]
            var index=anchored?:anchors[path]?:continue
            val key=ChapterScanner.key(ref.label)
            val exact=(index..minOf(index+7,span.last)).firstOrNull {ChapterScanner.key(assembly.blocks[it].text)==key}
            if(fragment.isNotEmpty()&&anchored==null&&exact==null)continue
            if(exact!=null)index=exact
            if(ref.label.isBlank()||ref.label.trim().lowercase() in setOf("cover","contents","table of contents","封面","目录","目錄"))continue
            val b=assembly.blocks[index]
            if(b.text.length<100&&ChapterScanner.key(b.text)==key) {b.kind="heading";b.level=ref.level}
            resolved+=Triple(ref.label.trim(),index to ref.level,"epub")
        }
        // A placeholder Start-only NCX is not a directory. Build one from real headings.
        if(resolved.isEmpty())assembly.blocks.forEach {b->if(b.kind=="body"){val level=ChapterScanner.level(b.text);if(level>0){b.kind="heading";b.level=level}}}
        if(convertedBook&&resolved.isEmpty()) {
            var numberedChapter=false
            for(block in assembly.blocks)if(block.kind=="heading") {
                val numbered=Regex("^[0-9]{1,2}\\s+[\\p{IsHan}]").containsMatchIn(block.text)&&block.text.length<=24
                when {
                    numbered->{block.level=2;numberedChapter=true}
                    block.level==1->numberedChapter=false
                    numberedChapter->block.level=3
                }
            }
        }
        require(assembly.blocks.any {it.text.any {c->c.isLetterOrDigit()}||it.imageKey.isNotEmpty()}) {"未找到可重排正文"}
        val explicit=assembly.blocks.withIndex().filter {(it.value.explicitHeading||ChapterScanner.level(it.value.text)>0)&&it.value.level<=2&&ChapterScanner.key(it.value.text)!=ChapterScanner.key(title)}
        val covered=explicit.count {b->resolved.any {it.second.first==b.index}}
        val major=explicit.filter {it.value.level==1}
        val allMajorCovered=major.size>=3&&major.all {b->resolved.any {it.second.first==b.index}}
        val authoritative=resolved.size>=3&&explicit.isNotEmpty()&&(allMajorCovered||covered>=explicit.size*.7)
        val result=assembly.finish(title,"EPUB",resolved,authoritative)
        require(result.text.length<=MAX_TEXT&&assembly.blocks.size>1) {"未找到可重排正文"}
        result
    }
    internal fun joinProse(left:String,right:String):String {
        val a=left.trimEnd();val b=right.trimStart()
        if(a.isEmpty())return b
        if(b.isEmpty())return a
        if(a.endsWith('-')&&a.length>1&&a[a.lastIndex-1].isLetter()&&a[a.lastIndex-1].code<128&&b[0].isLowerCase()&&b[0].code<128)return a.dropLast(1)+b
        val latinA=Character.UnicodeScript.of(a.last().code)==Character.UnicodeScript.LATIN||a.last().isDigit()||a.last() in ",;:!?)]}”’\"\'"
        val latinB=Character.UnicodeScript.of(b.first().code)==Character.UnicodeScript.LATIN||b.first().isDigit()||b.first() in "(“‘\"\'"
        val space=latinA&&latinB&&a.last() !in "(/"&&b.first() !in ".,;:!?)]"
        return a+(if(space)" " else "")+b
    }
    private fun markdownBlocks(value:String):BookAssembly {
        val out=BookAssembly();val paragraph=StringBuilder();var code=false
        fun flush(){val text=normalize(if(code)paragraph.toString() else markdown(paragraph.toString()));if(text.isNotBlank())out.add(SourceBlock(text,if(code)"verse" else "body"));paragraph.clear()}
        for(line in value.replace("\r\n","\n").lineSequence()) {
            val heading=Regex("^(#{1,6})\\s+(.+?)\\s*#*$").matchEntire(line)
            when {
                line.trimStart().startsWith("```")->{flush();code=!code}
                !code&&heading!=null->{flush();out.add(SourceBlock(markdown(heading.groupValues[2]),"heading",heading.groupValues[1].length.coerceAtMost(3),explicitHeading=true))}
                line.isBlank()->flush()
                else->{if(paragraph.isNotEmpty())paragraph.append('\n');paragraph.append(line)}
            }
        }
        flush();return out
    }
    private fun docx(file:File):BookAssembly=Archive(file).use {a->
        val out=BookAssembly();val text=StringBuilder();var inText=false;var heading=0
        xml(a.read("word/document.xml")) {p,e->
            fun attribute(name:String)=p.getAttributeValue("http://schemas.openxmlformats.org/wordprocessingml/2006/main",name)?:p.getAttributeValue(null,name)
            if(e==XmlPullParser.START_TAG)when(p.name) {
                "p"->{text.clear();heading=0}
                "pStyle"->{val style=attribute("val").orEmpty();if(style.equals("Title",true))heading=1 else if(Regex("(?:Heading|标题)[1-6]",RegexOption.IGNORE_CASE).matches(style))heading=style.last().digitToInt().coerceAtMost(3)}
                "outlineLvl"->attribute("val")?.toIntOrNull()?.takeIf {it in 0..2}?.let {heading=it+1}
                "t"->inText=true
                "tab"->text.append(' ')
                "br"->text.append('\n')
            }
            if(e==XmlPullParser.TEXT&&inText)text.append(p.text)
            if(e==XmlPullParser.END_TAG)when(p.name) {
                "t"->inText=false
                "p"->{val content=normalize(text.toString());if(content.isNotBlank())out.add(SourceBlock(content,if(heading>0)"heading" else "body",heading,explicitHeading=heading>0))}
            }
        }
        out
    }
    private fun pdf(file:File,progress:(Int,Int)->Unit):BookAssembly = PdfReflow.read(file,progress)
}
