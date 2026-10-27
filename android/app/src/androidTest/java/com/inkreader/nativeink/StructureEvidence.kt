package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Independently authored semantic fixtures; private regression books are not test dependencies. */
internal class StructureEvidence(private val host:Instrumentation) {
    fun run()=with(host) {
        val checks=JSONArray();var failure:String?=null
        val dir=File(targetContext.cacheDir,"structure-fixtures").also {it.mkdirs()}
        fun verify(name:String,ok:Boolean){checks.put(JSONObject().put("check",name).put("passed",ok));check(ok){name}}
        fun epub(name:String,nav:String,body:String,version3:Boolean):File {
            val f=File(dir,"$name.epub")
            val container="""<container><rootfiles><rootfile full-path="OPS/package.opf"/></rootfiles></container>"""
            val item=if(version3)"""<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""" else """<item id="nav" href="toc.ncx" media-type="application/x-dtbncx+xml"/>"""
            val opf="""<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>结构测试</dc:title></metadata><manifest>$item<item id="one" href="text/chapter%20one.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="one"/></spine></package>"""
            val files=mapOf("META-INF/container.xml" to container,"OPS/package.opf" to opf,"OPS/text/chapter one.xhtml" to body,"OPS/"+(if(version3)"nav.xhtml" else "toc.ncx") to nav)
            ZipOutputStream(f.outputStream()).use {z->files.forEach {(path,text)->z.putNextEntry(ZipEntry(path));z.write(text.toByteArray());z.closeEntry()}}
            return f
        }
        val body="""<!DOCTYPE html><html><head><title>Ignored metadata</title><script>not body</script></head><body><h1 id="one">第一章 起点</h1><p>第一段文字，inline <em>English</em> &amp; 中文。</p><h2 id="two">第二节 观察</h2><p>第二段保持完整，不要重复或者遗漏。</p><p>一、两次，我看到了变化。这是正文。</p></body></html>"""
        val nav3="""<html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="text/chapter%20one.xhtml#one">第一章 起点</a><ol><li><a href="text/chapter%20one.xhtml#two">第二节 观察</a></li></ol></li></ol></nav></body></html>"""
        val ncx="""<ncx><navMap><navPoint><navLabel><text>第一章 起点</text></navLabel><content src="text/chapter%20one.xhtml#one"/><navPoint><navLabel><text>第二节 观察</text></navLabel><content src="text/chapter%20one.xhtml#two"/></navPoint></navPoint></navMap></ncx>"""
        try {
            for((name,nav,v3) in listOf(Triple("epub3",nav3,true),Triple("epub2",ncx,false))) {
                val parsed=BookImporter.parse(epub(name,nav,body,v3),"fixture.epub")
                verify("$name retains semantic paragraphs and decodes inline entities",parsed.text.contains("第一段文字，inline English & 中文。")&&!parsed.text.contains("Ignored metadata")&&!parsed.text.contains("not body"))
                verify("$name resolves encoded paths and both same-file anchor destinations",parsed.structure.chapters.map {it.offset}==listOf(parsed.text.indexOf("第一章"),parsed.text.indexOf("第二节")))
                verify("$name keeps directory hierarchy and labels its original source",parsed.structure.chapters.map {it.level}==listOf(1,2)&&parsed.structure.directorySource=="epub")
                verify("$name persists structural metadata without offset changes",BookStructure.read(parsed.structure.json().toString(),parsed.text)==parsed.structure)
                val book=BookLayout(parsed.text,1000f,700f,30f,structure=parsed.structure)
                val heading=parsed.structure.blocks.first {it.start==parsed.text.indexOf("第二节")}
                val styled=book.layout.text as android.text.Spanned
                verify("$name styles a chapter heading separately from prose",styled.getSpans(heading.start,heading.end,android.text.style.RelativeSizeSpan::class.java).isNotEmpty())
                verify("$name retains exact text through native pagination",book.pages.flatten().joinToString(""){parsed.text.substring(it.start,it.end)}==parsed.text)
            }
            val placeholder="""<ncx><navMap><navPoint><navLabel><text>Start</text></navLabel><content src="text/chapter%20one.xhtml"/></navPoint></navMap></ncx>"""
            val detected=BookImporter.parse(epub("placeholder",placeholder,body,false),"fixture.epub")
            verify("A Start-only NCX falls back to an honestly labelled generated directory",detected.structure.directorySource=="generated"&&detected.structure.chapters.size==2)
            verify("A numbered prose sentence is not invented as a chapter",detected.structure.chapters.none {it.title.startsWith("一、两次")})
            verify("English line-end hyphens are repaired without joining words",BookImporter.joinProse("inter-","national reading")=="international reading"&&BookImporter.joinProse("English","words")=="English words")
            verify("Chinese line restoration adds no artificial word spaces",BookImporter.joinProse("这是一段","连续文字")=="这是一段连续文字")
            val mixed="""<html><body><div class="sgc-toc-title">目录</div><div class="sgc-toc-level"><a href="#body">第一章</a></div><h1 id="body">第一章 起点</h1><p>这段是与目录同文件的真实正文，必须保留。</p></body></html>"""
            val retained=BookImporter.parse(epub("mixed",placeholder,mixed,false),"mixed.epub")
            verify("An inline HTML contents block never discards prose in the same spine file",retained.text.contains("这段是与目录同文件的真实正文，必须保留。")&&retained.structure.chapters.any {it.title=="第一章 起点"})
            val converted="""<html><head><meta name="generator" content="PDF Reflow conversion"/></head><body><h1>自序</h1><p>自序 xiii</p><p>这是一段转换书籍的正文，页眉不应成为第二个章节。</p><h1>第一章 正文</h1><p>这里保留完整的正文内容。</p><h2>1 小章</h2><h2>观察方法</h2><p>这是小章中的一段正文。</p></body></html>"""
            val cleaned=BookImporter.parse(epub("converted",placeholder,converted,false),"converted.epub")
            verify("Converted Roman-numeral running heads do not become duplicate chapters",cleaned.structure.chapters.count {ChapterScanner.key(it.title).startsWith("自序")}==1&&cleaned.text.contains("这里保留完整的正文内容。"))
            verify("Converted chapter numbers and subsections retain separate generated levels",cleaned.structure.chapters.first {it.title=="1 小章"}.level==2&&cleaned.structure.chapters.first {it.title=="观察方法"}.level==3)
            val markdown=File(dir,"outline.md").apply {writeText("# 主标题\n\n正文段落。\n\n## 小节标题\n\n第二段正文。")}
            val md=BookImporter.parse(markdown,"outline.md")
            verify("Markdown headings retain hierarchy and generate real destinations",md.structure.chapters.map {it.title}==listOf("主标题","小节标题")&&md.structure.chapters.map {it.level}==listOf(1,2))
            val docx=File(dir,"outline.docx")
            ZipOutputStream(docx.outputStream()).use {z->z.putNextEntry(ZipEntry("word/document.xml"));z.write("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>文档主章</w:t></w:r></w:p><w:p><w:r><w:t>文档正文。</w:t></w:r></w:p><w:p><w:pPr><w:pStyle w:val="Heading2"/></w:pPr><w:r><w:t>文档小节</w:t></w:r></w:p></w:body></w:document>""".toByteArray());z.closeEntry()}
            val word=BookImporter.parse(docx,"outline.docx")
            verify("DOCX heading styles survive extraction as navigable chapter levels",word.structure.chapters.map {it.title}==listOf("文档主章","文档小节")&&word.structure.chapters.map {it.level}==listOf(1,2))
            val txt=File(dir,"outline.txt").apply {writeText("outline\n正文第一行。\n正文第二行。")}
            verify("A TXT title on the first physical line is not duplicated",BookImporter.parse(txt,"outline.txt").text==txt.readText())
            val frontBack="Volume\n\nTranslator's Preface\n\nAuthor: Ada Reader\nISBN: 978-0-000000-00-1\n\nA quiet morning begins with reading, and the next sentence stays in the same paragraph.\n\nThe Shape of Silence\n\nWords continue naturally and preserve every space between English words in this paragraph.\n\nAcknowledgments\n\nThanks to the readers.\n\nBibliography\n\nReference details.\n\nIndex\n\nReading, 12."
            val plain=BookStructure.infer(frontBack)
            verify("Plain text generates a real front/body/back-matter directory",plain.chapters.map {it.title}==listOf("Translator's Preface","The Shape of Silence","Acknowledgments","Bibliography","Index"))
            verify("Copyright fields stay separate and never become contents entries",plain.blocks.count {it.kind=="metadata"}==2&&plain.chapters.none {it.title.contains("ISBN")||it.title.contains("Author:")})
            verify("Short prose beginning with Notes and numbered footnotes do not become chapters",ChapterScanner.level("Notes are written in the margin")==0&&ChapterScanner.level("1. This is a footnote.")==0)
            val chinese="纸页\n\n出版说明\n\n说明正文。\n\n第一章 清晨\n\n这是一段正常的正文，既不是标题，也不该被拆散。\n\n译后记\n\n后记正文。\n\n参考文献\n\n文献条目。\n\n作者简介\n\n作者资料。"
            verify("Chinese front and back matter receive exact standalone destinations",BookStructure.infer(chinese).chapters.map {it.title}==listOf("出版说明","第一章 清晨","译后记","参考文献","作者简介"))
            val rich=StructuredHtml.parse("<html><body><p><strong>The Quiet Hours</strong></p><p>English words stay together with normal spaces.</p><p>Author: Ada<br/>ISBN: 123</p><figcaption>Figure One</figcaption><pre>line one<br/>line two</pre></body></html>")
            verify("Bold small titles are structural headings without enlarged-font dependence",rich.first().kind=="heading"&&rich.first().text=="The Quiet Hours")
            verify("Explicit copyright line breaks do not become joined English fields",rich.any {it.text=="Author: Ada"}&&rich.any {it.text=="ISBN: 123"}&&rich.none {it.text.contains("Ada ISBN")})
            verify("Captions and preformatted lines retain distinct semantics",rich.any {it.kind=="caption"&&it.text=="Figure One"}&&rich.any {it.kind=="verse"&&it.text=="line one\nline two"})
            verify("Accented English and closing quotation marks keep word spaces",BookImporter.joinProse("a café","opens quietly")=="a café opens quietly"&&BookImporter.joinProse("he said, “Read.”","Then continued.")=="he said, “Read.” Then continued.")
            val numberedHeadings=BookImporter.parse(epub("numeric-headings",placeholder,"<html><body><h1>卷一</h1><h2>1</h2><p>第一小节正文。</p><h2>2</h2><p>第二小节正文。</p></body></html>",false),"numeric-headings.epub")
            verify("Explicit numeric section headings remain real destinations rather than disappearing",numberedHeadings.structure.chapters.any {it.title=="1"}&&numberedHeadings.structure.chapters.any {it.title=="2"})
            val supplemented=BookImporter.parse(epub("supplemented",nav3,body.replace("</body>","<p>致谢</p><p>谢谢所有读者。</p></body>"),true),"supplemented.epub")
            verify("A partial source directory is supplemented with actual back-matter headings",supplemented.structure.directorySource=="mixed"&&supplemented.structure.chapters.any {it.title=="致谢"&&it.source=="generated"}&&supplemented.structure.chapters.count {it.source=="epub"}==2)
            if(android.os.Build.VERSION.SDK_INT>=35) {
                val pdf=File(dir,"geometry.pdf")
                val doc=PdfDocument();try {
                    val page=doc.startPage(PdfDocument.PageInfo.Builder(300,400,1).create());val paint=Paint().apply {textSize=20f;color=Color.BLACK}
                    page.canvas.drawText("CHAPTER I",55f,45f,paint);paint.textSize=12f
                    page.canvas.drawText("A paragraph crosses physical",26f,80f,paint)
                    page.canvas.drawText("lines without a paragraph break.",10f,100f,paint)
                    page.canvas.drawText("Another paragraph starts here",26f,130f,paint)
                    page.canvas.drawText("and continues naturally.",10f,150f,paint)
                    page.canvas.drawText("An ellipsis .... stays in prose.",26f,190f,paint)
                    page.canvas.drawText("1",280f,390f,paint);doc.finishPage(page);pdf.outputStream().use {doc.writeTo(it)}
                doc.close()
                } catch(t:Throwable){doc.close();throw t}
                val parsed=BookImporter.parse(pdf,"geometry.pdf")
                verify("PDF geometry merges physical lines into the exact first paragraph",parsed.text.contains("A paragraph crosses physical lines without a paragraph break."))
                verify("PDF indentation retains a real paragraph boundary",parsed.text.contains("break.\n\nAnother paragraph starts here and continues naturally."))
                verify("PDF prose with an ellipsis never causes the page to be discarded",parsed.text.contains("An ellipsis .... stays in prose."))
                verify("PDF marginal page number is excluded from reading text",!parsed.text.endsWith("\n\n1"))
                val continuation=File(dir,"contents-continuation.pdf");val tocDoc=PdfDocument()
                val names=listOf("1. First Chapter","2. Second Chapter","3. Third Chapter","4. Final Chapter, with a long descriptive subtitle")
                for(i in 0..5) {
                    val p=tocDoc.startPage(PdfDocument.PageInfo.Builder(600,800,i+1).create());val ink=Paint().apply {textSize=16f;color=Color.BLACK}
                    if(i==0){p.canvas.drawText("CONTENTS",200f,70f,ink);names.take(3).forEachIndexed {j,t->p.canvas.drawText("$t ............ ${j+1}",40f,130f+j*50,ink)}}
                    else if(i==1)p.canvas.drawText("${names[3]} ............ 4",40f,100f,ink)
                    else {p.canvas.drawText(names[i-2],40f,100f,ink);p.canvas.drawText("A complete paragraph belongs to this chapter.",40f,150f,ink)}
                    tocDoc.finishPage(p)
                };continuation.outputStream().use {tocDoc.writeTo(it)};tocDoc.close()
                val continued=BookImporter.parse(continuation,"contents-continuation.pdf")
                verify("A final one-entry contents continuation still resolves its long chapter heading",continued.structure.chapters.any {it.title==names[3]}&&continued.text.indexOf(names[3])==continued.text.lastIndexOf(names[3]))
            }
        }catch(t:Throwable){failure=t.stackTraceToString()}
        finally {dir.deleteRecursively()}
        val out=File(targetContext.getExternalFilesDir(null),"structure-verification").also {it.mkdirs()}
        File(out,"structure-report.json").writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("error",failure).toString(2))
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${checks.length()} structural import checks passed\n")})
    }
}
