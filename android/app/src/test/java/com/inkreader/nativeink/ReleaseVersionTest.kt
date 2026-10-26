package com.inkreader.nativeink

import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    @Test fun previewsAreComparedNumerically() {
        assertTrue(ReleaseVersion.parse("0.1.0-preview.10")!! > ReleaseVersion.parse("v0.1.0-preview.9")!!)
        assertTrue(ReleaseVersion.parse("0.2.0-preview.1")!! > ReleaseVersion.parse("0.1.9")!!)
    }
    @Test fun stableSupersedesItsPreviews() {
        assertTrue(ReleaseVersion.parse("0.1.0")!! > ReleaseVersion.parse("0.1.0-preview.999")!!)
        assertEquals(ReleaseVersion.parse("0.1.0"),ReleaseVersion.parse("v0.1.0"))
    }
    @Test fun malformedOrOverflowingTagsAreRejected() {
        for(tag in listOf("latest","1.0","0.1.0-alpha","0.1.0-preview.-1","999999999999.0.0","0.1.0/path"))
            assertNull(tag,ReleaseVersion.parse(tag))
    }
}
