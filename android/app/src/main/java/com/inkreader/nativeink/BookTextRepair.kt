package com.inkreader.nativeink

/** Conservative repairs in the derived copy; the original file is never changed. */
internal object BookTextRepair {
    private val independent=setOf("a","an","the","of","to","in","on","at","for","by","as","or","and","not","any","one","some","all","each","every","no","yes","is","are","be","been","am","was","were","he","she","it","we","us","you","they","them","his","her","our","their","my","me")
    private val endings=setOf("ing","tion","sion","ment","ness","ity","ous","ance","ence","able","ible","ally","ly")
    fun english(paragraphs:List<String>):List<String> {
        val words=Regex("[A-Za-z]+")
        val frequency=paragraphs.asSequence().flatMap {words.findAll(it).map {m->m.value}.filter {word->word.all(Char::isLowerCase)}}.groupingBy {it}.eachCount()
        val split=Regex("\\b([a-z]{2,18}) ([a-z]{2,18})\\b")
        return paragraphs.map {paragraph->
            var cursor=0;var copied=0;val result=StringBuilder()
            while(cursor<paragraph.length) {
                val m=split.find(paragraph,cursor)?:break
                val a=m.groupValues[1];val b=m.groupValues[2]
                // Require rare lowercase fragments and repeated complete forms from this same book.
                val joined=frequency[a+b]?:0
                val rarePrefix=frequency[a]==1||frequency[a]==2&&joined>=10
                if(a !in independent&&b !in independent&&(a.length<=2||b in endings)&&rarePrefix&&frequency[b]==1&&joined>=3) {
                    result.append(paragraph,copied,m.range.first).append(a).append(b)
                    copied=m.range.last+1;cursor=copied
                } else cursor=m.range.first+a.length+1
            }
            val repaired=result.append(paragraph,copied,paragraph.length).toString()
            if(words.findAll(repaired).count()>=3)repaired.replace(Regex("(?<=[A-Za-z]{2}) +(?=[,;:!?](?:\\s|$)|\\.(?:\\s|$))"),"")else repaired
        }
    }
}
