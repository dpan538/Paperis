package com.inkreader.nativeink

import android.text.StaticLayout

/** Keep native line breaks; permit bounded word spacing independently for each body paragraph. */
internal object ParagraphSpacing {
    data class Decision(val block:Int,val start:Int,val end:Int,val justified:Boolean,val reason:String,val maxSpaceRatio:Float)
    data class Result(val decisions:List<Decision>,val added:FloatArray)
    fun decide(text:String,structure:BookStructure,normal:StaticLayout,font:Float):Result {
        val added=FloatArray(normal.lineCount)
        val natural=normal.paint.measureText(" ")
        val decisions=structure.blocks.mapIndexed {index,block->
            var ratio=1f;var stretched=false
            val first=normal.getLineForOffset(block.start)
            val last=normal.getLineForOffset((block.end-1).coerceAtLeast(block.start))
            if(block.kind=="body")for(line in first..last) {
                val start=normal.getLineStart(line);val end=normal.getLineVisibleEnd(line)
                val endsParagraph=normal.getLineEnd(line)>=text.length||text[normal.getLineEnd(line)-1]=='\n'
                val spaces=(start until end).count {text[it]==' '}
                if(endsParagraph||spaces==0||normal.getParagraphDirection(line)!=1)continue
                // getLineMax includes leading margins and excludes trailing whitespace.
                val extra=((normal.width-normal.getLineMax(line))/spaces).coerceAtLeast(0f)
                added[line]=extra
                if(natural>0.1f)ratio=maxOf(ratio,(natural+extra)/natural)
                if(extra>maxOf(natural*.8f,font*.18f))stretched=true
            }
            val supported=(first..last).all {normal.getParagraphDirection(it)==1}
            val use=block.kind=="body"&&supported&&!stretched
            if(!use)for(line in first..last)added[line]=0f
            val reason=when {block.kind!="body"->"semantic-${block.kind}";!supported->"preserve-script-direction";stretched->"excessive-word-spacing";else->"bounded-justification"}
            Decision(index,block.start,block.end,use,reason,ratio)
        }
        return Result(decisions,added)
    }
}
