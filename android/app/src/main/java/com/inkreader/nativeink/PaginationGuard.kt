package com.inkreader.nativeink

import android.text.StaticLayout

/** Repair a broken page plan without changing text, native line breaks, or valid neighbouring pages. */
internal object PaginationGuard {
    data class Result(val pages:List<List<BookLayout.Column>>,val repairs:List<String>)
    fun coverage(text:String,pages:List<List<BookLayout.Column>>,lineCount:Int):Boolean {
        val slices=pages.flatten()
        return pages.isNotEmpty()&&pages.all {it.isNotEmpty()}&&slices.all {it.start>=0&&it.end<=text.length&&it.start<it.end&&it.firstLine in 0 until lineCount&&it.lastLine in it.firstLine until lineCount}&&
            slices.first().start==0&&slices.last().end==text.length&&slices.zipWithNext().all {(a,b)->a.end==b.start}
    }
    fun apply(text:String,native:StaticLayout,plan:List<List<BookLayout.Column>>,columns:Int,bodyHeight:Float,comic:Boolean):Result {
        if(comic||text.isEmpty())return Result(plan,emptyList())
        val repairs=mutableListOf<String>()
        val source=if(coverage(text,plan,native.lineCount))plan else {
            repairs+="rebuild-invalid-source-coverage"
            val slices=mutableListOf<BookLayout.Column>();var first=0
            while(first<native.lineCount) {
                var last=first
                while(last+1<native.lineCount&&native.getLineBottom(last+1)-native.getLineTop(first)<=bodyHeight)last++
                val start=native.getLineStart(first);val end=native.getLineEnd(last)
                if(start<end)slices+=BookLayout.Column(first,last,start,end)
                first=last+1
            }
            slices.chunked(columns.coerceAtLeast(1))
        }
        if(source.none {page->page.all {text.substring(it.start,it.end).isBlank()}})return Result(source,repairs)
        // Carry only whitespace source ranges into an adjacent real column; drawable lines stay intact.
        var carry:Int?=null
        val result=mutableListOf<MutableList<BookLayout.Column>>()
        for(page in source) {
            val kept=mutableListOf<BookLayout.Column>()
            for(column in page) {
                if(text.substring(column.start,column.end).isBlank()) {if(carry==null)carry=column.start}
                else {kept+=column.copy(start=carry?:column.start);carry=null}
            }
            if(kept.isNotEmpty())result+=kept
        }
        if(result.isEmpty())return Result(source,repairs) // The importer rejects empty books; do not invent content.
        if(carry!=null) {val page=result.last();page[page.lastIndex]=page.last().copy(end=text.length)}
        repairs+="remove-whitespace-only-pages-with-source-ranges-retained"
        return Result(result,repairs)
    }
}
