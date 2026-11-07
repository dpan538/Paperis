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
    val sizing = ReaderSizing(viewportWidth, viewportHeight)
    val chromeMargin = (viewportWidth*.054f).coerceIn(24f,if(viewportWidth>viewportHeight) 92f else 80f)
    // A narrow portrait page always stays single-column; the landscape preference is retained.
    val gutter = (viewportWidth*.052f).coerceIn(24f,88f)
    val columns = if (!structure.comic && columnMode != 1 &&
        sizing.canUseTwoColumns(font, viewportWidth-chromeMargin*2, gutter)) 2 else 1
    private val spreadWidth = minOf(viewportWidth-chromeMargin*2,
        if (structure.comic) Float.MAX_VALUE else if (columns==2) 1760f else maxOf(560f,font*32f))
    val margin = (viewportWidth-spreadWidth)/2
    val columnWidth = ((spreadWidth - gutter * (columns - 1)) / columns).toInt().coerceAtLeast(1)
    // Leave an optical half-em for platform CJK hanging punctuation and glyph overhang.
    private val textWidth=(columnWidth-kotlin.math.ceil(font*.5).toInt()).coerceAtLeast(1)
    val bodyTop = sizing.bodyTop
    val bodyBottom = viewportHeight-sizing.bottomSpace
    val headerY = sizing.headerY
    val footerY = sizing.footerY
    val bodyHeight = bodyBottom - bodyTop
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = font; color = Color.BLACK; typeface = face; textLocale = Locale.SIMPLIFIED_CHINESE
    }
    // Punctuation compression is measured as replacement spans before the first layout build.
    private val imageRanges=structure.blocks.filter {(it.kind=="image"||it.kind=="plate")&&it.start>=0&&it.end<=text.length&&it.start<it.end}.map {it.start until it.end}
    private val plateSpanWidth=if(columns>1) (textWidth*columns+gutter*(columns-1)).toInt() else textWidth
    private var plateLines:Set<Int> = emptySet()
    // Paragraph tails are replacement spans too, so runs that could fall inside the protected
    // three-character suffix are left uncompressed instead of letting two spans overlap.
    private val tailSuffixes=run {
        val suffix=mutableListOf<IntRange>();var start=0
        while(start<=text.length) {
            val newline=text.indexOf('\n',start);val end=if(newline<0)text.length else newline
            if(end-start>=8) {
                var at=end;var letters=0
                while(at>start&&letters<3) {at=text.offsetByCodePoints(at,-1);if(text[at].isLetterOrDigit())letters++}
                if(letters==3)suffix+=at until end
            }
            if(newline<0)break
            start=newline+1
        }
        suffix
    }
    private val punctuation=run {
        val result=mutableListOf<CjkTypography.CompressionRun>();var suffixIndex=0
        for(run in CjkTypography.compressionRuns(text,0,text.length,CjkTypography.MAX_COMPRESSED_RUNS*2)) {
            if(result.size>=CjkTypography.MAX_COMPRESSED_RUNS)break
            if(imageRanges.any {range->run.start<=range.last&&range.first<=run.end-1})continue
            while(suffixIndex<tailSuffixes.size&&tailSuffixes[suffixIndex].last<run.start)suffixIndex++
            if(suffixIndex<tailSuffixes.size&&tailSuffixes[suffixIndex].first<=run.end-1)continue
            result+=run
        }
        result
    }
    /** Runs of 2+ full-width marks whose advance is compressed to at most 1em. */
    val compressedPunctuationRuns:Int get()=punctuation.size
    private val styled=SpannableString(text).apply {
        structure.blocks.forEach {block->
            if(block.start !in text.indices||block.end>text.length)return@forEach
            when(block.kind) {
                "heading"->{
                    setSpan(RelativeSizeSpan(when(block.level){1->if(sizing.phone)1.30f else 1.42f;2->1.20f;else->1.07f}),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if(block.level==1)setSpan(android.text.style.AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "subtitle"->{setSpan(RelativeSizeSpan(1.16f),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);setSpan(android.text.style.AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
                "image"->imageSize(block.image)?.let {(w,h)->setSpan(Illustration(w,h,{imageProvider(block.image)},textWidth,(bodyHeight-font*spacing*(if(structure.comic)3 else 1)-12).toInt(),structure.comic),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
                "plate"->imageSize(block.image)?.let {(w,h)->setSpan(Illustration(w,h,{imageProvider(block.image)},plateSpanWidth,(bodyHeight-font*spacing-12).toInt(),false),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
                "metadata","caption"->setSpan(RelativeSizeSpan(.88f),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                "quote","verse"->setSpan(LeadingMarginSpan.Standard((font*.8f).toInt()),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                else->{
                    val first=text.substring(block.start,minOf(block.end,block.start+16))
                    val indent=if(first.any {it.code in 0x3400..0x9fff})(font*2).toInt() else 0
                    setSpan(LeadingMarginSpan.Standard(indent,0),block.start,block.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        Regex("\\n\\n").findAll(text).forEach {gap->setSpan(ParagraphGap((font*.62f).toInt()),gap.range.last,gap.range.last+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
        // Consecutive full-width marks keep every character but share a bounded advance.
        punctuation.forEach {run->setSpan(CompressedPunctuation(run.markStart-run.start,run.marks,run.end-run.markEnd),run.start,run.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
    }
    private fun build(justify:Boolean=false): StaticLayout {
        layoutBuilds++
        return StaticLayout.Builder.obtain(styled, 0, styled.length, paint, textWidth)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setLineSpacing(font * spacing, 1f)
        .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
        .setLineBreakConfig(LineBreakConfig.Builder().setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_STRICT).build())
        .setJustificationMode(if(justify)LineBreaker.JUSTIFICATION_MODE_INTER_WORD else LineBreaker.JUSTIFICATION_MODE_NONE)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .build()
    }
    val layout: StaticLayout
    val spacingDecisions:List<ParagraphSpacing.Decision>
    private val wordSpacing:FloatArray
    private val cjkSpacing:FloatArray
    /** Body lines that receive bounded CJK letter spacing. */
    val cjkJustifiedLines:Int
    val pages: List<List<Column>>
    val paginationRepairs:List<String>

    init {
        val initial = build()
        var tailsAdded=false
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
                        if (letters == 3 && paint.measureText(text, at, end) < columnWidth * .7f) {
                            styled.setSpan(ParagraphTail(), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            tailsAdded=true
                        }
                    }
                }
                start = end + 1
            }
        }
        // Rebuild only when paragraph tails actually changed the measured text; otherwise the
        // initial layout is already final (one StaticLayout build instead of two).
        layout = if (protectTails&&tailsAdded) build() else initial
        val plateLineRanges=structure.blocks.filter {it.kind=="plate"&&it.start in text.indices&&it.end<=text.length}.map {block->
            val first=layout.getLineForOffset(block.start)
            val last=layout.getLineForOffset((block.end-1).coerceAtLeast(block.start))
            first..last
        }
        plateLines=plateLineRanges.flatMap {it.toList()}.toSet()
        val spacingPolicy=ParagraphSpacing.decide(text,structure,layout,font)
        spacingDecisions=spacingPolicy.decisions;wordSpacing=spacingPolicy.added
        // Per-line CJK letter spacing from leftover slack; word-justified lines never receive it.
        val lineSpans=ArrayList<CjkTypography.LineSpan>(layout.lineCount)
        var blockIndex=0
        for (line in 0 until layout.lineCount) {
            val start=layout.getLineStart(line)
            while(blockIndex<structure.blocks.size&&structure.blocks[blockIndex].end<=start)blockIndex++
            val block=structure.blocks.getOrNull(blockIndex)
            val body=block!=null&&start>=block.start&&start<block.end&&block.kind=="body"
            val end=layout.getLineEnd(line)
            val visibleEnd=layout.getLineVisibleEnd(line).coerceIn(start,end)
            val endsParagraph=end>=text.length||(end>0&&text[end-1]=='\n')||(end<text.length&&text[end]=='\n')
            lineSpans+=CjkTypography.LineSpan(start,visibleEnd,layout.getLineMax(line),endsParagraph,body,wordSpacing[line]>0f)
        }
        cjkSpacing=CjkTypography.letterSpacing(text,lineSpans,textWidth.toFloat(),font)
        cjkJustifiedLines=cjkSpacing.count {it>0f}
        val generated:List<List<Column>>
        if(structure.comic&&structure.blocks.any {it.kind=="image"}) {
            val imageLines=structure.blocks.filter {it.kind=="image"}.map {layout.getLineForOffset(it.start)}.distinct().sorted()
            generated=imageLines.map {line->
                listOf(Column(line,line,layout.getLineStart(line),layout.getLineEnd(line)))
            }
        } else {
        val slices = mutableListOf<Column>()
        val headings=structure.blocks.filter {it.kind=="heading"}.map {it to layout.getLineForOffset(it.start)}
        var first = 0
        while (first < layout.lineCount) {
            var last = first
            val top = layout.getLineTop(first)
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= bodyHeight) last++
            headings.firstOrNull {(block,line)->block.level<=1&&line>first&&line<=last&&text.substring(layout.getLineStart(first),block.start).isNotBlank()}?.let {last=it.second-1}
            for((block,line) in headings) {
                if(line !in first..last)continue
                val body=structure.blocks.firstOrNull {it.start>=block.end&&it.kind !in setOf("heading","subtitle","caption")}
                val after=body?.let {layout.getLineForOffset(it.start)}?:layout.getLineForOffset((block.end-1).coerceAtLeast(block.start))
                if(line>first&&after+1>last&&layout.getLineBottom(minOf(after+1,layout.lineCount-1))-layout.getLineTop(line)<=bodyHeight){last=line-1;break}
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
        // A plate owns its page: split any slice that crosses it and never let a second column
        // share the plate page. This keeps a cover/title/art page as ONE original page.
        val splitSlices=mutableListOf<Column>()
        for(slice in slices) {
            val ranges=plateLineRanges.filter {it.first in slice.firstLine..slice.lastLine||it.last in slice.firstLine..slice.lastLine}
            if(ranges.isEmpty()) {splitSlices+=slice;continue}
            var at=slice.firstLine
            for(range in ranges.sortedBy {it.first}) {
                if(range.first>at) splitSlices+=Column(at,range.first-1,layout.getLineStart(at),layout.getLineEnd(range.first-1))
                splitSlices+=Column(range.first,range.last,layout.getLineStart(range.first),layout.getLineEnd(range.last))
                at=range.last+1
            }
            if(at<=slice.lastLine) splitSlices+=Column(at,slice.lastLine,layout.getLineStart(at),layout.getLineEnd(slice.lastLine))
        }
        val paged=mutableListOf<List<Column>>();val current=mutableListOf<Column>()
        for(slice in splitSlices) {
            val plate=plateLines.any {it in slice.firstLine..slice.lastLine}
            if(plate) {
                if(current.isNotEmpty()){paged+=current.toList();current.clear()}
                paged+=listOf(slice)
                continue
            }
            val startsMajor=headings.any {(block,line)->block.level<=1&&line in slice.firstLine..slice.lastLine&&text.substring(slice.start,block.start).isBlank()}
            if(current.isNotEmpty()&&startsMajor){paged+=current.toList();current.clear()}
            current+=slice
            if(current.size==columns){paged+=current.toList();current.clear()}
        }
        if(current.isNotEmpty())paged+=current.toList()
        generated=paged
        }
        val checked=PaginationGuard.apply(text,layout,generated,columns,bodyHeight,structure.comic)
        pages=checked.pages;paginationRepairs=checked.repairs
    }

    private class ParagraphGap(private val height:Int):android.text.style.LineHeightSpan {
        override fun chooseHeight(text:CharSequence,start:Int,end:Int,spanstartv:Int,v:Int,fm:Paint.FontMetricsInt) {fm.ascent=-height;fm.descent=0;fm.top=fm.ascent;fm.bottom=0}
    }
    private class Illustration(sourceWidth:Int,sourceHeight:Int,private val bitmap:()->android.graphics.Bitmap?,private val width:Int,maxHeight:Int,upscale:Boolean):ReplacementSpan() {
        private val factor=minOf(width.toFloat()/sourceWidth,maxHeight.toFloat()/sourceHeight,if(upscale)3f else 1f)
        private val w=(sourceWidth*factor).toInt().coerceAtLeast(1);private val h=(sourceHeight*factor).toInt().coerceAtLeast(1)
        private val left=(width-w)/2f
        override fun getSize(paint:Paint,text:CharSequence,start:Int,end:Int,fm:Paint.FontMetricsInt?):Int {
            fm?.apply {ascent=-h;top=ascent;descent=0;bottom=0};return width
        }
        override fun draw(canvas:Canvas,text:CharSequence,start:Int,end:Int,x:Float,top:Int,y:Int,bottom:Int,paint:Paint) {
            if(canvas is TextDrawProbe.CaptureCanvas)return
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

    internal fun drawNativeText(canvas:Canvas,first:Int=0,last:Int=layout.lineCount-1) {
        val nativePaint=layout.paint;val originalWord=nativePaint.wordSpacing;val originalLetter=nativePaint.letterSpacing
        try {
            for(line in first..last) {
                canvas.save();canvas.clipRect(0f,layout.getLineTop(line).toFloat(),columnWidth.toFloat(),layout.getLineBottom(line).toFloat())
                (canvas as? TextDrawProbe.CaptureCanvas)?.limit(line)
                nativePaint.wordSpacing=wordSpacing[line]
                nativePaint.letterSpacing=cjkSpacing[line]
                layout.draw(canvas);canvas.restore()
            }
        } finally {nativePaint.wordSpacing=originalWord;nativePaint.letterSpacing=originalLetter;(canvas as? TextDrawProbe.CaptureCanvas)?.resetLimit()}
    }

    fun draw(canvas: Canvas, page: Int) {
        pages[page.coerceIn(pages.indices)].forEachIndexed { i, slice ->
            canvas.save()
            val plateSlice=plateLines.any {it in slice.firstLine..slice.lastLine}
            if(plateSlice) {
                canvas.translate(margin, bodyTop)
                canvas.clipRect(0f, 0f, plateSpanWidth.toFloat(), bodyHeight)
                canvas.translate(0f, -layout.getLineTop(slice.firstLine).toFloat())
                layout.draw(canvas)
                canvas.restore();return@forEachIndexed
            }
            val firstBlock=structure.blocks.firstOrNull {it.start<=slice.start&&it.end>slice.start}
            val opening=firstBlock?.kind=="heading"&&firstBlock.level<=1
            val usedHeight0=layout.getLineBottom(slice.lastLine)-layout.getLineTop(slice.firstLine)
            val openingPad=if(opening&&usedHeight0+font*1.6f<=bodyHeight) font*1.6f else 0f
            canvas.translate(margin + i * (columnWidth + gutter), bodyTop+openingPad)
            if(structure.comic) {
                val imageLine=structure.blocks.firstOrNull {it.kind=="image"&&layout.getLineForOffset(it.start) in slice.firstLine..slice.lastLine}?.let {layout.getLineForOffset(it.start)}
                if(imageLine!=null) {
                    val top=layout.getLineTop(imageLine);val height=(layout.getLineBottom(imageLine)-top).coerceAtLeast(1)
                    val y=((bodyHeight-height)/2f).coerceAtLeast(0f)
                    canvas.clipRect(0f,y,columnWidth.toFloat(),y+height)
                    canvas.translate(0f,y-top.toFloat());layout.draw(canvas);canvas.restore();return@forEachIndexed
                }
            }
            val usedHeight = layout.getLineBottom(slice.lastLine) - layout.getLineTop(slice.firstLine)
            canvas.clipRect(0f, 0f, columnWidth.toFloat(), usedHeight.toFloat())
            canvas.translate(0f, -layout.getLineTop(slice.firstLine).toFloat())
            drawNativeText(canvas,slice.firstLine,slice.lastLine)
            canvas.restore()
        }
    }

    internal companion object {
        /**
         * Instrumentation counter for construction cost in StaticLayout builds. A layout whose
         * paragraphs need no ParagraphTail span now costs 1 build (previously 2); protecting at
         * least one tail still costs 2. Reset before a case and read after constructing BookLayout.
         */
        @Volatile internal var layoutBuilds=0
        internal fun resetLayoutBuilds(){layoutBuilds=0}
    }
}
