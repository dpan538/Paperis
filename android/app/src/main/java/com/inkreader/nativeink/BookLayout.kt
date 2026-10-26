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
    private val protectTails: Boolean = true) {
    data class Column(val firstLine: Int, val lastLine: Int, val start: Int, val end: Int)
    val margin = if (viewportWidth > viewportHeight) 92f else 80f
    // A narrow portrait page always stays single-column; the landscape preference is retained.
    val columns = if (viewportWidth > viewportHeight && columnMode != 1) 2 else 1
    val gutter = 88f
    val columnWidth = ((viewportWidth - margin * 2 - gutter * (columns - 1)) / columns).toInt()
    val bodyTop = 150f
    val bodyBottom = viewportHeight - 114f
    val bodyHeight = bodyBottom - bodyTop
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = font; color = Color.BLACK; typeface = face; textLocale = Locale.SIMPLIFIED_CHINESE
    }
    private val styled = SpannableString(text).apply {
        val headingEnd = text.indexOf('\n').takeIf { it >= 0 } ?: text.length
        setSpan(RelativeSizeSpan(1.45f), 0, headingEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(StyleSpan(Typeface.BOLD), 0, headingEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        var position = headingEnd + 1
        while (position < text.length) {
            val end = text.indexOf('\n', position).takeIf { it >= 0 } ?: text.length
            if (end > position) setSpan(LeadingMarginSpan.Standard((font * 2).toInt(), 0), position, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            position = end + 1
        }
    }
    private fun build(): StaticLayout = StaticLayout.Builder.obtain(styled, 0, styled.length, paint, columnWidth)
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
        var first = 0
        while (first < layout.lineCount) {
            var last = first
            val top = layout.getLineTop(first)
            while (last + 1 < layout.lineCount && layout.getLineBottom(last + 1) - top <= bodyHeight) last++
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
        pages = slices.chunked(columns)
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
