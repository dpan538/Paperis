package com.inkreader.nativeink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text-layer detection must not confuse a scanned page with a few printed folio digits. */
class PdfTextLayerTest {
    private fun result(pages:Int,proseCharacters:Int,prosePages:Int)=
        PdfBoxText.Result(pages,List(pages){441 to 666},List(pages){emptyList()},emptyList(),proseCharacters,prosePages)

    @Test fun `a scanned book with only folio digits is not a text layer`() {
        assertFalse(result(84,88,1).hasTextLayer)
        assertFalse(result(300,100,5).hasTextLayer)
    }

    @Test fun `a real text layer needs meaningful text on a meaningful share of pages`() {
        assertTrue(result(84,28_000,51).hasTextLayer)
        assertTrue(result(1,31,1).hasTextLayer)
    }
}
