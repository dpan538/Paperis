package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercise the production repair/reaudit gate, including negative controls and failed-save rollback. */
internal class ReflowGuardEvidence(private val host:Instrumentation) {
    fun runCorpus()=with(host) {
        val out=File(targetContext.getExternalFilesDir(null),"reflow-corpus-verification").also {it.mkdirs()}
        val root=File(targetContext.filesDir,"reflow-private-corpus").also {it.deleteRecursively();it.mkdirs()}
        val ctx=object:ContextWrapper(targetContext) {
            override fun getFilesDir()=root
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("private_guard_$name",mode)
        }
        val checks=JSONArray();var failure:String?=null;var current=""
        try {
            val sources=File(targetContext.getExternalFilesDir(null),"import-samples").listFiles().orEmpty().filter {it.extension.lowercase() in setOf("epub","pdf")}.sortedBy {it.name}
            require(sources.size>=6){"Provide the six private regression books"}
            for((number,source)in sources.withIndex()) {
                current=source.name;sendStatus(0,Bundle().apply {putString("stream","Checking private book ${number+1}/${sources.size}\n")})
                val store=BookFiles(ctx);check(store.import(android.net.Uri.fromFile(source)))
                val entry=store.list().first {it.name==source.name};val text=store.text(entry);val structure=store.structure(entry,text)
                val first=JSONObject(File(store.bundle(entry),"quality.json").readText());check(first.getBoolean("passed"))
                val at=text.length*2/3;store.reading(entry).edit().putInt("offset",at).putStringSet("bookmarks",setOf(at.toString())).commit()
                val originalHash=BookFiles.hashFile(source);val images=File(store.bundle(entry),"images").listFiles().orEmpty().associate {it.name to BookFiles.hashFile(it)}
                check(BookFiles.hashFile(store.original(entry)!!)==originalHash)
                store.reprocess(entry.id)
                val rebuilt=checkNotNull(store.get(entry.id));val report=JSONObject(File(store.bundle(rebuilt),"quality.json").readText())
                check(report.getBoolean("passed")&&store.text(rebuilt)==text&&store.structure(rebuilt,text)==structure){"A second checked reflow was not idempotent"}
                check(store.reading(rebuilt).getInt("offset",-1)==at&&store.reading(rebuilt).getStringSet("bookmarks",emptySet())==setOf(at.toString()))
                check(BookFiles.hashFile(store.original(rebuilt)!!)==originalHash&&File(store.bundle(rebuilt),"images").listFiles().orEmpty().associate {it.name to BookFiles.hashFile(it)}==images)
                File(out,"book-$number-audit.json").writeText(report.toString(2))
                checks.put(JSONObject().put("name",source.name).put("passed",true).put("characters",text.length).put("chapters",structure.chapters.size).put("images",images.size).put("reviews",report.getJSONObject("final").getInt("reviews")))
            }
        }catch(t:Throwable){failure=t.stackTraceToString()}
        File(out,"reflow-corpus-report.json").writeText(JSONObject().put("passed",failure==null).put("checks",checks).put("failureBook",if(failure!=null)current else "").put("error",failure).toString(2))
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${checks.length()} private books checked through real import and reprocessing\n")})
    }
    fun run()=with(host) {
        val out=File(targetContext.getExternalFilesDir(null),"reflow-guard-verification").also {it.mkdirs()}
        val checks=JSONArray();val rounds=JSONArray();var failure:String?=null;var context=JSONObject()
        fun verify(name:String,ok:Boolean){checks.put(JSONObject().put("check",name).put("passed",ok));check(ok){name}}
        val root=File(targetContext.filesDir,"reflow-guard-fixtures").also {it.deleteRecursively();it.mkdirs()}
        val ctx=object:ContextWrapper(targetContext) {
            override fun getFilesDir()=root
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("guard_$name",mode)
        }
        fun make(parts:List<Pair<String,String>>,chapters:Boolean=true):ParsedBook {
            val text=parts.joinToString("\n\n"){it.first};var at=0
            val blocks=parts.map {(value,kind)->BookBlock(at,at+value.length,kind,if(kind=="heading")1 else 0,origin="authored:offset=$at").also {at+=value.length+2}}
            val nav=if(chapters)blocks.filter {it.kind=="heading"&&it.start>0}.map {BookChapter(text.substring(it.start,it.end),it.start)}else emptyList()
            return ParsedBook("Fixture",text,"TXT",BookStructure(blocks,nav))
        }
        try {
            val picture=File(root,"picture.png");Bitmap.createBitmap(80,60,Bitmap.Config.ARGB_8888).also {b->b.eraseColor(Color.DKGRAY);picture.outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            val key=BookFiles.hashFile(picture)
            val clean="A quiet reader follows a complete sentence. Any one, a lone reader, and in to the room keep their intended spaces."
            val fixtures=(0..9).map {seed->
                val body=when(seed) {
                    0->"清晨的文字应当自然排列，保持段落与标点。".repeat(8)
                    1->clean.repeat(3)
                    2->"版权信息作者：示例作者出版社：示例出版社"
                    3->"radiant ".repeat(12)+"A ra diant morning begins."
                    4->"Chapter II"
                    5->clean
                    6->clean.repeat(2)
                    7->"版权信息作者：示例作者出版社：示例出版社"
                    8->"A sample ........ (12) and another example ........ (24) explain a technical document; this is prose, not a contents page."
                    else->"مرحبا بالقارئ، هذه فقرة عربية. 日本語と中文保留原本边界。 Un café opens. A frag ment is not guessed."
                }
                val parts=mutableListOf("Fixture" to "heading","Chapter I" to "heading",body to "body")
                if(seed==4)parts+=clean to "body"
                if(seed==7){parts+="ra diant\nAuthor: Poetry Publisher: A metaphor" to "verse";parts+="compiled.html A Note on Geometry" to "caption";parts+="\uFFFC" to "image"}
                var book=make(parts,seed!=5&&seed!=4)
                if(seed==6)book=book.copy(structure=book.structure.copy(chapters=listOf(BookChapter("Chapter I",book.structure.blocks[2].start))))
                if(seed==7)book=book.copy(structure=book.structure.copy(blocks=book.structure.blocks.map {if(it.kind=="image")it.copy(image=key)else it}),images=mapOf(key to picture))
                book
            }
            val sizes=listOf(360f to 760f,760f to 360f,960f to 600f,1136f to 1650f,1680f to 1080f)
            for((seed,input)in fixtures.withIndex())for((profile,size)in sizes.withIndex())for(font in listOf(18f,26f,36f))for(columns in listOf(1,2)) {
                context=JSONObject().put("seed",seed).put("profile",profile).put("font",font).put("columns",columns)
                val result=ReflowGuard.run(input,listOf(ReflowGuard.Profile("matrix",size.first,size.second,font,columns=columns)))
                check(result.report.getBoolean("passed")){"A repair or fallback left a hard error"}
                val final=result.book
                when(seed) {
                    2,7->check(final.text.contains("版权信息\n\n作者：示例作者\n\n出版社：示例出版社")){"Metadata fields remain collapsed"}
                    3->check(final.text.endsWith("A radiant morning begins.")){"Same-book supported word repair did not run"}
                    4->check(final.structure.chapters.any {it.title=="Chapter II"&&final.structure.blocks.any {b->b.kind=="heading"&&b.start==it.offset}}){"Recovered heading lacks a real chapter destination"}
                    5->check(final.structure.chapters.single().offset==final.structure.blocks[1].start){"Missing directory was not built"}
                    6->check(final.structure.chapters.single().offset==final.structure.blocks[1].start){"Wrong directory destination was retained"}
                    else->check(final.text==input.text){"Healthy or ambiguous text changed"}
                }
                if(seed==7) {
                    val protected=input.structure.blocks.filter {it.kind in setOf("verse","caption","image")}.map {it.kind to input.text.substring(it.start,it.end)}
                    check(final.structure.blocks.filter {it.kind in setOf("verse","caption","image")}.map {it.kind to final.text.substring(it.start,it.end)}==protected)
                    check(final.images==input.images&&BookFiles.hashFile(picture)==key)
                }
                check(result.report.getJSONArray("rounds").length()<=ReflowGuard.MAX_REPAIRS)
                rounds.put(JSONObject(context.toString()).put("passed",true).put("stop",result.report.getString("stop")))
            }
            verify("All 300 production audit/repair/profile combinations executed",rounds.length()==300)
            val protectedAssembly=BookAssembly().apply {
                add(SourceBlock("radiant gentle brilliant ".repeat(12)))
                add(SourceBlock("A ra diant caption","caption"));add(SourceBlock("A ge ntle quotation","quote"));add(SourceBlock("A br illiant verse","verse"))
            }.finish("Protected source","HTML")
            verify("The upstream assembler also preserves literal captions, quotations and verse",protectedAssembly.text.contains("A ra diant caption")&&protectedAssembly.text.contains("A ge ntle quotation")&&protectedAssembly.text.contains("A br illiant verse"))
            val p=listOf(ReflowGuard.Profile("control",1000f,700f,30f))
            val dialogue=make(listOf("Fixture" to "heading","Author: I am speaking inside a prose example. Publisher: This is dialogue rather than a copyright page. Keep this deliberately labelled conversation in one original paragraph." to "body"),false)
            verify("Metadata-like dialogue without semantic or copyright evidence remains unchanged",ReflowGuard.run(dialogue,p).book==dialogue)
            val ambiguous=fixtures[8]
            var calls=0
            val stable=ReflowGuard.run(ambiguous,p,repairer={b,_->calls++;ReflowGuard.Repair(b,listOf(JSONObject().put("rule","repeat")))})
            verify("An unchanged candidate stops immediately instead of recurring",calls==1&&stable.book==ambiguous&&stable.report.getString("stop")=="no-safe-change")
            val worse=ReflowGuard.run(fixtures[2],p,repairer={b,_->ReflowGuard.Repair(b.copy(text="discarded body"),listOf(JSONObject().put("rule","bad-repair")))})
            verify("Body loss or a non-improving candidate is rolled back",worse.book==fixtures[2]&&worse.report.getString("stop")=="no-improvement")
            val sourceNav=fixtures[6].copy(structure=fixtures[6].structure.copy(chapters=listOf(BookChapter("Chapter I",fixtures[6].text.length+20,1,"epub"))))
            val navLoss=ReflowGuard.run(sourceNav,p,repairer={b,_->ReflowGuard.Repair(b.copy(structure=b.structure.copy(chapters=emptyList())),listOf(JSONObject().put("rule","erase-directory")))})
            verify("A failing authoritative directory cannot be erased to make the audit look clean",navLoss.book==sourceNav&&!navLoss.report.getBoolean("passed"))
            val protected=ReflowGuard.run(fixtures[7],p,repairer={b,issues->val change=ReflowGuard.repair(b,issues);change.copy(book=change.book.copy(text=change.book.text.replace("A metaphor","X metaphor")))})
            verify("A purported repair cannot mutate poetry while fixing nearby metadata",protected.book==fixtures[7]&&!protected.report.getJSONArray("rounds").getJSONObject(0).getBoolean("protectedContentIntact"))
            val many=make(listOf("Fixture" to "heading")+(1..5).map {"Author: Writer $it Publisher: Press $it" to "metadata"},false)
            val bounded=ReflowGuard.run(many,p,repairer={b,issues->val first=issues.first {it.finding.category=="METADATA_BOUNDARY"}.finding.block;ReflowGuard.repair(b,issues.filter {it.finding.block==first})})
            verify("Repeated repair/reaudit is capped at three improving rounds",bounded.report.getJSONArray("rounds").length()==3&&bounded.report.getString("stop")=="limit"&&bounded.report.getJSONObject("final").getInt("reviews")>0)
            val imageMissing=fixtures[7].copy(images=emptyMap());val refused=ReflowGuard.run(imageMissing,p)
            verify("Missing image assets fail the gate instead of becoming a blank successful book",!refused.report.getBoolean("passed")&&refused.report.getJSONObject("final").getJSONArray("findings").toString().contains("INVALID_IMAGE_ASSET"))
            val verse=fixtures[7].structure.blocks.first {it.kind=="verse"};val anchors=ReadingAnchorMap(fixtures[7].text,ReflowGuard.run(fixtures[7],p).book.text)
            val repaired=ReflowGuard.run(fixtures[7],p).book
            verify("Reading anchors survive repairs before a protected verse",anchors.map(verse.start)==repaired.structure.blocks.first {it.kind=="verse"}.start)
            // Calibrate pagination repair independently with a dropped range and a whitespace-only page.
            val raw="First paragraph.\n\n\n\nLast paragraph.";val layout=BookLayout(raw,800f,600f,30f)
            val dropped=layout.pages.mapIndexed {i,page->if(i==0)page.mapIndexed {j,c->if(j==0)c.copy(start=c.start+1)else c}else page}
            val recovered=PaginationGuard.apply(raw,layout.layout,dropped,layout.columns,layout.bodyHeight,false)
            verify("A dropped-range injection rebuilds a complete source plan",recovered.repairs.contains("rebuild-invalid-source-coverage")&&PaginationGuard.coverage(raw,recovered.pages,layout.layout.lineCount))
            val firstEnd=raw.indexOf('\n');val lastStart=raw.indexOf("Last")
            val blankPlan=listOf(listOf(BookLayout.Column(0,0,0,firstEnd)),listOf(BookLayout.Column(1,1,firstEnd,lastStart)),listOf(BookLayout.Column(layout.layout.getLineForOffset(lastStart),layout.layout.lineCount-1,lastStart,raw.length)))
            val noBlank=PaginationGuard.apply(raw,layout.layout,blankPlan,1,layout.bodyHeight,false)
            verify("A whitespace-only page is removed without deleting its source range",noBlank.pages.size==2&&PaginationGuard.coverage(raw,noBlank.pages,layout.layout.lineCount)&&noBlank.pages.none {raw.substring(it.first().start,it.last().end).isBlank()})
            // A real reprocessing failure must leave the old committed bundle/state/source untouched.
            val epub=File(root,"broken.epub")
            ZipOutputStream(epub.outputStream()).use {z->
                fun entry(name:String,value:String){z.putNextEntry(ZipEntry(name));z.write(value.toByteArray());z.closeEntry()}
                entry("mimetype","application/epub+zip")
                entry("META-INF/container.xml","<container><rootfiles><rootfile full-path=\"content.opf\"/></rootfiles></container>")
                entry("content.opf","<package><metadata><title>Broken picture</title></metadata><manifest><item id=\"c\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"i\" href=\"bad.png\" media-type=\"image/png\"/></manifest><spine><itemref idref=\"c\"/></spine></package>")
                entry("chapter.xhtml","<html><body><h1>Chapter I</h1><p>Saved prose remains readable and unchanged.</p><img src=\"bad.png\"/></body></html>")
                entry("bad.png","not a real PNG")
            }
            val store=BookFiles(ctx);val id=BookFiles.hashFile(epub);val saved=store.commit(id,"broken.epub","",make(listOf("Fixture" to "heading","A previously saved readable paragraph." to "body")),epub)
            store.reading(saved).edit().putInt("offset",12).putStringSet("bookmarks",setOf("12")).commit()
            val oldText=store.text(saved);val failed=runCatching {store.reprocess(id)}
            val reloaded=BookFiles(ctx);val still=checkNotNull(reloaded.get(id));val report=JSONObject(File(root,"import-jobs/reflow-$id-auto/quality.json").readText())
            verify("A hard audit failure refuses the real commit and retains the old book/state/original",failed.isFailure&&!report.getBoolean("passed")&&still.bundle==saved.bundle&&reloaded.text(still)==oldText&&reloaded.reading(still).getInt("offset",-1)==12&&reloaded.reading(still).getStringSet("bookmarks",emptySet())==setOf("12")&&BookFiles.hashFile(reloaded.original(still)!!)==id)
            File(out,"rollback-audit.json").writeText(report.toString(2))
        }catch(t:Throwable){failure=t.stackTraceToString()}
        File(out,"reflow-guard-report.json").writeText(JSONObject().put("passed",failure==null).put("roundCount",rounds.length()).put("rounds",rounds).put("checks",checks).put("failureContext",if(failure!=null)context else JSONObject()).put("error",failure).toString(2))
        finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${rounds.length()} automatic reflow checks passed\n")})
    }
}
