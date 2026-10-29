package com.inkreader.nativeink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingAnchorMapTest {
    @Test fun whitespaceRepairKeepsTheSameRepeatedParagraph() {
        val paragraph="A repeated paragraph must stay at its original source position."
        val old=(1..12).joinToString("\n\n"){paragraph}
        val new=old.replace(" ","  ")
        val offset=old.lastIndexOf("source position")
        val mapped=ReadingAnchorMap(old,new).map(offset)
        assertEquals(new.lastIndexOf("source  position"),mapped)
        assertEquals(11,new.substring(0,mapped).windowed("A  repeated".length).count {it=="A  repeated"}-1)
    }
    @Test fun ARepairAfterTheReadingPositionKeepsItsExactOffset() {
        val old="This paragraph repeats. ".repeat(20)+"Author: A Publisher: B"
        val new=old.replace("Publisher:","\n\nPublisher:")
        assertEquals(130,ReadingAnchorMap(old,new).map(130))
    }
    @Test fun unchangedSuffixTracksAnInsertedHeading() {
        val old="Cover\n\n"+"A reader follows a long source sentence. ".repeat(10)
        val new=old.replace("Cover","Cover and a newly recognized subtitle")
        val at=old.lastIndexOf("source sentence")
        assertEquals(new.lastIndexOf("source sentence"),ReadingAnchorMap(old,new).map(at))
    }
    @Test fun realTextEditsChooseTheNearbyOccurrenceRatherThanTheFirst() {
        val old="old opening. "+"The long repeated paragraph is part of a complete book. ".repeat(15)+"old closing"
        val new=old.replace("old opening","new opening").replace("old closing","new closing")
        val at=old.lastIndexOf("repeated paragraph")
        assertTrue(ReadingAnchorMap(old,new).map(at)>new.length*4/5)
    }
    @Test fun emptyAndOutOfRangeAnchorsStayBounded() {
        assertEquals(0,ReadingAnchorMap("","").map(12))
        assertEquals(0,ReadingAnchorMap("abc","").map(12))
        assertEquals(2,ReadingAnchorMap("abc","abc").map(12))
    }
}
