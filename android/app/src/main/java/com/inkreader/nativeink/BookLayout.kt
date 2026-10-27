package com.inkreader.nativeink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.graphics.text.LineBreakConfig
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.ReplacementSpan
import android.graphics.Paint
import java.util.Locale

/** Paginate the native text layout without flattening the book into a picture or dropping characters. */
internal class BookLayout(val text: String, val viewportWidth: Float, val viewportHeight: Float, val font: Float,
    val spacing: Float = .67f, val columnMode: Int = 0, val face: Typeface = Typeface.create("serif", Typeface.NORMAL),
    private val protectTails:Boolean=true,val structure:BookStructure=BookStructure.infer(text),private val imageProvider:(String)->android.graphics.Bitmap?={null},private val imageSize:(String)->Pair<Int,Int>?={null}) {
    data class Column(val firstLine: Int, val lastLine: Int, val start: Int, val end: Int)
    val margin = (viewportWidth*.054f).coerceIn(24f,if(viewportWidth>viewportHeight) 92f else 80f)
    // A narrow portrait page always stays single-column; the landscape preference is retained.
    val gutter = (viewportWidth*.052f).coerceIn(24f,88f)
    val columns = if (viewportWidth > viewportHeight && columnMode != 1 &&
        viewportWidth-margin*2-gutter >= maxOf(640f,font*20f)) 2 else 1
    val columnWidth = ((viewportWidth - margin * 2 - gutter * (columns - 1)) / columns).toInt()
    // Leave an optical half-em for platform CJK hanging punctuation and glyph overhang.
    private val textWidth=(columnWidth-kotlin.math.ceil(font*.5).toInt()).coerceAtLeast(1)
    val bodyTop = (viewportHeight*.132f).coerceIn(84f,150f)
    val bodyBottom = viewportHeight - (viewportHeight*.1004f).coerceIn(64f,114f)
    val headerY = (bodyTop*(73f/150f)).coerceAtLeast(56f)
    val footerY = viewportHeight-(viewportHeight-bodyBottom)*(62f/114f)
    val bodyHeight = bodyBottom - bodyTop
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = font; color = Color.BLACK; typeface = face; textLocale = Locale.SIMPLIFIED_CHINESE
    }
    private val styled=SpannableString(text).apply {
        structure.blocks.forEach {block->
            if(block.start !in text.indices||block.end>text.length)return@forEach
            when(block.kind) {
                "heading"->{
                    setSpan(RelativeSizeSpan(when(block.level){1->1.42f;2->1.20f;else->1.07f}),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if(block.level==1)setSpan(android.text.style.AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "subtitle"->{setSpan(RelativeSizeSpan(1.16f),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);setSpan(android.text.style.AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
                "image"->imageSize(block.image)?.let {(w,h)->setSpan(Illustration(w,h,{imageProvider(block.image)},textWidth,(bodyHeight-font*spacing-12).toInt()),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
                "quote","verse"->setSpan(LeadingMarginSpan.Standard((font*.8f).toInt()),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                else->{
                    val first=text.substring(block.start,minOf(block.end,block.start+16))
                    val indent=if(first.any {it.code in 0x3400..0x9fff})(font*2).toInt() else 0
                    setSpan(LeadingMarginSpan.Standard(indent,0),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        Regex("\\n\\n").findAll(text).forEach {gap->setSpan(ParagraphGap((font*.62f).toInt()),gap.range.last,gap.range.last+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
    }
    private fun build(): StaticLayout = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, textWidth)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setLineSpacing(font * spacing, 1f)
        .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
        .setLineBreakConfig(LineBreakConfig.Builder().setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_STRICT).build())
        .setJustificationMode(LineBreaker.JUSTIFICATION_MODE_INTER_WORD)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .build()
    val layout: StaticLayout
    val pages: List<List<Column>>

    init {
        val initial = build()
        if (protectTails) {
            var start = 0
            text.split('\n').forEach { paragraph ->
                val end = start + paragraph.length
                if (paragraph.length >= 8) {
                    val line = initial.getLineForOffset((end - 1).coerceAtLeast(start))
                    val lineStart = initial.getLineStart(line).coerceAtLeast(start)
                    val tail = text.substring(lineStart, end)
                    if (lineStart > start && tail.count { it.isLetterOrDigit() } == 1 && tail.any { it.code in 0x3400..0x9fff }) {
                        var at = end; var letters = 0
                        while (at > start && letters < 3) { at = text.offsetByCodePoints(at, -1); if (text[at].isLetterOrDigit()) letters++ }
                        if (letters == 3 && paint.measureText(text, at, end) < columnWidth * .7f)
                            styled.setSpan(ParagraphTail(), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                start = end + 1
            }
        }
        layout = if (protectTails) build() else initial
        val slices = mutableListOf<Column>()
        val headings=structure.blocks.filter {it.kind=="heading"}.map {it to layout.getLineForOffset(it.start)}
        var first = 0
        while (first < layout.lineCount) {
            var last = first
            val top = layout.getLineTop(first)
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= bodyHeight) last++
            headings.firstOrNull {(block,line)->block.level<=1&&line>first&&line<=last}?.let {last=it.second-1}
            headings.filter {it.second in first..last}.forEach {(block,line)->
                var after=layout.getLineForOffset((block.end-1).coerceAtLeast(block.start))+1
                while(after<layout.lineCount&&text.substring(layout.getLineStart(after),layout.getLineEnd(after)).isBlank())after++
                if(line>first&&after+1>last)last=line-1
            }
            if (last > first + 1 && last + 1 < layout.lineCount) {
                val next = layout.getLineStart(last + 1)
                val paragraphStart = text.lastIndexOf('\n', (next - 1).coerceAtLeast(0)) + 1
                val paragraphEnd = text.indexOf('\n', next).let { if (it < 0) text.length else it }
                val current = layout.getLineStart(last)
                if (current >= paragraphStart && (current == paragraphStart || layout.getLineEnd(last + 1) >= paragraphEnd)) last--
            }
            slices += Column(first, last, layout.getLineStart(first), layout.getLineEnd(last))
            first = last + 1
        }
        val paged=mutableListOf<List<Column>>();val current=mutableListOf<Column>()
        val majorStarts=headings.filter {it.first.level<=1}.map {it.second}.toSet()
        for(slice in slices) {
            if(current.isNotEmpty()&&slice.firstLine in majorStarts){paged+=current.toList();current.clear()}
            current+=slice
            if(current.size==columns){paged+=current.toList();current.clear()}
        }
        if(current.isNotEmpty())paged+=current.toList()
        pages=paged
    }

    private class ParagraphGap(private val height:Int):android.text.style.LineHeightSpan {
        override fun chooseHeight(text:CharSequence,start:Int,end:Int,spanstartv:Int,v:Int,fm:Paint.FontMetricsInt) {fm.ascent=-height;fm.descent=0;fm.top=fm.ascent;fm.bottom=0}
    }
    private class Illustration(sourceWidth:Int,sourceHeight:Int,private val bitmap:()->android.graphics.Bitmap?,private val width:Int,maxHeight:Int):ReplacementSpan() {
        private val factor=minOf(width.toFloat()/sourceWidth,maxHeight.toFloat()/sourceHeight,1f)
        private val w=(sourceWidth*factor).toInt().coerceAtLeast(1);private val h=(sourceHeight*factor).toInt().coerceAtLeast(1)
        private val left=(width-w)/2f
        override fun getSize(paint:Paint,text:CharSequence,start:Int,end:Int,fm:Paint.FontMetricsInt?):Int {
            fm?.apply {ascent=-h;top=ascent;descent=0;bottom=0};return width
        }
        override fun draw(canvas:Canvas,text:CharSequence,start:Int,end:Int,x:Float,top:Int,y:Int,bottom:Int,paint:Paint) {
            bitmap()?.let {canvas.drawBitmap(it,null,android.graphics.RectF(x+left,y-h.toFloat(),x+left+w,y.toFloat()),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))}
        }
    }

    /** Keep a short CJK tail together while drawing the exact original characters. */
    private class ParagraphTail : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if(fm != null) paint.getFontMetricsInt(fm)
            return kotlin.math.ceil(paint.measureText(text, start, end).toDouble()).toInt()
        }
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            canvas.drawText(text, start, end, x, y.toFloat(), paint)
        }
    }

    fun pageForOffset(offset: Int) = pages.indexOfFirst { offset < it.last().end }.let { if (it < 0) pages.lastIndex else it }
    fun offsetForPage(page: Int) = pages[page.coerceIn(pages.indices)].first().start
    fun pageText(page: Int): String {
        val spans = pages[page.coerceIn(pages.indices)]
        return text.substring(spans.first().start, spans.last().end)
    }

    fun draw(canvas: Canvas, page: Int) {
        pages[page.coerceIn(pages.indices)].forEachIndexed { i, slice ->
            canvas.save()
            canvas.translate(margin + i * (columnWidth + gutter), bodyTop)
            val usedHeight = layout.getLineBottom(slice.lastLine) - layout.getLineTop(slice.firstLine)
            canvas.clipRect(0f, 0f, columnWidth.toFloat(), usedHeight.toFloat())
            canvas.translate(0f, -layout.getLineTop(slice.firstLine).toFloat())
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
