package com.inkreader.nativeink

/** Structural evidence shared by plain text, EPUB and PDF; short prose is never sufficient alone. */
internal object TextSemantics {
    private val fields=Regex("^(?:(?:Author|Editor|Translator)[：:]|ISBN\\b|ISSN\\b|Copyright\\b|All rights reserved\\b|Published by\\b|Translated by\\b|Edited by\\b|Printed in\\b|First published\\b|(?:书号|書號|定价|定價|作者|译者|譯者|著者|出版者|出版社|版次|印次|开本|開本|字数|字數|责任编辑|責任編輯|版权所有|版權所有)[：:　 ]|https?://|www\\.)",RegexOption.IGNORE_CASE)
    fun metadata(value:String)=value.length<=180&&(fields.containsMatchIn(value.trim())||Regex("^.{3,18}(?:著|譯|译|编著|編著|出版社)$").matches(value.trim())||Regex("^(?:[0-9]{4}年[0-9一二三四五六七八九十]{1,3}月(?:[0-9]{1,2}日)?(?:于|於)?.{0,12}|[0-9]{4}[-/.][0-9]{1,2}[-/.][0-9]{1,2})$").matches(value.trim()))
    fun englishTitle(value:String):Boolean {
        val t=value.trim();if(t.length !in 3..80||metadata(t)||Regex("[.!?;:，。！？：；]").containsMatchIn(t))return false
        val words=t.split(Regex("\\s+")).filter {it.any(Char::isLetter)}
        if(words.size !in 2..12)return false
        val minor=setOf("a","an","the","of","and","or","to","in","on","for","with","as","by","at","from")
        return words.filter {it.lowercase() !in minor}.all {it.firstOrNull()?.isUpperCase()==true}&&words.count {it.firstOrNull()?.isUpperCase()==true}>=2
    }
    fun shortChinese(value:String)=value.length in 2..16&&value.all {Character.UnicodeScript.of(it.code)==Character.UnicodeScript.HAN||it==' '||it=='　'}&&!metadata(value)
}
