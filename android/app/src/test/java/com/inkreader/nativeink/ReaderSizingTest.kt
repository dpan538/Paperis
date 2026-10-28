package com.inkreader.nativeink

import org.junit.Assert.*
import org.junit.Test

class ReaderSizingTest {
    @Test fun normalPhoneAndShortLandscapeHavePracticalReadingSizes() {
        for ((w,h) in listOf(296f to 592f,336f to 752f,369f to 825f,388f to 867f,776f to 288f)) {
            val sizing=ReaderSizing(w,h)
            assertEquals(18f,sizing.fontSizes[1],0f)
            assertTrue(sizing.fontSizes.first()>=16f)
            assertTrue(sizing.bodyTop<h-sizing.bottomSpace)
        }
    }
    @Test fun establishedLargeTabletKeepsItsTypographyAndSpread() {
        val sizing=ReaderSizing(1680f,1088f)
        assertEquals(30f,sizing.fontSizes[1],0f)
        assertTrue(sizing.canUseTwoColumns(30f,1496f,88f))
    }
    @Test fun compactHeightAndInsufficientColumnMeasureRejectDoubleColumns() {
        assertFalse(ReaderSizing(936f,360f).canUseTwoColumns(18f,840f,48f))
        assertFalse(ReaderSizing(839f,600f).canUseTwoColumns(22f,743f,44f))
        assertFalse(ReaderSizing(1100f,700f).canUseTwoColumns(48f,1000f,60f))
        assertTrue(ReaderSizing(1200f,900f).canUseTwoColumns(30f,1070f,62f))
    }
    @Test fun dimensionsAndFontArraysDoNotLeakBetweenWindows() {
        val phone=ReaderSizing(336f,752f);val pad=ReaderSizing(1680f,1088f)
        val sizes=phone.fontSizes;sizes[1]=100f
        assertEquals(18f,phone.fontSizes[1],0f)
        assertEquals(30f,pad.fontSizes[1],0f)
        assertFalse(ReaderSizing(850f,479f).canUseTwoColumns(18f,754f,44f))
    }
}
