package com.inkreader.nativeink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Authoritative PDF outline wiring through BookAssembly; no Android runtime needed. */
class PdfOutlineAssemblyTest {
    private fun assembly():BookAssembly = BookAssembly().apply {
        add(SourceBlock("Preface",kind="heading",level=1,page=3))
        add(SourceBlock("I am a designer and writer. This book unfolds a year of meditation.",kind="body",page=3))
        add(SourceBlock("Laundry Room",kind="heading",level=1,page=6))
        add(SourceBlock("I once asked a professor about the church,",kind="verse",page=6))
        add(SourceBlock("Choice",kind="heading",level=1,page=17))
        add(SourceBlock("A complete paragraph of the third chapter belongs here.",kind="body",page=17))
    }
    private fun outlineAndFinish(authoritative:Boolean):ParsedBook = assembly().apply {
        outline+=Triple("Preface",0 to 1,"pdf")
        outline+=Triple("Laundry Room",2 to 1,"pdf")
        outline+=Triple("Choice",4 to 1,"pdf")
        outlineAuthoritative=authoritative
    }.finish("The Threes","PDF")

    @Test fun `native pdf outline becomes the authoritative directory source`() {
        val book=outlineAndFinish(true)
        assertEquals("pdf",book.structure.directorySource)
        assertEquals(listOf("Preface","Laundry Room","Choice"),book.structure.chapters.map {it.title})
        assertEquals(setOf("pdf"),book.structure.chapters.map {it.source}.toSet())
        assertTrue(book.structure.chapters.zipWithNext().all {(a,b)->a.offset<b.offset})
        assertEquals(-1,book.structure.blocks.first().page)
        assertEquals("paperis:book-title",book.structure.blocks.first().origin)
    }

    @Test fun `an authoritative outline does not duplicate generated headings`() {
        val book=outlineAndFinish(true)
        assertEquals(3,book.structure.chapters.size)
        // Every chapter still starts on a real heading block at the recorded offset.
        book.structure.chapters.forEach {chapter->
            val block=book.structure.blocks.first {chapter.offset in it.start until it.end}
            assertEquals("heading",block.kind)
        }
    }

    @Test fun `printed contents fallback still reports a generated directory`() {
        val book=assembly().finish("The Threes","PDF")
        assertEquals("generated",book.structure.directorySource)
        assertTrue(book.structure.chapters.all {it.source=="generated"})
        assertTrue(book.structure.chapters.isNotEmpty())
    }
}
