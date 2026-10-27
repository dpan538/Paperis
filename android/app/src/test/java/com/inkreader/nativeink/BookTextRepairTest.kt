package com.inkreader.nativeink

import org.junit.Assert.*
import org.junit.Test

class BookTextRepairTest {
    @Test fun repairsUniqueFragmentsOnlyWhenTheSameBookSupportsTheCompleteWord() {
        val input=listOf("reading reading reading", "by read ing slowly .")
        assertEquals("by reading slowly.",BookTextRepair.english(input).last())
    }
    @Test fun keepsRealWordBoundariesAndNamesWithoutGuessing() {
        val value="in to the room, a lone reader may choose any one. An unknown frag ment stays."
        assertEquals(value,BookTextRepair.english(listOf("into into into alone alone alone anyone anyone anyone",value)).last())
    }
    @Test fun shortNotationIsNotTreatedAsProsePunctuation() {
        assertEquals(listOf("sin . cos","x . y"),BookTextRepair.english(listOf("sin . cos","x . y")))
    }
}
