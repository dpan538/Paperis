package com.inkreader.nativeink

/** Keep file errors useful when the UI is English, without exposing internal stack traces. */
internal object ReaderErrors {
    fun message(value: String?, english: Boolean): String {
        val raw=value.orEmpty()
        if(raw.contains("No content provider",true)||raw.contains("Permission Denial",true)||raw.contains("content://")||raw.contains("FileNotFoundException"))
            return if(english) "This shared file is no longer accessible. Save it to a local folder, then choose it from Import files." else "分享文件已失效或无法访问。请先保存到本地文件夹，再通过“导入文件”选择。"
        if(Regex("zip END|zip file|ZipException|XMLPullParser|XmlPullParser|expected.*tag|not well-formed",RegexOption.IGNORE_CASE).containsMatchIn(raw))
            return if(english)"This document is damaged or incomplete. Download the original again and retry." else "文件内容损坏或结构不完整，请重新下载原文件后再试。"
        if(!english) return value ?: "无法读取所选文件，请重新选择"
        val source=value.orEmpty()
        return when {
            source.contains("Android 15") -> "PDF text reflow requires Android 15 or later. Try EPUB or TXT."
            source.contains("OCR") -> "Offline OCR could not finish. The original and completed pages are retained for retry."
            source.contains("受保护") -> "This document's text is protected."
            source.contains("暂不支持") -> "Unsupported format. Choose TXT, EPUB, CBZ/image ZIP comics, MD, HTML, DOCX or PDF."
            source.contains("CBZ") || source.contains("漫画") || source.contains("图片 ZIP") -> "The comic archive is damaged, contains no supported images, or exceeds the image limits."
            source.contains("编码") || source.contains("有效文本") -> "Text encoding not recognized. Try saving as UTF-8."
            source.contains("MB") -> "The file is empty or exceeds the size limit."
            source.contains("200 万") -> "Text exceeds two million characters. Split the document first."
            source.contains("100 个") -> "Select at most 100 files per import."
            source.contains("文件夹内没有") -> "No supported reading files in this folder."
            source.contains("层级") -> "The folder is too deep or contains too many entries."
            source.contains("章节") || source.contains("文档") || source.contains("ZIP",true) -> "The document structure is incomplete or unsupported."
            else -> "Could not read this file. Check its format and access permissions."
        }
    }
}
