package com.inkreader.nativeink

/** Repair early flattened imports. Source navigation still requires the original file. */
internal object LegacyBookRepair {
    private fun key(s:String)=s.filterNot {it.isWhitespace()}.lowercase()
    private val contents=setOf("目录","目錄","contents","tableofcontents")
    fun lines(value:String):List<String> {
        val input=value.replace("\r","").lineSequence().map {it.trim()}.filter {it.isNotEmpty()}.toList()
        val start=input.indexOfFirst {key(it) in contents}
        val cleaned=if(start>=0) {
            val prose=(start+1 until input.size).firstOrNull {input[it].length>=80&&input[it].any {c->c in "。！？.!?"}}
            if(prose!=null) {
                var body=if(input[prose-1].length<=100)prose-1 else prose
                while(body>start+1&&key(input[body-1])==key(input[body]))body--
                input.take(start)+input.drop(body)
            } else input
        } else input
        val result=mutableListOf<String>()
        for(line in cleaned) {
            if(key(line) in contents)continue
            // Old HTML imports included both <head><title> and the visible heading.
            val heading=line.length<=72&&line.none {it in "，。！？,.;!?；：:…"}
            if(heading&&result.lastOrNull()?.let {key(it)==key(line)}==true)continue
            result+=line
        }
        return result
    }
}
