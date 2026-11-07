package com.inkreader.nativeink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM coverage for the bounded CJK typography rules; no Android framework needed. */
class CjkTypographyTest {
    private fun em(expected:Float,actual:Float) = assertEquals(expected,actual,1e-6f)

    @Test fun letterSpacingCapIsEightyMilliEm() {
        em(0.08f,CjkTypography.MAX_LETTER_SPACING_EM)
        em(1f,CjkTypography.MAX_PUNCTUATION_RUN_EM)
        em(0.5f,CjkTypography.PUNCTUATION_MARK_EM)
        assertEquals(2,CjkTypography.MIN_JUSTIFIED_CODE_POINTS)
    }

    @Test fun compressedAdvanceIsHalfEmPerMarkCappedAtOneEm() {
        em(0f,CjkTypography.punctuationAdvanceEm(0))
        em(0f,CjkTypography.punctuationAdvanceEm(1))
        em(1f,CjkTypography.punctuationAdvanceEm(2))
        em(1f,CjkTypography.punctuationAdvanceEm(3))
        em(1f,CjkTypography.punctuationAdvanceEm(9))
    }

    @Test fun compressedAdvancePixelsRoundUpWithinTheRunCap() {
        assertEquals(30,CjkTypography.punctuationAdvancePx(2,30f))
        assertEquals(30,CjkTypography.punctuationAdvancePx(3,30f))
        assertEquals(18,CjkTypography.punctuationAdvancePx(2,17.5f))
        assertEquals(0,CjkTypography.punctuationAdvancePx(1,30f))
    }

    @Test fun compressedAdvanceNeverStretchesANarrowRun() {
        assertEquals(30,CjkTypography.compressedAdvancePx(2,30f,60f))
        assertEquals(20,CjkTypography.compressedAdvancePx(2,30f,20f))
        assertEquals(30,CjkTypography.compressedAdvancePx(2,30f,0f))
        assertEquals(2,CjkTypography.compressedAdvancePx(2,30f,1.2f))
        assertEquals(7,CjkTypography.compressedAdvancePx(1,30f,6.2f))
        assertEquals(0,CjkTypography.compressedAdvancePx(1,30f,0f))
    }

    @Test fun compressionSpansAddNaturalNeighboursToTheBoundedMarks() {
        assertEquals(30,CjkTypography.compressedRunAdvancePx(0f,2,60f,0f,30f))
        assertEquals(43,CjkTypography.compressedRunAdvancePx(12.7f,2,60f,0f,30f))
        assertEquals(32,CjkTypography.compressedRunAdvancePx(12f,2,20f,0f,30f))
        assertEquals(48,CjkTypography.compressedRunAdvancePx(12f,2,60f,5.2f,30f))
        assertEquals(30,CjkTypography.compressedRunAdvancePx(Float.NaN,2,Float.NaN,Float.NaN,30f))
    }

    @Test fun compressionRunsCarryASafeLeadingNeighbour() {
        val run=CjkTypography.compressionRuns("他说。」然后").single()
        assertEquals(1,run.start)
        assertEquals(2,run.markStart)
        assertEquals(4,run.markEnd)
        assertEquals(4,run.end)
        assertEquals(2,run.marks)
        assertEquals(1,run.leadChars)
        assertEquals(0,run.trailChars)
        val ellipsis=CjkTypography.compressionRuns("书……").single()
        assertEquals(0,ellipsis.start)
        assertEquals(1,ellipsis.markStart)
        assertEquals(2,ellipsis.marks)
    }

    @Test fun openingMarkRunsAlsoCarryASafeTrailingNeighbour() {
        val run=CjkTypography.compressionRuns("A「『文").single()
        assertEquals(0,run.start)
        assertEquals(1,run.markStart)
        assertEquals(3,run.markEnd)
        assertEquals(4,run.end)
        assertEquals(1,run.trailChars)
        assertTrue(CjkTypography.compressionRuns("A「『\n").isEmpty())
    }

    @Test fun compressionRunsSkipUnsafeOrUnavailableNeighbours() {
        assertTrue(CjkTypography.compressionRuns("。」").isEmpty())
        assertTrue("whitespace lead",CjkTypography.compressionRuns("A 。」").isEmpty())
        assertTrue("punctuation lead",CjkTypography.compressionRuns("a.。」").isEmpty())
        assertTrue("non-starting lead",CjkTypography.compressionRuns("ー……").isEmpty())
        assertTrue("paragraph boundary",CjkTypography.compressionRuns("。\n。").isEmpty())
        assertTrue(CjkTypography.compressionRuns("A「『b……").size==1)
        assertTrue(CjkTypography.compressionRuns("书……a……").size==2)
    }

    @Test fun runEnumerationIsBoundedForPunctuationHeavyText() {
        val text="书……a……b……"
        assertTrue(CjkTypography.punctuationRuns(text,limit=1).size==1)
        assertTrue(CjkTypography.compressionRuns(text,limit=1).size==1)
        assertTrue(CjkTypography.compressionRuns(text).size==3)
    }

    @Test fun supplementaryNeighboursKeepUtf16Offsets() {
        val text="🙂……"
        val run=CjkTypography.compressionRuns(text).single()
        assertEquals(0,run.start)
        assertEquals(2,run.markStart)
        assertEquals(4,run.markEnd)
        assertEquals(4,run.end)
        assertEquals(2,run.leadChars)
        assertEquals(0,run.trailChars)
        assertEquals("🙂……",text.substring(run.start,run.end))
    }

    @Test fun lineEdgeEligibilityKeepsBreaksTypographicallyLegal() {
        for(cp in listOf("中","A","1","🙂")) assertTrue("$cp may start",CjkTypography.lineStartEligible(cp.codePointAt(0)))
        for(cp in listOf(" ","\n","。","、","ー","々","｡")) assertFalse("$cp may not start",CjkTypography.lineStartEligible(cp.codePointAt(0)))
        for(cp in listOf("中","A","🙂","ー")) assertTrue("$cp may end",CjkTypography.lineEndEligible(cp.codePointAt(0)))
        for(cp in listOf(" ","\n","。","、")) assertFalse("$cp may not end",CjkTypography.lineEndEligible(cp.codePointAt(0)))
        for(cp in listOf("「","『","（","【","“")) assertTrue("$cp opens",CjkTypography.isOpeningPunctuation(cp.codePointAt(0)))
        for(cp in listOf("」","』","）","】","”","。")) assertFalse("$cp does not open",CjkTypography.isOpeningPunctuation(cp.codePointAt(0)))
    }

    @Test fun punctuationRunsFindTheSpecifiedExamples() {
        val cases=mapOf(
            "。」" to listOf("。」"),
            "……」「" to listOf("……」「"),
            "，、" to listOf("，、"),
            "他说。」然后" to listOf("。」"),
            "他。「文」" to listOf("。「")
        )
        for((text,expected) in cases) {
            val runs=CjkTypography.punctuationRuns(text)
            assertEquals(text,expected,runs.map {text.substring(it.start,it.end)})
            assertEquals(text,expected.size,runs.size)
            for(run in runs) {
                assertEquals(text,run.marks,text.codePointCount(run.start,run.end))
                assertTrue(text,run.start<run.end)
                assertTrue(text,run.start==0||runs.none {it!==run&&it.end>run.start&&it.start<run.end})
            }
        }
    }

    @Test fun singleMarksAndNonFullWidthMarksAreNotCompressed() {
        assertTrue(CjkTypography.punctuationRuns("。").isEmpty())
        assertTrue(CjkTypography.punctuationRuns("……").size==1)
        assertTrue(CjkTypography.punctuationRuns("｡､").isEmpty())
        assertTrue(CjkTypography.punctuationRuns("......").isEmpty())
        assertTrue(CjkTypography.punctuationRuns("。。").size==1)
        assertTrue(CjkTypography.punctuationRuns("。🙂。").isEmpty())
        assertTrue(CjkTypography.punctuationRuns("").isEmpty())
    }

    @Test fun compressionNeverCrossesAParagraphBoundary() {
        assertTrue(CjkTypography.punctuationRuns("。\n。").isEmpty())
        assertTrue(CjkTypography.punctuationRuns("……\n「」").map {it.marks}==listOf(2,2))
    }

    @Test fun supplementaryCodePointsKeepUtf16Offsets() {
        val text="𠮷。」"
        val run=CjkTypography.punctuationRuns(text).single()
        assertEquals(2,run.start)
        assertEquals(4,run.end)
        assertEquals(2,run.marks)
        assertEquals("。」",text.substring(run.start,run.end))
    }

    @Test fun fullWidthClassificationIsConservative() {
        for(mark in listOf("。","、","」","「","！","：","…","—","～","・","“","”")) assertTrue(mark,CjkTypography.isFullWidthPunctuation(mark.codePointAt(0)))
        for(other in listOf("中","A"," ","\n","々","ー","｡","､","🙂")) assertFalse(other,CjkTypography.isFullWidthPunctuation(other.codePointAt(0)))
    }

    @Test fun visibleCodePointsIgnoreWhitespaceAndCountSurrogatesOnce() {
        assertEquals(0,CjkTypography.visibleCodePoints("",0,0))
        assertEquals(2,CjkTypography.visibleCodePoints("中文",0,2))
        assertEquals(2,CjkTypography.visibleCodePoints("中 文",0,3))
        assertEquals(2,CjkTypography.visibleCodePoints("中　文",0,3))
        assertEquals(2,CjkTypography.visibleCodePoints("𠮷🙂",0,4))
        assertEquals(2,CjkTypography.visibleCodePoints("x中y文",1,3))
        assertEquals(0,CjkTypography.visibleCodePoints("中文",2,2))
    }

    @Test fun onlyWrappedCjkDominantBodyLinesCanJustify() {
        val cjk="这是一行用于两端对齐的中文正文内容"
        assertTrue(CjkTypography.canJustifyLine(cjk,0,cjk.length,false,true,false))
        val kana="ひらがなの文章をそろえる"
        assertTrue(CjkTypography.canJustifyLine(kana,0,kana.length,false,true,false))
        val hangul="한국어문장을정렬한다"
        assertTrue(CjkTypography.canJustifyLine(hangul,0,hangul.length,false,true,false))
        val mixed="𠮷🙂中文阅读"
        assertTrue(CjkTypography.canJustifyLine(mixed,0,mixed.length,false,true,false))
        val latin="A quiet reader follows the path"
        assertFalse(CjkTypography.canJustifyLine(latin,0,latin.length,false,true,false))
        val marks="……」"
        assertFalse("punctuation-only lines are not justified",CjkTypography.canJustifyLine(marks,0,marks.length,false,true,false))
        assertFalse("paragraph ends",CjkTypography.canJustifyLine(cjk,0,cjk.length,true,true,false))
        assertFalse("not body",CjkTypography.canJustifyLine(cjk,0,cjk.length,false,false,false))
        assertFalse("already word justified",CjkTypography.canJustifyLine(cjk,0,cjk.length,false,true,true))
        val spaced="中文 正文"
        assertFalse("word gaps",CjkTypography.canJustifyLine(spaced,0,spaced.length,false,true,false))
        val halfLatin="中文ab"
        assertFalse("Latin-majority line",CjkTypography.canJustifyLine(halfLatin,0,halfLatin.length,false,true,false))
    }

    @Test fun justificationIsBoundedAndNeverNegative() {
        em(0f,CjkTypography.justificationEm(0f,30f,10))
        em(0f,CjkTypography.justificationEm(-4f,30f,10))
        em(0f,CjkTypography.justificationEm(10f,0f,10))
        em(0f,CjkTypography.justificationEm(10f,30f,1))
        em(0f,CjkTypography.justificationEm(Float.NaN,30f,10))
        em(0.08f,CjkTypography.justificationEm(1000f,30f,10))
        em(0.06666667f,CjkTypography.justificationEm(10f,30f,5))
        em(0.01f,CjkTypography.justificationEm(3f,30f,10))
    }

    @Test fun letterSpacingAppliesOnlyToEligibleLinesAndFitsTheSlack() {
        val text="这是一行需要两端对齐的中文正文内容"
        val width=400f;val font=30f
        val lines=listOf(
            CjkTypography.LineSpan(0,text.length,width-30f,false,true,false),
            CjkTypography.LineSpan(0,text.length,width-30f,true,true,false),
            CjkTypography.LineSpan(0,text.length,width-30f,false,true,true),
            CjkTypography.LineSpan(0,text.length,width-30f,false,false,false),
            CjkTypography.LineSpan(0,text.length,width-1f,false,true,false)
        )
        val spacing=CjkTypography.letterSpacing(text,lines,width,font)
        assertEquals(lines.size,spacing.size)
        val visible=CjkTypography.visibleCodePoints(text,0,text.length)
        em(30f/(30f*visible),spacing[0])
        em(0f,spacing[1])
        em(0f,spacing[2])
        em(0f,spacing[3])
        for(index in lines.indices) {
            val line=lines[index]
            val slack=width-line.maxWidth
            assertTrue("negative spacing",spacing[index]>=0f)
            assertTrue("cap exceeded",spacing[index]<=CjkTypography.MAX_LETTER_SPACING_EM+1e-6f)
            assertTrue("line would overflow",spacing[index]*font*visible<=slack+1e-3f)
        }
    }

    @Test fun letterSpacingHandlesEmptyLayouts() {
        assertTrue(CjkTypography.letterSpacing("",emptyList(),400f,30f).isEmpty())
        assertTrue(CjkTypography.letterSpacing("",listOf(CjkTypography.LineSpan(0,0,0f,true,true,false)),400f,30f).contentEquals(FloatArray(1)))
    }
}
