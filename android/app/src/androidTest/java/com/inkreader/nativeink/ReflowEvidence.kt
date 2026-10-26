package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Typeface
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Independent golden text plus native-layout invariants; no screenshots used as text truth. */
internal class ReflowEvidence(private val instrumentation: Instrumentation) {
    fun run() = with(instrumentation) {
        val checks=JSONArray(); var failure:String?=null
        fun verify(name:String,value:Boolean) { checks.put(JSONObject().put("check",name).put("passed",value));check(value) { name } }
        val destination=File(targetContext.getExternalFilesDir(null),"reflow-verification").also { it.mkdirs() }
        val temporary=File(targetContext.cacheDir,"reflow-golden").also { it.mkdirs() }
        try {
            val expected=context.assets.open("fixtures/golden/expected.txt").bufferedReader().use { it.readText().trimEnd('\n') }
            for(name in listOf("utf8.txt","utf16le.txt","utf16be.txt","gb18030.txt","note.md","page.html","spine.epub","paragraphs.docx")) {
                val file=File(temporary,name)
                context.assets.open("fixtures/golden/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                verify("$name exactly matches independently authored Unicode/paragraph golden text",BookImporter.parse(file,"重排金样."+name.substringAfterLast('.')).text==expected)
            }
            verify("A different file title is retained without changing its body",BookImporter.parse(File(temporary,"utf8.txt"),"另一书名.txt").text=="另一书名\n\n$expected")
            val pdf=File(temporary,"text.pdf")
            targetContext.contentResolver.openInputStream(android.net.Uri.parse("content://io.github.dpan538.inkreader.fixtures/document/文本.pdf"))!!.use { input -> pdf.outputStream().use { input.copyTo(it) } }
            verify("Text PDF matches its known source line",BookImporter.parse(pdf,"文本.pdf").text=="文本\n\nPDF_REFLOW_TEXT native reading")
            val text="原生重排核对\n\n"+(8..145).joinToString("\n\n") { n ->
                when(n%4) {
                    0 -> "中文的断行、标点与段尾应当自然".repeat(12).take(n)+"。"
                    1 -> "“阅读与思考”，他说：不要遗漏（任何文字）。".repeat(1+n/32)
                    2 -> "Mixed English words 123.45, 中文及𠮷🙂；保持次序。".repeat(1+n/35)
                    else -> "https://example.org/a/very/long/unbroken/path/".repeat(1+n/40)
                }
            }
            fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toList()
            var cases=0
            for((w,h) in listOf(1704f to 1136f,1136f to 1704f)) for(size in listOf(28f,32f,36f))
                for(space in listOf(.40f,.67f,.95f)) for(columns in listOf(1,2)) for(face in listOf("serif","sans-serif")) {
                    val b=BookLayout(text,w,h,size,space,columns,Typeface.create(face,Typeface.NORMAL))
                    val slices=b.pages.flatten()
                    check(slices.first().start==0 && slices.last().end==text.length)
                    check(slices.zipWithNext().all { (a,c)->a.end==c.start })
                    val rebuilt=slices.joinToString("") { text.substring(it.start,it.end) }
                    check(rebuilt==text && hash(rebuilt)==hash(text)) { "Text changed: $w/$size/$space/$columns/$face" }
                    check(slices.all { b.layout.getLineBottom(it.lastLine)-b.layout.getLineTop(it.firstLine)<=b.bodyHeight })
                    for(line in 0 until b.layout.lineCount) {
                        val start=b.layout.getLineStart(line);val end=b.layout.getLineEnd(line)
                        check(start==0 || !Character.isLowSurrogate(text[start])) { "Split Unicode character" }
                        check(b.layout.getLineLeft(line)>=-0.5 && b.layout.getLineRight(line)<=b.columnWidth+0.5) { "Horizontal overflow at $line ($face $size)" }
                        val fragment=text.substring(start,end).trim()
                        if(fragment.isNotEmpty()) {
                            check(fragment.first() !in "，。！？；：、）】》」』”") { "Closing punctuation at line start: $fragment" }
                            check(fragment.last() !in "（【《「『“") { "Opening punctuation at line end: $fragment" }
                        }
                    }
                    for(offset in text.indices step 53) {
                        val p=b.pages[b.pageForOffset(offset)]
                        check(offset in p.first().start until p.last().end) { "Anchor outside selected page" }
                    }
                    cases++
                }
            verify("$cases native layouts preserve every Unicode character, paragraph order and SHA-256",cases==72)
            verify("All columns fit vertically and every line fits horizontally",true)
            verify("CJK punctuation, surrogate boundaries and sampled reading anchors remain valid",true)
        } catch(t:Throwable) { failure=t.stackTraceToString() }
        finally {
            temporary.deleteRecursively()
            File(destination,"reflow-report.json").writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("error",failure).toString(2))
            finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply { putString("stream",failure ?: "${checks.length()} golden/reflow checks passed\n") })
        }
    }
}
