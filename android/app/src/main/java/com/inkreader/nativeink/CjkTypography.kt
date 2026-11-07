package com.inkreader.nativeink

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan

/**
 * Bounded CJK typography. Every rule here is a visual adjustment only: no character, source
 * offset or paragraph boundary is ever changed. The rule bodies are pure Kotlin (no android.*
 * types) so they run unchanged in JVM unit tests; the replacement span at the bottom of the file
 * is the only drawing glue.
 */
internal object CjkTypography {
    /** Hard cap for extra inter-character spacing on one justified CJK body line, in em. */
    const val MAX_LETTER_SPACING_EM = 0.08f
    /** A run of two or more full-width marks may occupy at most this much advance, in em. */
    const val MAX_PUNCTUATION_RUN_EM = 1f
    /** Nominal advance allowed per mark inside a run before the run-wide cap applies, in em. */
    const val PUNCTUATION_MARK_EM = 0.5f
    /** A line needs at least this many visible code points before letter spacing is worth it. */
    const val MIN_JUSTIFIED_CODE_POINTS = 2
    /** Safety bound on spans added to one book; normal books stay far below it. */
    const val MAX_COMPRESSED_RUNS = 20_000

    /** One detected run of consecutive full-width marks, in UTF-16 source offsets. */
    data class PunctuationRun(val start: Int, val end: Int, val marks: Int) {
        val advanceEm: Float get() = punctuationAdvanceEm(marks)
    }

    /**
     * A mark run expanded by one safe neighbour where a line break must not be exposed.
     * [start] is the leading code point of the replacement span, [markStart]..[markEnd] are the
     * marks, and [end] is the span end. The leading and/or trailing neighbour is drawn at its
     * natural advance; only the marks share the compressed advance.
     */
    data class CompressionRun(val start: Int, val markStart: Int, val markEnd: Int, val end: Int,
        val marks: Int) {
        val leadChars: Int get() = markStart - start
        val trailChars: Int get() = end - markEnd
        val advanceEm: Float get() = punctuationAdvanceEm(marks)
    }

    /** Per-line facts needed by [letterSpacing]; offsets are UTF-16 source indices. */
    data class LineSpan(val start: Int, val visibleEnd: Int, val maxWidth: Float,
        val endsParagraph: Boolean, val body: Boolean, val wordJustified: Boolean)

    /** Advance of a compressed run: 0.5em per mark, capped at 1em in total. */
    fun punctuationAdvanceEm(marks: Int): Float = if (marks < 2) 0f else minOf(MAX_PUNCTUATION_RUN_EM,
        PUNCTUATION_MARK_EM * marks)

    /** Integer advance for a compressed run; ceil avoids rounding a mark below its slot. */
    fun punctuationAdvancePx(marks: Int, font: Float): Int =
        kotlin.math.ceil((punctuationAdvanceEm(marks) * font).toDouble()).toInt()

    /**
     * Final advance for the marks: the compressed budget, never wider than the marks' natural
     * advance (a narrow quote pair is left as-is rather than stretched to the 1em cap).
     */
    fun compressedAdvancePx(marks: Int, font: Float, naturalPx: Float): Int {
        val budget = punctuationAdvancePx(marks, font)
        if (marks < 2) return if (naturalPx > 0f) kotlin.math.ceil(naturalPx.toDouble()).toInt() else 0
        if (naturalPx <= 0f || !naturalPx.isFinite()) return budget
        return minOf(budget, kotlin.math.ceil(naturalPx.toDouble()).toInt())
    }

    /** Total replacement width: natural neighbours plus the bounded mark advance. */
    fun compressedRunAdvancePx(leadPx: Float, marks: Int, markNaturalPx: Float, trailPx: Float,
        font: Float): Int {
        val lead = if (leadPx.isFinite() && leadPx > 0f) leadPx else 0f
        val trail = if (trailPx.isFinite() && trailPx > 0f) trailPx else 0f
        return kotlin.math.ceil((lead + compressedAdvancePx(marks, font, markNaturalPx) + trail)
            .toDouble()).toInt()
    }

    /**
     * Bounded letter spacing for one line: `min(0.08, slack / (font * visibleCodePoints))`,
     * never negative. Returns 0 when no bounded value is meaningful.
     */
    fun justificationEm(slack: Float, font: Float, visibleCodePoints: Int): Float {
        if (visibleCodePoints < MIN_JUSTIFIED_CODE_POINTS || font <= 0f || slack <= 0f ||
            !slack.isFinite()) return 0f
        val raw = slack / (font * visibleCodePoints)
        return if (raw.isFinite()) raw.coerceIn(0f, MAX_LETTER_SPACING_EM) else 0f
    }

    /** Count visible (non-whitespace) code points from [start] to [end]. */
    fun visibleCodePoints(text: String, start: Int, end: Int): Int {
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        var count = 0
        var at = from
        while (at < to) {
            val codePoint = text.codePointAt(at)
            if (!Character.isWhitespace(codePoint)) count++
            at += Character.charCount(codePoint)
        }
        return count
    }

    /**
     * A wrapped body line may justify when it is CJK-dominant, has no word gaps, is not the final
     * line of a paragraph and was not already justified with word spacing.
     */
    fun canJustifyLine(text: String, start: Int, visibleEnd: Int, endsParagraph: Boolean,
        body: Boolean, wordJustified: Boolean): Boolean {
        if (!body || endsParagraph || wordJustified) return false
        val from = start.coerceIn(0, text.length)
        val to = visibleEnd.coerceIn(from, text.length)
        if (to <= from) return false
        var visible = 0
        var cjk = 0
        var cjkLetters = 0
        var at = from
        while (at < to) {
            val codePoint = text.codePointAt(at)
            if (Character.isWhitespace(codePoint)) return false
            visible++
            if (isCjk(codePoint)) cjk++
            if (isCjkLetter(codePoint)) cjkLetters++
            at += Character.charCount(codePoint)
        }
        return visible >= MIN_JUSTIFIED_CODE_POINTS && cjkLetters > 0 && cjk * 2 > visible
    }

    /**
     * Per-line extra letter spacing, in em, for the whole layout. Lines that fail
     * [canJustifyLine] receive 0 and are never widened.
     */
    fun letterSpacing(text: String, lines: List<LineSpan>, width: Float, font: Float): FloatArray {
        val result = FloatArray(lines.size)
        if (width <= 0f || font <= 0f) return result
        for (index in lines.indices) {
            val line = lines[index]
            if (!canJustifyLine(text, line.start, line.visibleEnd, line.endsParagraph, line.body,
                    line.wordJustified)) continue
            val visible = visibleCodePoints(text, line.start, line.visibleEnd)
            result[index] = justificationEm(width - line.maxWidth, font, visible)
        }
        return result
    }

    /**
     * Runs of two or more consecutive full-width punctuation marks. A run never crosses a line
     * feed, so no paragraph boundary can be joined by compression.
     */
    fun punctuationRuns(text: String, from: Int = 0, to: Int = text.length,
        limit: Int = Int.MAX_VALUE): List<PunctuationRun> {
        val start = from.coerceIn(0, text.length)
        val stop = to.coerceIn(start, text.length)
        val result = mutableListOf<PunctuationRun>()
        var at = start
        while (at < stop && result.size < limit) {
            val codePoint = text.codePointAt(at)
            if (codePoint == '\n'.code || !isFullWidthPunctuation(codePoint)) {
                at += Character.charCount(codePoint)
                continue
            }
            var end = at
            var marks = 0
            while (end < stop) {
                val next = text.codePointAt(end)
                if (next == '\n'.code || !isFullWidthPunctuation(next)) break
                marks++
                end += Character.charCount(next)
            }
            if (marks >= 2) result += PunctuationRun(at, end, marks)
            at = end
        }
        return result
    }

    /**
     * Compressible runs with a replacement span range. A spanning replacement hides the original
     * line-break class of its characters, so the span is extended by the preceding code point
     * (which may start a line) and, only when the run ends with an opening mark, by the following
     * code point (which may end a line). This keeps every resulting break typographically legal:
     * no line starts or ends with a mark whose class would forbid it. Runs without a safe
     * neighbour are left uncompressed.
     */
    fun compressionRuns(text: String, from: Int = 0, to: Int = text.length,
        limit: Int = Int.MAX_VALUE): List<CompressionRun> {
        val start = from.coerceIn(0, text.length)
        val stop = to.coerceIn(start, text.length)
        val rawLimit = if (limit == Int.MAX_VALUE) Int.MAX_VALUE else limit.coerceAtLeast(1) * 4
        val result = mutableListOf<CompressionRun>()
        var lastEnd = 0
        for (run in punctuationRuns(text, start, stop, rawLimit)) {
            if (result.size >= limit) break
            if (run.start <= start || run.start < lastEnd) continue
            val lead = text.offsetByCodePoints(run.start, -1)
            if (lead < start || !lineStartEligible(text.codePointAt(lead))) continue
            var end = run.end
            if (isOpeningPunctuation(text.codePointBefore(run.end))) {
                if (run.end >= stop) continue
                val next = text.codePointAt(run.end)
                if (!lineEndEligible(next)) continue
                end = run.end + Character.charCount(next)
            }
            val candidate = CompressionRun(lead, run.start, run.end, end, run.marks)
            if (result.isNotEmpty() && candidate.start < result.last().end) continue
            result += candidate
            lastEnd = end
        }
        return result
    }

    /** True for full-width/East-Asian punctuation and symbols only; letters and spaces are not. */
    fun isFullWidthPunctuation(codePoint: Int): Boolean = when {
        codePoint < 0x2013 || codePoint > 0xFF60 -> false
        // Fast reject for the two large Han blocks; they can never be punctuation here.
        codePoint in 0x3400..0x9FFF || codePoint in 0xF900..0xFAFF -> false
        codePoint in 0x2013..0x2015 -> true // en/em dash, horizontal bar
        codePoint in 0x2018..0x201F -> true // single/double quotes and their low variants
        codePoint in 0x2025..0x2026 -> true // two-dot leader, ellipsis
        codePoint in 0x3001..0x3003 -> true // ideographic comma/full stop/ditto
        codePoint in 0x3008..0x3011 -> true // angle, lenticular, corner, white corner brackets
        codePoint in 0x3014..0x301B -> true // tortoise-shell, white lenticular, white corner
        codePoint == 0x301C -> true // wave dash
        codePoint in 0x301D..0x301F -> true // reversed double prime quotation marks
        codePoint == 0x3030 -> true // wavy dash
        codePoint == 0x30FB -> true // katakana middle dot
        codePoint in 0xFF01..0xFF0F -> true // full-width ASCII punctuation, part one
        codePoint in 0xFF1A..0xFF20 -> true // full-width ASCII punctuation, part two
        codePoint in 0xFF3B..0xFF40 -> true // full-width ASCII brackets and marks
        codePoint in 0xFF5B..0xFF60 -> true // full-width ASCII braces, bar, tilde, white brackets
        else -> false
    }

    /** True for opening marks: a line must not end with one. */
    fun isOpeningPunctuation(codePoint: Int): Boolean = codePoint in OPENING_PUNCTUATION

    /** True when a code point may safely begin a line (not punctuation, mark or non-starter). */
    fun lineStartEligible(codePoint: Int): Boolean {
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) ||
            isPunctuation(codePoint) || isMark(codePoint)) return false
        return codePoint !in NON_STARTING
    }

    /** True when a code point may safely end a line (not whitespace, punctuation or mark). */
    fun lineEndEligible(codePoint: Int): Boolean =
        !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint) &&
            !isPunctuation(codePoint) && !isMark(codePoint)

    /** Han, kana, Hangul and other CJK letters; punctuation does not count as a letter. */
    fun isCjkLetter(codePoint: Int): Boolean = Character.isIdeographic(codePoint) ||
        codePoint in 0x3041..0x3096 || codePoint in 0x309D..0x309F ||
        codePoint in 0x30A1..0x30FA || codePoint in 0x30FD..0x30FF ||
        codePoint in 0x31F0..0x31FF || codePoint in 0x1100..0x11FF ||
        codePoint in 0x3130..0x318F || codePoint in 0xAC00..0xD7AF ||
        codePoint in 0xA960..0xA97F || codePoint in 0xD7B0..0xD7FF

    /** True when the code point belongs to the CJK typographic system for dominance counting. */
    fun isCjk(codePoint: Int): Boolean = isCjkLetter(codePoint) || isFullWidthPunctuation(codePoint)

    // Unicode general categories that must not appear at a line edge. Numeric constants avoid
    // the char-only java.lang.Character convenience predicates.
    private fun isPunctuation(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type in 20..24 || type == 29 || type == 30 // dash/start/end/connector/other/quote
    }

    private fun isMark(codePoint: Int): Boolean = Character.getType(codePoint) in 6..8

    private val OPENING_PUNCTUATION = setOf(0x2018, 0x201C, 0x2039, 0x3008, 0x300A, 0x300C, 0x300E,
        0x3010, 0x3014, 0x3016, 0x3018, 0x301A, 0x301D, 0xFF08, 0xFF3B, 0xFF5B, 0xFF5F)

    // Cannot begin a line: small kana, prolonged sound mark, iteration marks and combining kana
    // marks. Deliberately excluded from compression output.
    private val NON_STARTING = setOf(0x3005, 0x303B, 0x3099, 0x309A, 0x309D, 0x309E, 0x30FC,
        0x30FD, 0x30FE, 0x3041, 0x3043, 0x3045, 0x3047, 0x3049, 0x3083, 0x3085, 0x3087, 0x308E,
        0x3095, 0x3096, 0x30A1, 0x30A3, 0x30A5, 0x30A7, 0x30A9, 0x30C3, 0x30E3, 0x30E5, 0x30E7,
        0x30EE, 0x30F5, 0x30F6)

    // Deliberately excluded from compression: half-width forms (U+FF61..U+FF65), the iteration
    // mark 々 (U+3005), the prolonged sound mark ー (U+30FC) and the ideographic space U+3000.
    // They are either letters, already narrow, or spacing rather than marks.
}

/**
 * Draw a run of full-width marks in a bounded advance without editing the text. The marks are
 * measured as one replacement and scaled horizontally to the bounded advance; the safe leading
 * (and, for opening-mark runs, trailing) neighbour is drawn at its natural advance so a line
 * break can never expose a forbidden mark at the start or end of a line. Drawing order and every
 * character are preserved.
 */
internal class CompressedPunctuation(private val leadChars: Int, private val marks: Int,
    private val trailChars: Int) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int,
        fm: Paint.FontMetricsInt?): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        val spacing = paint.letterSpacing
        return try {
            paint.letterSpacing = 0f
            val leadEnd = start + leadChars
            val markEnd = end - trailChars
            CjkTypography.compressedRunAdvancePx(paint.measureText(text, start, leadEnd), marks,
                paint.measureText(text, leadEnd, markEnd), paint.measureText(text, markEnd, end),
                paint.textSize)
        } finally {
            paint.letterSpacing = spacing
        }
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int,
        y: Int, bottom: Int, paint: Paint) {
        val originalLetterSpacing = paint.letterSpacing
        val originalScaleX = paint.textScaleX
        try {
            paint.letterSpacing = 0f
            paint.textScaleX = originalScaleX
            val leadEnd = start + leadChars
            val markEnd = end - trailChars
            val lead = paint.measureText(text, start, leadEnd)
            val natural = paint.measureText(text, leadEnd, markEnd)
            val target = CjkTypography.compressedAdvancePx(marks, paint.textSize, natural).toFloat()
            if (leadChars > 0) canvas.drawText(text, start, leadEnd, x, y.toFloat(), paint)
            if (natural > 0f && target > 0f) paint.textScaleX = originalScaleX * (target / natural)
            if (marks > 0) canvas.drawText(text, leadEnd, markEnd, x + lead, y.toFloat(), paint)
            paint.textScaleX = originalScaleX
            if (trailChars > 0) canvas.drawText(text, markEnd, end, x + lead + target, y.toFloat(),
                paint)
        } finally {
            paint.letterSpacing = originalLetterSpacing
            paint.textScaleX = originalScaleX
        }
    }
}
