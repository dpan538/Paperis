package com.inkreader.nativeink

import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Bounded CBZ reader. Pages are read in natural filename order and never extracted by archive path. */
internal object ComicArchive {
    private val imageExtensions=setOf("jpg","jpeg","png","webp","gif","bmp")
    private const val MAX_ENTRIES=4000
    private const val MAX_IMAGE_BYTES=32*1024*1024
    private const val MAX_EXPANDED_BYTES=256*1024*1024

    fun parse(file:File,title:String,progress:(Int,Int)->Unit,workspace:File,format:String="CBZ"):ParsedBook = ZipFile(file).use { zip->
        require(zip.size()<=MAX_ENTRIES) { "漫画压缩包条目过多" }
        val pages=zip.entries().asSequence().filter { entry->
            if(entry.isDirectory)return@filter false
            val name=entry.name.replace('\\','/')
            require(!name.startsWith('/')&&name.split('/').none {it==".."}) { "漫画压缩包路径无效" }
            val parts=name.split('/')
            parts.none {it.startsWith('.')||it.equals("__MACOSX",true)} && name.substringAfterLast('.',"").lowercase() in imageExtensions
        }.toList().sortedWith {a,b->naturalCompare(a.name,b.name)}
        require(pages.isNotEmpty()) { "CBZ 中没有可识别的漫画图片" }
        require(pages.map {it.name.replace('\\','/')}.distinct().size==pages.size) { "CBZ 中存在重复的漫画图片路径" }
        workspace.mkdirs()
        val assembly=BookAssembly().apply {comic=true}
        var expanded=0
        pages.forEachIndexed { index,entry->
            check(!Thread.currentThread().isInterrupted) { "已暂停，进度已保留" }
            val key=BookFiles.hashBytes(entry.name.replace('\\','/').toByteArray())
            val output=File(workspace,key);var written=0
            zip.getInputStream(entry).use {input->output.outputStream().use {out->
                val buffer=ByteArray(32768)
                while(true) {
                    val n=input.read(buffer);if(n<0)break
                    written+=n;expanded+=n
                    require(written<=MAX_IMAGE_BYTES&&expanded<=MAX_EXPANDED_BYTES) { "漫画解压内容过大" }
                    out.write(buffer,0,n)
                }
            }}
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeFile(output.path,bounds)
            require(bounds.outWidth in 1..20000&&bounds.outHeight in 1..20000) { "漫画图片损坏或尺寸过大：${entry.name.substringAfterLast('/')}" }
            assembly.images[key]=output
            assembly.add(SourceBlock("【插图】","image",imageKey=key))
            progress(index+1,pages.size)
        }
        assembly.finish(title,format)
    }

    private fun naturalCompare(left:String,right:String):Int {
        val a=left.replace('\\','/').lowercase();val b=right.replace('\\','/').lowercase()
        val aa=Regex("\\d+|\\D+").findAll(a).map {it.value}.toList();val bb=Regex("\\d+|\\D+").findAll(b).map {it.value}.toList()
        for(i in 0 until minOf(aa.size,bb.size)) {
            val x=aa[i];val y=bb[i]
            val cmp=if(x.first().isDigit()&&y.first().isDigit()) {
                val nx=x.trimStart('0').ifEmpty {"0"};val ny=y.trimStart('0').ifEmpty {"0"}
                nx.length.compareTo(ny.length).takeIf {it!=0}?:nx.compareTo(ny).takeIf {it!=0}?:x.length.compareTo(y.length)
            } else x.compareTo(y)
            if(cmp!=0)return cmp
        }
        return aa.size.compareTo(bb.size).takeIf {it!=0}?:a.compareTo(b)
    }
}
