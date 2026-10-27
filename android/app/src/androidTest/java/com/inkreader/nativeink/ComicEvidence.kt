package com.inkreader.nativeink

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.content.Intent
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 300 deterministic book × screen-layout runs over CBZ comics, image-rich EPUB and illustrated PDF. */
internal class ComicEvidence(private val host:Instrumentation) {
    private data class Fixture(val file:File,val kind:String,val expectedKeys:List<String>,val expectedSentences:List<String>,val expectedAssetHashes:Map<String,String> = emptyMap())
    private data class Profile(val width:Int,val height:Int,val densityDpi:Int,val fontScale:Float,val left:Int,val top:Int,val right:Int,val bottom:Int)
    private fun <T> ui(block:()->T):T {
        val result=AtomicReference<Result<T>>()
        host.runOnMainSync {result.set(runCatching(block))}
        return result.get().getOrThrow()
    }

    fun run()=with(host) {
        val output=File(targetContext.getExternalFilesDir(null),"comic-300-verification").also {it.mkdirs()}
        val work=File(targetContext.cacheDir,"comic-300-work").also {it.deleteRecursively();it.mkdirs()}
        val rounds=JSONArray();var failure:String?=null
        val profiles=listOf(
            Profile(3408,2272,320,1f,0,0,0,0),
            Profile(3240,2160,480,1f,0,24,0,36),
            Profile(2272,3408,400,1f,0,36,0,48),
            Profile(1920,1200,320,1f,0,0,0,0),
            Profile(1280,800,320,1.15f,20,24,20,28),
            Profile(2560,1600,320,1f,0,0,0,0),
            Profile(2048,1536,320,1.25f,32,24,24,32),
            Profile(1600,2560,400,1.1f,0,40,0,48),
            Profile(2560,1080,320,1f,28,0,28,0),
            Profile(1920,1280,240,1.4f,0,48,0,56)
        )
        fun fixtureImage(index:Int,page:Int):Bitmap {
            val bitmap=Bitmap.createBitmap(360+(page%3)*72,480+(index%2)*80,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap)
            val backgrounds=intArrayOf(Color.WHITE,Color.rgb(245,224,150),Color.rgb(120,184,218),Color.rgb(218,142,163),Color.rgb(160,206,144))
            canvas.drawColor(backgrounds[(index+page)%backgrounds.size])
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;strokeWidth=5f;color=Color.BLACK}
            canvas.drawRect(8f,8f,bitmap.width-8f,bitmap.height-8f,paint)
            paint.style=Paint.Style.FILL;paint.color=Color.rgb((index*31+page*13)%256,(index*17+page*29)%256,(index*43+page*7)%256)
            canvas.drawRect(bitmap.width*.12f,bitmap.height*.14f,bitmap.width*.88f,bitmap.height*.48f,paint)
            paint.color=Color.BLACK;paint.textSize=30f;canvas.drawText("${index+1} · ${page+1}",20f,bitmap.height*.82f,paint)
            paint.strokeWidth=9f;paint.style=Paint.Style.STROKE
            canvas.drawLine(20f,bitmap.height*.58f,bitmap.width*.76f,bitmap.height*.58f,paint)
            return bitmap
        }
        fun png(index:Int,page:Int):ByteArray {
            val bitmap=fixtureImage(index,page)
            return try {val out=java.io.ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);out.toByteArray()} finally {bitmap.recycle()}
        }
        fun encodedImage(index:Int,page:Int,extension:String):ByteArray {
            if(extension.lowercase()!="jpg"&&extension.lowercase()!="jpeg")return png(index,page)
            val bitmap=fixtureImage(index,page)
            return try {val out=java.io.ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,94,out);out.toByteArray()} finally {bitmap.recycle()}
        }
        fun zip(file:File,items:List<Pair<String,ByteArray>>) {
            ZipOutputStream(FileOutputStream(file)).use {zip->items.forEach {(name,bytes)->zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()}}
        }
        fun cbz(index:Int):Fixture {
            val extension=if(index%3==0)"zip" else "cbz"
            val file=File(work,"comic-$index.$extension");val count=2+(index*7%18)
            val paths=(1..count).map {page->"pages/page$page.${if(page%4==0)"jpg" else "png"}"}
            val images=paths.mapIndexed {at,path->path to encodedImage(index,at,path.substringAfterLast('.'))}
            val entries=images.reversed()
            zip(file,entries)
            return Fixture(file,extension.uppercase(),paths.map {BookFiles.hashBytes(it.toByteArray())},emptyList(),images.associate {(path,bytes)->BookFiles.hashBytes(path.toByteArray()) to BookFiles.hashBytes(bytes)})
        }
        fun epub(index:Int):Fixture {
            val file=File(work,"illustrated-$index.epub");val count=3+(index*11%18)
            val names=(0 until count).map {"OEBPS/images/page$it.png"}
            val title="Illustrated ${index+1}"
            val sentences=listOf("Chapter ${index+1}: A Quiet Morning","Mixed English words stay in their original order.","中文段落保留原有标点与分段。")
            val xhtml=buildString {
                append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>${sentences[0]}</h1><p>${sentences[1]}</p><p>${sentences[2]}</p>")
                names.forEachIndexed {at,name->append("<figure><img src=\"images/page$at.png\"/><figcaption>Plate ${at+1}</figcaption></figure>")}
                append("<h2>Back matter</h2><p>References and notes remain readable.</p></body></html>")
            }
            val opf=buildString {
                append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"id\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"id\">fixture-$index</dc:identifier><dc:title>$title</dc:title><dc:language>en</dc:language></metadata><manifest><item id=\"chapter\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
                names.forEachIndexed {at,_->append("<item id=\"image$at\" href=\"images/page$at.png\" media-type=\"image/png\"/>")}
                append("</manifest><spine><itemref idref=\"chapter\"/></spine></package>")
            }
            val nav="<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><body><nav epub:type=\"toc\"><ol><li><a href=\"chapter.xhtml\">${sentences[0]}</a></li></ol></nav></body></html>"
            val items=mutableListOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\"><rootfiles><rootfile full-path=\"OEBPS/book.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>".toByteArray(),
                "OEBPS/book.opf" to opf.toByteArray(),"OEBPS/chapter.xhtml" to xhtml.toByteArray(),"OEBPS/nav.xhtml" to nav.toByteArray())
            val images=names.mapIndexed {at,name->name to png(index,at)}
            items+=images
            zip(file,items)
            return Fixture(file,"EPUB",names.map {BookFiles.hashBytes(it.toByteArray())},sentences,images.associate {(path,bytes)->BookFiles.hashBytes(path.toByteArray()) to BookFiles.hashBytes(bytes)})
        }
        fun pdf(index:Int):Fixture {
            val file=File(work,"illustrated-$index.pdf");val doc=PdfDocument();val pages=2+index%5
            repeat(pages) {pageIndex->
                val page=doc.startPage(PdfDocument.PageInfo.Builder(720,960,pageIndex+1).create());val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.BLACK;textSize=28f}
                page.canvas.drawText("Illustrated PDF ${index+1}",50f,70f,paint)
                page.canvas.drawText("Chapter ${pageIndex+1}: Color panels and exact text.",50f,112f,paint)
                val image=fixtureImage(index,pageIndex)
                page.canvas.drawBitmap(image,null,android.graphics.RectF(90f,180f,630f,900f),Paint(Paint.FILTER_BITMAP_FLAG));image.recycle()
                doc.finishPage(page)
            }
            file.outputStream().use {doc.writeTo(it)};doc.close()
            return Fixture(file,"PDF",emptyList(),listOf("Color panels and exact text."))
        }
        fun imageGray(book:ParsedBook):Map<String,Bitmap> = book.images.mapValues {(_,file)->
            val source=checkNotNull(BitmapFactoryCompat.decode(file))
            try {InkImage.grayscale16(source)} finally {source.recycle()}
        }
        try {
            val fixtures=buildList {
                repeat(10){add(cbz(it))};repeat(10){add(epub(it))};repeat(10){add(pdf(it))}
            }
            var run=0
            for((bookIndex,fixture) in fixtures.withIndex()) {
                val originalHash=BookFiles.hashFile(fixture.file)
                val parsed=BookImporter.parse(fixture.file,fixture.file.name,{_,_->},targetContext,File(work,"assets-$bookIndex"))
                check(parsed.format==fixture.kind) {"Format mismatch: ${fixture.file.name}"}
                check(fixture.expectedSentences.all {parsed.text.contains(it)}||fixture.kind in setOf("CBZ","ZIP")) {"Text extraction lost a known sentence: ${fixture.file.name}; extracted=${parsed.text.take(500)}"}
                check(parsed.images.isNotEmpty()) {"No images extracted from ${fixture.file.name}"}
                if(fixture.expectedKeys.isNotEmpty()) {
                    val actual=parsed.structure.blocks.filter {it.kind=="image"}.map {it.image}
                    check(actual==fixture.expectedKeys) {"Image order or image destinations changed: ${fixture.file.name}"}
                } else check(parsed.structure.blocks.count {it.kind=="image"}>=2) {"Illustrated PDF pages were not retained: ${fixture.file.name}"}
                fixture.expectedAssetHashes.forEach {(key,hash)->check(parsed.images[key]?.let {BookFiles.hashFile(it)==hash}==true) {"Imported image asset changed from its source bytes: ${fixture.file.name}/$key"}}
                val grayscale=imageGray(parsed)
                check(grayscale.size==parsed.images.size)
                grayscale.forEach {(key,bitmap)->
                    val pixels=IntArray(bitmap.width*bitmap.height);bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    check(pixels.all {val r=Color.red(it);val g=Color.green(it);val b=Color.blue(it);r==g&&g==b}) {"Color survived monochrome conversion: $key"}
                    check(pixels.map {Color.red(it)}.distinct().size<=16) {"Image exceeds the 16-level grayscale palette: $key"}
                }
                if(fixture.kind in setOf("CBZ","ZIP"))check(parsed.structure.comic&&parsed.structure.blocks.count {it.kind=="image"}==fixture.expectedKeys.size) {"Image archive did not become one page per file image"}
                for(profileIndex in profiles.indices) {
                    val profile=profiles[profileIndex]
                    val density=profile.densityDpi/160f
                    val viewport=ReaderViewport.calculate(profile.width,profile.height,density,profile.fontScale,profile.left,profile.top,profile.right,profile.bottom)
                    val width=viewport.width;val height=viewport.height
                    val fontSp=floatArrayOf(26f,28f,30f,32f,36f,40f)[(bookIndex*3+profileIndex)%6]
                    val font=fontSp*profile.fontScale
                    val spacing=floatArrayOf(.40f,.67f,.82f,.95f)[(bookIndex+profileIndex*3)%4]
                    val columns=(bookIndex+profileIndex)%3
                    val layout=BookLayout(parsed.text,width,height,font,spacing,columns,Typeface.create(if((bookIndex+profileIndex)%2==0)"serif" else "sans-serif",Typeface.NORMAL),structure=parsed.structure,imageProvider={grayscale[it]},imageSize={key->parsed.images[key]?.let {file->BitmapFactoryCompat.size(file)}})
                    val slices=layout.pages.flatten()
                    check(slices.isNotEmpty()&&slices.first().start==0&&slices.last().end==parsed.text.length) {"Page layout omitted text ranges: ${fixture.file.name}/$profileIndex"}
                    check(slices.zipWithNext().all {(a,b)->a.end==b.start}) {"Page layout duplicated or skipped source text: ${fixture.file.name}/$profileIndex"}
                    check(slices.all {layout.layout.getLineBottom(it.lastLine)-layout.layout.getLineTop(it.firstLine)<=layout.bodyHeight+.5f}) {"Page content exceeded the viewport: ${fixture.file.name}/$profileIndex"}
                    check((0 until layout.layout.lineCount).all {layout.layout.getLineRight(it)<=layout.columnWidth+.5f}) {"Text or illustration exceeded its column: ${fixture.file.name}/$profileIndex"}
                    if(fixture.kind in setOf("CBZ","ZIP")) {
                        check(layout.columns==1&&layout.pages.size==fixture.expectedKeys.size) {"Comic pages split into columns or merged together: ${fixture.file.name}/$profileIndex"}
                        check(parsed.structure.blocks.filter {it.kind=="image"}.all {layout.pages[layout.pageForOffset(it.start)].size==1}) {"Comic image destination does not map to a page"}
                    }
                    val render=Bitmap.createBitmap(width.toInt().coerceAtLeast(1),height.toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                    try {
                        val canvas=Canvas(render);canvas.drawColor(Color.WHITE)
                        val page=(run+bookIndex+profileIndex)%layout.pages.size
                        layout.draw(canvas,page)
                        val sample=render.getPixel((render.width/2).coerceIn(0,render.width-1),(render.height/2).coerceIn(0,render.height-1))
                        check(kotlin.math.abs(Color.red(sample)-Color.green(sample))<=1&&kotlin.math.abs(Color.green(sample)-Color.blue(sample))<=1) {"Rendered page contains colored pixels: ${fixture.file.name}/$profileIndex"}
                    } finally {render.recycle()}
                    rounds.put(JSONObject().put("round",run+1).put("book",fixture.file.name).put("format",fixture.kind).put("profile",profileIndex).put("widthDp",width).put("heightDp",height).put("fontPx",font).put("images",parsed.images.size).put("passed",true))
                    run++
                }
                check(BookFiles.hashFile(fixture.file)==originalHash) {"Source file changed during import: ${fixture.file.name}"}
                grayscale.values.forEach {it.recycle()}
            }
            check(run==300) {"Expected 300 combinations, ran $run"}
            // Exercise the complete persistent import path once, including its source-copy and structure round trip.
            val comic=fixtures.first {it.kind=="CBZ"};val library=BookFiles(targetContext);val sourceHash=BookFiles.hashFile(comic.file)
            library.import(android.net.Uri.fromFile(comic.file),providedName=comic.file.name,rebuild=true)
            val saved=checkNotNull(library.list().firstOrNull {it.id==sourceHash})
            check(saved.format=="CBZ"&&library.original(saved)?.let {BookFiles.hashFile(it)==sourceHash}==true) {"CBZ original was not durably preserved"}
            val savedText=library.text(saved);val savedStructure=library.structure(saved,savedText)
            check(savedStructure.comic&&savedStructure.blocks.count {it.kind=="image"}==comic.expectedKeys.size&&library.image(saved,savedStructure.blocks.first {it.kind=="image"}.image)!=null) {"Persisted comic structure or grayscale page could not be reopened"}
            check(BookFiles.hashFile(comic.file)==BookFiles.hashFile(checkNotNull(library.original(saved)))) {"Original CBZ changed after page conversion"}
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
            val deadline=android.os.SystemClock.uptimeMillis()+30000
            while((ui {activity.inkView.width<=0||activity.inkView.height<=0})&&android.os.SystemClock.uptimeMillis()<deadline)android.os.SystemClock.sleep(50)
            ui {activity.inkView.openBook(saved,savedText)}
            while(ui {activity.inkView.isRefreshing}&&android.os.SystemClock.uptimeMillis()<deadline)android.os.SystemClock.sleep(50)
            check(ui {activity.inkView.currentStructure.comic&&activity.inkView.pageCount==comic.expectedKeys.size&&activity.inkView.currentPage==0}) {"Comic could not open as a full-screen, one-image-per-page reading flow"}
            android.os.SystemClock.sleep(180)
            val firstPage=checkNotNull(uiAutomation.takeScreenshot())
            ui {activity.inkView.turnPage(1)}
            val turnDeadline=android.os.SystemClock.uptimeMillis()+10000
            while((ui {activity.inkView.currentPage!=1||activity.inkView.isRefreshing})&&android.os.SystemClock.uptimeMillis()<turnDeadline)android.os.SystemClock.sleep(30)
            check(ui {activity.inkView.currentPage==1&&activity.inkView.currentOffset>=savedStructure.blocks.filter {it.kind=="image"}[1].start}) {"Comic page-turn action did not settle on the next image"}
            val screenshot=uiAutomation.takeScreenshot()
            if(screenshot!=null)try {
                check(!firstPage.sameAs(screenshot)) {"The settled comic reader frame did not change after a page turn"}
                File(output,"comic-reader-page-2.png").outputStream().use {screenshot.compress(Bitmap.CompressFormat.PNG,100,it)}
            } finally {screenshot.recycle();firstPage.recycle()} else firstPage.recycle()
            ui {activity.finish()}
        } catch(t:Throwable) {failure=t.stackTraceToString()}
        finally {
            work.deleteRecursively()
            File(output,"comic-300-report.json").writeText(JSONObject().put("passed",failure==null).put("roundCount",rounds.length()).put("formats",JSONArray(listOf("CBZ","ZIP","EPUB","PDF"))).put("profileCount",profiles.size).put("rounds",rounds).put("persistentImportAndReaderSmoke",failure==null).put("error",failure).toString(2))
            finish(if(failure==null)Activity.RESULT_OK else Activity.RESULT_CANCELED,Bundle().apply {putString("stream",failure?:"${rounds.length()} image-book import/layout rounds passed across three formats and ten screen profiles\n")})
        }
    }

    private object BitmapFactoryCompat {
        fun decode(file:File):Bitmap?=android.graphics.BitmapFactory.decodeFile(file.path)
        fun size(file:File):Pair<Int,Int>? {
            val options=android.graphics.BitmapFactory.Options().apply {inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeFile(file.path,options)
            return if(options.outWidth>0&&options.outHeight>0)options.outWidth to options.outHeight else null
        }
    }
}
