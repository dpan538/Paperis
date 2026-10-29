package com.inkreader.nativeink

/** Whitespace-only repair maps by source sequence, even when whole paragraphs repeat. */
internal class ReadingAnchorMap(private val old:String,private val new:String) {
    private val oldPositions=IntArray(old.length)
    private val newPositions=IntArray(new.length)
    private val before=normalize(old,oldPositions)
    private val after=normalize(new,newPositions)
    private val prefix=old.indices.firstOrNull {it>=new.length||old[it]!=new[it]}?:minOf(old.length,new.length)
    private val suffix=run {var n=0;while(n<old.length-prefix&&n<new.length-prefix&&old[old.lastIndex-n]==new[new.lastIndex-n])n++;n}
    private fun normalize(value:String,positions:IntArray):String {
        val out=StringBuilder();value.forEachIndexed {i,c->if(!c.isWhitespace()){positions[out.length]=i;out.append(c)}};return out.toString()
    }
    fun map(offset:Int):Int {
        if(old.isEmpty()||new.isEmpty())return 0
        val at=offset.coerceIn(0,old.lastIndex)
        if(at<prefix)return at
        if(at>=old.length-suffix)return (at+new.length-old.length).coerceIn(0,new.lastIndex)
        val found=java.util.Arrays.binarySearch(oldPositions,0,before.length,at)
        val index=if(found>=0)found else -found-1
        if(before==after)return (newPositions.getOrNull(index.takeIf {it<after.length}?:-1)?:new.lastIndex).coerceIn(0,new.lastIndex)
        val needle=before.substring(index.coerceAtMost(before.length),minOf(index+48,before.length))
        val expected=(index.toLong()*after.length/before.length.coerceAtLeast(1)).toInt()
        var best=-1
        if(needle.length>=12) {
            var next=after.indexOf(needle)
            while(next>=0) {if(best<0||kotlin.math.abs(next-expected)<kotlin.math.abs(best-expected))best=next;next=after.indexOf(needle,next+1)}
        }
        return if(best>=0)newPositions[best] else (at.toLong()*new.length/old.length).toInt().coerceIn(0,new.lastIndex)
    }
}
