package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.text.StaticLayout
import android.text.TextPaint
import android.graphics.text.LineBreaker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Generated language/semantic matrix plus independent defect injections and repair counterexamples. */
internal class BookQualityEvidence(private val host:Instrumentation,private val options:Bundle=Bundle()) {
    fun run()=with(host) {
        val out=File(targetContext.getExternalFilesDir(null),"quality-verification").also {it.mkdirs()}
        val rounds=JSONArray();val detections=JSONArray();var failure:String?=null;var context=JSONObject()
        val calibration=JSONObject()
        fun html(s:String)=s.replace("&","&amp;").replace("<","&lt;")
        fun decision(d:ParagraphSpacing.Decision)=JSONObject().put("block",d.block).put("offset",d.start).put("justified",d.justified).put("rule",d.reason).put("maxSpaceRatio",d.maxSpaceRatio)
        fun verify(name:String,ok:Boolean){detections.put(JSONObject().put("check",name).put("passed",ok));check(ok){name}}
        try {
            val bodies=listOf(
                "纸上的文字应当保持自然间距，句子里的标点和段落都有各自的位置。",
                "A quiet reader follows the path, reads a complete sentence, and turns another page. ",
                "1884 年，读者见到 English names 与中文混排，空白不能被无故拉宽。",
                "作者和出版信息各自成行，版权页无需模仿正文缩进。",
                "English words such as any one, a lone reader and in to the room must keep their spaces. ",
                "Open index.html in the editor. Save index.html in the example directory. ",
                "这段正文有省略号......后面仍然是完整正文，不能误删。",
                "Un café opens quietly. Supplementary characters 𠮷 and quotation marks “stay together,” while words remain intact. ",
                "数字 2026 与单位 mm、日本語、引号和破折号——共同出现时仍应自然换行。",
                "مرحبا بالقارئ، هذه فقرة عربية تحافظ على اتجاه الكتابة والمسافات بين الكلمات. "
            )
            for((seed,body) in bodies.withIndex())for(profile in 0..4)for(font in listOf(26f,32f,40f))for(columns in listOf(1,2)) {
                if(options.getString("seed")?.toIntOrNull()?.let {it!=seed}==true||options.getString("profile")?.toIntOrNull()?.let {it!=profile}==true||options.getString("font")?.toFloatOrNull()?.let {it!=font}==true||options.getString("columns")?.toIntOrNull()?.let {it!=columns}==true)continue
                val heading="Chapter ${seed+1}";val prose=body.repeat(8).trim()
                val split=if(profile%2==0)13 else 23
                val markup=prose.chunked(split).joinToString(""){"<span>"+html(it)+"</span>"}
                val source="<html><head><title>inert-document.html</title></head><body><h1 id=\"chapter\">$heading</h1><p>作　者：示例作者</p><p>出版社：示例出版社</p><p>$markup</p><pre>First line\nSecond line</pre></body></html>"
                context=JSONObject().put("phase","matrix").put("seed",seed).put("profile",profile).put("font",font).put("columns",columns).put("format","HTML").put("source",source)
                val assembly=BookAssembly();assembly.blocks+=StructuredHtml.parse(source,"fixture-$seed.xhtml")
                val book=assembly.finish("Quality fixture $seed","HTML")
                val sizes=listOf(1704f to 1080f,1136f to 1650f,960f to 600f,640f to 380f,1280f to 800f)
                val (w,h)=sizes[profile];val layout=BookLayout(book.text,w,h,font,columnMode=columns,structure=book.structure)
                check(book.text.contains(prose)){"Inline/soft-wrap mutation altered the independent body golden"}
                check(book.structure.blocks.any {it.kind=="metadata"&&book.text.substring(it.start,it.end)=="作者：示例作者"}){"Metadata lost its semantic block"}
                check(book.structure.blocks.any {it.kind=="verse"&&book.text.substring(it.start,it.end)=="First line\nSecond line"}){"Poetry/preformatted line breaks changed"}
                val findings=BookDiagnostics.inspect(book,layout)
                if(findings.isNotEmpty()) {
                    File(out,"failure-input.html").writeText(source);File(out,"failure-text.txt").writeText(book.text)
                    File(out,"failure-audit.json").writeText(JSONArray(findings.map {it.json()}).toString(2))
                    error("Quality detector found ${findings.map {it.category}} in seed=$seed profile=$profile font=$font columns=$columns")
                }
                val ranges=layout.pages.flatten()
                check(ranges.first().start==0&&ranges.last().end==book.text.length&&ranges.zipWithNext().all {(a,b)->a.end==b.start}){"Text range was dropped or duplicated"}
                check(layout.spacingDecisions.filter {book.structure.blocks[it.block].kind in setOf("metadata","heading","verse")}.none {it.justified}){"Non-prose semantic block was justified"}
                rounds.put(JSONObject().put("seed",seed).put("profile",profile).put("font",font).put("columns",columns).put("passed",true).put("decisions",JSONArray(layout.spacingDecisions.map(::decision))))
            }
            fun injected(text:String,blocks:List<BookBlock>,chapters:List<BookChapter> = emptyList())=ParsedBook("Injected",text,"TXT",BookStructure(blocks,chapters))
            val faults=listOf(
                "METADATA_BOUNDARY" to "版权信息作者：示例作者出版社：示例出版社",
                "PRINTED_CONTENTS_REMAINS" to "1. A road ........ (12) 2. A river ........ (24) 3. A hill ........ (36)",
                "HTML_FILE_HEADER" to "compiled.html Chapter One"
            )
            for((category,text) in faults) {
                context=JSONObject().put("phase","detector-calibration").put("category",category).put("source",text)
                val book=injected(text,listOf(BookBlock(0,text.length,origin="fault.txt:line=1")))
                val findings=BookDiagnostics.inspect(book,BookLayout(text,1000f,700f,30f,structure=book.structure))
                verify("Injected $category is detected with a source, block, page, line and offset",findings.any {it.category==category&&it.origin.isNotEmpty()&&it.block==0&&it.page>=0&&it.line>=0&&it.offset==0})
            }
            val dup="Chapter One\n\nChapter One\n\nA complete paragraph follows."
            context=JSONObject().put("phase","detector-calibration").put("category","DUPLICATE_HEADING")
            val second=dup.indexOf("Chapter One",1);val end=second+"Chapter One".length
            val duplicate=injected(dup,listOf(BookBlock(0,11,"heading",1),BookBlock(second,end,"heading",1),BookBlock(end+2,dup.length)))
            verify("Injected duplicate headings are located",BookDiagnostics.inspect(duplicate,BookLayout(dup,1000f,700f,30f,structure=duplicate.structure)).any {it.category=="DUPLICATE_HEADING"&&it.offset==second})
            var injectedSpaces=0
            var paintedGap=0
            context=JSONObject().put("phase","detector-calibration").put("category","EXCESSIVE_SPACING")
            val stretch="1884 年，一位读者在纸上写下一段完整的阅读计划，随后翻开另一页。".repeat(5)
            for(width in listOf(250,310,380,430,510)) {
                val native=StaticLayout.Builder.obtain(stretch,0,stretch.length,TextPaint().apply {textSize=30f},width).setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD).build()
                injectedSpaces+=BookDiagnostics.spaces(stretch,30f,{native},native.lineCount).size
                val bitmap=android.graphics.Bitmap.createBitmap(width,minOf(250,native.height),android.graphics.Bitmap.Config.ARGB_8888)
                val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE);native.draw(canvas)
                for(line in 0 until minOf(3,native.lineCount)) {
                    val top=native.getLineTop(line);val bottom=minOf(bitmap.height,native.getLineBottom(line));if(top>=bottom)continue
                    val ink=BooleanArray(width){x->(top until bottom).any {y->android.graphics.Color.red(bitmap.getPixel(x,y))<130}}
                    val first=ink.indexOfFirst {it};val last=ink.indexOfLast {it};var gap=0
                    if(first>=0)for(x in first..last){gap=if(ink[x])0 else gap+1;paintedGap=maxOf(paintedGap,gap)}
                }
                if(width==380)File(out,"injected-stretched-spacing.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            verify("Independent native over-justification injections calibrate the spacing detector",injectedSpaces>0)
            verify("Rasterized glyph columns independently confirm the injected excessive gap",paintedGap>23)
            calibration.put("injectedSpacingFindings",injectedSpaces).put("rasterMaxGapPx",paintedGap)
            var boundedLines=0;var selectiveFallbacks=0
            val healthy="word ".repeat(24).trim();val controlled=healthy+"\n\n"+stretch
            val controlledStructure=BookStructure(listOf(BookBlock(0,healthy.length),BookBlock(healthy.length+2,controlled.length)),emptyList())
            for(width in listOf(640f,800f,1000f,1200f,1440f)) {
                val layout=BookLayout(controlled,width,800f,30f,columnMode=1,structure=controlledStructure)
                val observed=TextDrawProbe.capture(layout.layout){layout.drawNativeText(it)}
                boundedLines+=observed.count {it.added>0.1f&&it.width<=30f*.65f}
                for(d in layout.spacingDecisions)if(d.reason=="excessive-word-spacing") {
                    selectiveFallbacks++
                    check((layout.layout.getLineForOffset(d.start)..layout.layout.getLineForOffset(d.end-1)).all {observed[it].added==0f}) {"A fallback paragraph still draws stretched spaces"}
                }
            }
            calibration.put("boundedDrawnLines",boundedLines).put("paragraphFallbacks",selectiveFallbacks)
            verify("The actual renderer retains bounded justification and selectively falls back",boundedLines>0&&selectiveFallbacks>0)
            val words="radiant ".repeat(12)+"A ra diant morning begins."
            val broken=injected(words,listOf(BookBlock(0,words.length,origin="words.txt:line=1")))
            val wordFindings=BookDiagnostics.inspect(broken,BookLayout(words,1000f,700f,30f,structure=broken.structure))
            verify("Independent vocabulary corruption is located and corrected only with same-book evidence",wordFindings.any {it.category=="ENGLISH_FRAGMENT"&&it.offset==words.indexOf("ra diant")+2}&&BookTextRepair.english(listOf(words)).single().endsWith("A radiant morning begins."))
            val unknown="A frag ment is not guessed without vocabulary evidence."
            verify("Unknown fragments without supporting vocabulary remain unchanged",BookTextRepair.english(listOf(unknown)).single()==unknown)
            val innocent="index.html\nEdit the file in your editor.\nindex.html\nSave your changes.\nA sample ........ (12)\nThis explanation must remain."
            context=JSONObject().put("phase","repair-counterexamples")
            val noEvents=mutableListOf<LegacyBookRepair.RepairEvent>()
            verify("Technical filenames and isolated dot examples cause no automatic repair",LegacyBookRepair.lines(innocent){noEvents+=it}==innocent.lines()&&noEvents.isEmpty())
            val damaged="Dedication\nA sentence stays complete.\ncompiled.html A Note on Natural History Natural history begins with reading.\ncompiled.html\nCHAPTER I\nA complete paragraph follows."
            val events=mutableListOf<LegacyBookRepair.RepairEvent>();val repaired=LegacyBookRepair.lines(damaged){events+=it}
            verify("Applicable legacy repairs record their rule and before/after evidence",events.isNotEmpty()&&events.all {it.rule.isNotBlank()&&it.before.isNotEmpty()}&&repaired.none {it.contains("compiled.html")})
            File(out,"repair-trace.json").writeText(JSONArray(events.map {JSONObject().put("rule",it.rule).put("inputOffset",it.inputOffset).put("before",it.before).put("after",it.after)}).toString(2))
        }catch(t:Throwable){failure=t.stackTraceToString()}
        File(out,"quality-report.json").writeText(JSONObject().put("passed",failure==null).put("roundCount",rounds.length()).put("rounds",rounds).put("detectorChecks",detections).put("calibration",calibration).put("failureContext",if(failure!=null)context else JSONObject()).put("error",failure).toString(2))
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${rounds.length()} semantic/language/layout rounds and ${detections.length()} detector/repair controls passed\n")})
    }
}
