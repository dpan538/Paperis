package com.inkreader.nativeink

/** Keep file errors useful when the UI is English, without exposing internal stack traces. */
internal object ReaderErrors {
    fun message(value: String?, english: Boolean): String {
        if(!english) return value ?: "无法读取所选文件，请重新选择"
        val source=value.orEmpty()
        return when {
            source.contains("Android 15") -> "PDF text reflow requires Android 15 or later. Try EPUB or TXT."
            source.contains("扫描") || source.contains("文本层") -> "No readable text layer. Scanned or protected files cannot be reflowed."
            source.contains("受保护") -> "This document's text is protected."
            source.contains("暂不支持") -> "Unsupported format. Choose TXT, EPUB, MD, HTML, DOCX or text PDF."
            source.contains("编码") || source.contains("有效文本") -> "Text encoding not recognized. Try saving as UTF-8."
            source.contains("MB") -> "The file is empty or exceeds the size limit."
            source.contains("100 万") -> "Text exceeds one million characters. Split the document first."
            source.contains("100 个") -> "Select at most 100 files per import."
            source.contains("文件夹内没有") -> "No supported reading files in this folder."
            source.contains("层级") -> "The folder is too deep or contains too many entries."
            source.contains("章节") || source.contains("文档") || source.contains("ZIP",true) -> "The document structure is incomplete or unsupported."
            else -> "Could not read this file. Check its format and access permissions."
        }
    }
}
