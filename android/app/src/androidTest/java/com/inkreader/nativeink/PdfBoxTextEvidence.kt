package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.graphics.BitmapFactory
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Optional private-corpus verification for the PDFBox acquisition path.
 *
 * Place the book and an optional `pdfbox-expectations.json` in the app's external
 * `import-samples` directory, then run this suite from the evidence runner:
 * `adb shell am instrument -w -e suite pdfbox <test-package>/<runner>`
 *
 * Expected JSON shape (keys per file name, all optional):
 * {"The Threes.pdf":{"pageCount":84,"chapters":22,"minVerseLines":600,"images":true,"paragraphs":["..."]}}
 */
internal class PdfBoxTextEvidence(private val host:Instrumentation) {
    fun run()=with(host) {
        val out=File(targetContext.getExternalFilesDir(null),"pdfbox-verification").also {it.mkdirs()}
        val checks=JSONArray();var failure:String?=null
        fun verify(label:String,ok:Boolean){checks.put(JSONObject().put("check",label).put("passed",ok));check(ok){label}}
        try {
            val input=File(targetContext.getExternalFilesDir(null),"import-samples")
            val pdf=input.listFiles()?.filter {it.isFile&&it.extension.equals("pdf",true)}?.sortedBy {it.name}?.firstOrNull()
            require(pdf!=null) {"Place the private PDF in the app external-files/import-samples directory"}
            val expectedFile=File(input,"pdfbox-expectations.json")
            val expected=if(expectedFile.isFile)JSONObject(expectedFile.readText()).optJSONObject(pdf.name) else null
            val parsed=BookImporter.parse(pdf,pdf.name,context=targetContext)
            val structure=parsed.structure
            File(out,"structure.json").writeText(structure.json().put("name",pdf.name).put("characters",parsed.text.length).toString(2))
            File(out,"text.txt").writeText(parsed.text)
            val pages=structure.blocks.mapNotNull {it.page}.filter {it>=0}
            verify("Block source pages stay in true page-tree order",pages.zipWithNext().all {(a,b)->a<=b})
            val chapters=structure.chapters
            verify("Outline destinations advance in reading order",chapters.zipWithNext().all {(a,b)->a.offset<b.offset})
            verify("Every chapter lands on a block that names it",chapters.all {chapter->
                val block=structure.blocks.firstOrNull {chapter.offset in it.start until it.end}
                block!=null&&block.kind!="image"&&PdfOutlineMapping.matchesTitle(parsed.text.substring(block.start,block.end),chapter.title)
            })
            verify("Printed dot-leader contents never reach the reading text",!parsed.text.contains("····")&&!parsed.text.contains("......"))
            val longLines=parsed.text.split('\n').map {it.trim()}.filter {it.length>=40}
            verify("No line of 40+ characters is duplicated",longLines.groupingBy {it}.eachCount().values.none {it>1})
            val images=structure.blocks.filter {it.kind=="image"}
            val decodable=images.all {block->
                val file=parsed.images[block.image]?:return@all false
                val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
                BitmapFactory.decodeFile(file.path,options);options.outWidth>0&&options.outHeight>0
            }
            verify("Illustrations are retained as decodable local assets",decodable)
            val verseLines=structure.blocks.filter {block->block.kind=="verse"}.sumOf {block->parsed.text.substring(block.start,block.end).count {ch->ch=='\n'}+1}
            if(expected!=null) {
                expected.optInt("chapters",-1).takeIf {it>=0}?.let {count->verify("Chapter count matches the independent expectation ($count)",chapters.size==count)}
                expected.optInt("pageCount",-1).takeIf {it>=0}?.let {count->
                    val actual=android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(pdf,android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use {it.pageCount}
                    verify("Page count matches the independent expectation ($count)",actual==count)
                }
                expected.optInt("minVerseLines",-1).takeIf {it>=0}?.let {count->verify("Poem lines stay separate verse lines ($count+)",verseLines>=count)}
                if(expected.optBoolean("images",false))verify("The expected illustrations are present",images.isNotEmpty())
                expected.optJSONArray("paragraphs")?.let {array->repeat(array.length()){n->verify("Known paragraph ${n+1} survives in reading order",parsed.text.contains(array.getString(n)))}}
                expected.optString("directorySource").takeIf {it.isNotEmpty()}?.let {source->verify("Directory is labelled $source",structure.directorySource==source)}
            }
            File(out,"pdfbox-report.json").writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("chapters",chapters.size).put("verseLines",verseLines).put("images",images.size).put("characters",parsed.text.length).put("error",failure).toString(2))
        } catch(t:Throwable){failure=t.stackTraceToString()}
        finally {if(failure!=null)File(out,"pdfbox-report.json").writeText(JSONObject().put("passed",false).put("checks",checks).put("error",failure).toString(2))}
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${checks.length()} PDFBox acquisition checks passed\n")})
    }
}
