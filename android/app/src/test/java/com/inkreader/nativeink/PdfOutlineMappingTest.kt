package com.inkreader.nativeink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure PDF outline/destination mapping; no Android or PDFBox runtime needed. */
class PdfOutlineMappingTest {
    @Test fun `titles are normalized and non-chapters are dropped`() {
        assertEquals("Laundry Room",PdfOutlineMapping.normalizeTitle("  Laundry \n Room "))
        assertNull(PdfOutlineMapping.normalizeTitle("   "))
        assertNull(PdfOutlineMapping.normalizeTitle("12"))
        assertNull(PdfOutlineMapping.normalizeTitle("Contents"))
        assertNull(PdfOutlineMapping.normalizeTitle("Table of Contents"))
        assertNull(PdfOutlineMapping.normalizeTitle("封面"))
        assertEquals("Church (short story)",PdfOutlineMapping.normalizeTitle("Church (short story)"))
    }

    @Test fun `entries map to the first text block at or after their destination page`() {
        val firstBlockByPage=mapOf(3 to 0,6 to 1,9 to 2)
        val entries=listOf(
            PdfOutlineMapping.Entry("Preface",3,1),
            PdfOutlineMapping.Entry("Laundry Room",6,1),
            PdfOutlineMapping.Entry("Invisible",9,2)
        )
        val nav=PdfOutlineMapping.map(entries,firstBlockByPage,84)
        assertEquals(listOf("Preface","Laundry Room","Invisible"),nav.map {it.first})
        assertEquals(listOf(0,1,2),nav.map {it.second.first})
        assertEquals(listOf(1,1,2),nav.map {it.second.second})
        assertEquals(setOf("pdf"),nav.map {it.third}.toSet())
    }

    @Test fun `blank or contents destination pages advance to the next text block`() {
        val firstBlockByPage=mapOf(7 to 4,8 to 5)
        val entries=listOf(PdfOutlineMapping.Entry("Chapter One",5,1))
        val nav=PdfOutlineMapping.map(entries,firstBlockByPage,10)
        assertEquals(1,nav.size)
        assertEquals(4,nav.single().second.first)
        // Only a bounded look-ahead is allowed; a far-away block is not silently attached.
        assertTrueEmpty(PdfOutlineMapping.map(listOf(PdfOutlineMapping.Entry("Detached",0,1)),mapOf(9 to 3),10))
    }

    @Test fun `out of range pages, duplicate destinations and depths are bounded`() {
        val firstBlockByPage=mapOf(0 to 0,5 to 1)
        val nav=PdfOutlineMapping.map(
            listOf(
                PdfOutlineMapping.Entry("Start",0,0),
                PdfOutlineMapping.Entry("Start",0,1),
                PdfOutlineMapping.Entry("End",5,9),
                PdfOutlineMapping.Entry("Missing",500,1)
            ),
            firstBlockByPage,6
        )
        assertEquals(2,nav.size)
        assertEquals(0,nav[0].second.first)
        assertEquals(1,nav[0].second.second)
        assertEquals(1,nav[1].second.first)
        assertEquals(3,nav[1].second.second)
        assertTrueEmpty(PdfOutlineMapping.map(emptyList(),firstBlockByPage,6))
        assertTrueEmpty(PdfOutlineMapping.map(listOf(PdfOutlineMapping.Entry("Any",0,1)),firstBlockByPage,0))
    }

    @Test fun `a heading printed mid-page wins over the first block on the page`() {
        val firstBlockByPage=mapOf(70 to 131,71 to 134)
        val blocksByPage=mapOf(70 to listOf(131,134))
        val texts=MutableList(140){""}
        texts[131]="Days without breeze,\nWithout weeping"
        texts[134]="Tourist"
        val nav=PdfOutlineMapping.map(
            listOf(PdfOutlineMapping.Entry("Tourist",70,1)),
            firstBlockByPage,84,blocksByPage=blocksByPage,blockTexts=texts
        )
        assertEquals(1,nav.size)
        assertEquals(134,nav.single().second.first)
        assertEquals("pdf",nav.single().third)
        assertTrue(PdfOutlineMapping.matchesTitle("Church (short story)","Church"))
        assertTrue(PdfOutlineMapping.matchesTitle("Laundry Room","Laundry Room"))
        assertFalse(PdfOutlineMapping.matchesTitle("Days without breeze","Tourist"))
    }

    @Test fun `page number only lines are not prose`() {
        assertEquals(true,PdfOutlineMapping.pageNumber("12"))
        assertEquals(true,PdfOutlineMapping.pageNumber("iv"))
        assertEquals(false,PdfOutlineMapping.pageNumber("Chapter 12"))
        assertEquals(false,PdfOutlineMapping.pageNumber(""))
    }

    private fun assertTrueEmpty(value:List<Any>)=assertEquals(0,value.size)
}
