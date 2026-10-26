package com.inkreader.nativeink

import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

internal data class ParsedBook(val title: String, val text: String, val format: String)

/** Reads local data only. No HTML execution, external XML entities or archive extraction. */
internal object BookImporter {
    const val MAX_BYTES = 32 * 1024 * 1024
    const val MAX_TEXT = 1_000_000
    val extensions = setOf("txt", "epub", "md", "markdown", "html", "htm", "docx", "pdf")

    fun parse(file: File, name: String): ParsedBook {
        require(file.length() in 1..MAX_BYTES.toLong()) { "文件为空或超过 32 MB" }
        val extension = name.substringAfterLast('.', "").lowercase()
        require(extension in extensions) { "暂不支持 .$extension；请选择 TXT、EPUB、MD、HTML、DOCX 或文本 PDF" }
        var title = name.substringBeforeLast('.').ifBlank { "未命名" }
        val body = when (extension) {
            "epub" -> epub(file).let { (t, text) -> if (t.isNotBlank()) title = t; text }
            "docx" -> docx(file)
            "html", "htm" -> html(decode(file.readBytes()))
            "md", "markdown" -> markdown(decode(file.readBytes()))
            "pdf" -> pdf(file)
            else -> decode(file.readBytes())
        }
        val normalized = normalize(body)
        require(normalized.any { it.isLetterOrDigit() }) { "未找到可重排正文；扫描图片或受保护文件暂不能重排" }
        require(normalized.length <= MAX_TEXT) { "正文超过 100 万字，请拆分后导入" }
        title = title.replace(Regex("\\s+"), " ").take(100)
        val text = if (normalized.lineSequence().first().trim() == title) normalized else "$title\n\n$normalized"
        return ParsedBook(title, text, extension.uppercase())
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
        val utf8 = runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
        val value = utf8 ?: runCatching { charset("GB18030").newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }.getOrElse { error("文字编码无法识别，请另存为 UTF-8") }
        require(value.count { it == '\u0000' || it == '\uFFFD' } < maxOf(2, value.length / 200)) { "文件不像有效文本，请检查编码或格式" }
        return value.removePrefix("\uFEFF")
    }

    fun normalize(value: String): String = value.replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ')
        .replace(Regex("[\\t ]+\\n"), "\n").replace(Regex("\\n[\\t 　]+"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n").trim()

    private fun html(value: String): String {
        val clean = value.replace(Regex("<(script|style|noscript)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        return Html.fromHtml(clean, Html.FROM_HTML_MODE_LEGACY).toString()
    }
    private fun markdown(value: String) = value
        .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s+"), "")
        .replace("**", "").replace("__", "").replace("```", "")

    private fun xml(value: String, visit: (XmlPullParser, Int) -> Unit) {
        require(!value.contains("<!DOCTYPE", true) && !value.contains("<!ENTITY", true)) { "文档包含不支持的 XML 声明" }
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(value.reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) { visit(parser, event); event = parser.next() }
    }
    private class Archive(file: File) : java.io.Closeable {
        val zip = ZipFile(file)
        private var expanded = 0
        init { require(zip.size() <= 4000) { "压缩文档条目过多" } }
        fun read(path: String): String {
            val entry = zip.getEntry(path) ?: error("文档缺少 $path")
            val bytes = zip.getInputStream(entry).use { it.readNBytes(MAX_BYTES + 1) }
            expanded += bytes.size
            require(expanded <= MAX_BYTES) { "解压后正文过大" }
            return decode(bytes)
        }
        fun has(path: String) = zip.getEntry(path) != null
        override fun close() = zip.close()
    }
    private fun resolve(base: String, href: String): String {
        val path = android.net.Uri.decode(href.substringBefore('#'))
        require(!path.contains(':') && !path.startsWith('/')) { "不支持外部章节地址" }
        val parts = mutableListOf<String>()
        (base.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.plus("/").orEmpty() + path).split('/').forEach {
            when (it) { "", "." -> Unit; ".." -> { require(parts.isNotEmpty()) { "章节地址越界" }; parts.removeAt(parts.lastIndex) }; else -> parts += it }
        }
        return parts.joinToString("/")
    }
    private fun epub(file: File): Pair<String, String> = Archive(file).use { a ->
        val encrypted = mutableSetOf<String>()
        if(a.has("META-INF/encryption.xml")) xml(a.read("META-INF/encryption.xml")) { p,e ->
            if(e==XmlPullParser.START_TAG && p.name=="CipherReference") encrypted += p.getAttributeValue(null,"URI").orEmpty()
        }
        var opf = ""
        xml(a.read("META-INF/container.xml")) { p, e -> if (e == XmlPullParser.START_TAG && p.name == "rootfile" && opf.isEmpty()) opf = p.getAttributeValue(null, "full-path").orEmpty() }
        require(opf.isNotBlank() && !opf.startsWith('/') && ".." !in opf.split('/')) { "EPUB 的书籍索引无效" }
        val manifest = mutableMapOf<String, String>(); val spine = mutableListOf<String>()
        var title = ""; var readingTitle = false
        xml(a.read(opf)) { p, e ->
            if (e == XmlPullParser.START_TAG) when (p.name) {
                "title" -> readingTitle = true
                "item" -> manifest[p.getAttributeValue(null, "id").orEmpty()] = p.getAttributeValue(null, "href").orEmpty()
                "itemref" -> if (p.getAttributeValue(null, "linear") != "no") spine += p.getAttributeValue(null, "idref").orEmpty()
            }
            if (e == XmlPullParser.TEXT && readingTitle) title += p.text
            if (e == XmlPullParser.END_TAG && p.name == "title") readingTitle = false
        }
        require(spine.isNotEmpty()) { "EPUB 没有可读章节" }
        val text = StringBuilder()
        spine.forEach { id ->
            val href = manifest[id] ?: error("EPUB 的章节引用缺失")
            val path=resolve(opf,href)
            require(path !in encrypted) { "此 EPUB 的正文章节受保护，暂不能读取" }
            text.append(html(a.read(path))).append("\n\n")
            require(text.length <= MAX_TEXT) { "正文超过 100 万字" }
        }
        title.trim() to text.toString()
    }
    private fun docx(file: File): String = Archive(file).use { a ->
        val text = StringBuilder(); var inText = false
        xml(a.read("word/document.xml")) { p, e ->
            if (e == XmlPullParser.START_TAG) when (p.name) { "t" -> inText = true; "tab" -> text.append(' '); "br" -> text.append('\n') }
            if (e == XmlPullParser.TEXT && inText) text.append(p.text)
            if (e == XmlPullParser.END_TAG) when (p.name) { "t" -> inText = false; "p" -> text.append("\n\n"); "tc" -> text.append(' ') }
        }
        text.toString()
    }
    private fun pdf(file: File): String {
        if (Build.VERSION.SDK_INT < 35) throw IllegalArgumentException("PDF 文本重排需要 Android 15 及以上；可先转为 EPUB/TXT")
        val output = StringBuilder()
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { document ->
            require(document.pageCount <= 1500) { "PDF 页数过多，请拆分后导入" }
            for (i in 0 until document.pageCount) document.openPage(i).use { page ->
                page.textContents.forEach { output.append(it.text).append('\n') }
                output.append("\n\n")
                require(output.length <= MAX_TEXT) { "PDF 正文过长，请拆分后导入" }
            }
        }
        require(output.any { it.isLetterOrDigit() }) { "此 PDF 没有文本层，扫描图片暂不能重排" }
        return output.toString()
    }
}
