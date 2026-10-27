package com.inkreader.nativeink

import org.junit.Assert.*
import org.junit.Test

class ReaderViewportTest {
    @Test fun usesActualWindowDensityAndSafeInsets() {
        val v=ReaderViewport.calculate(3240,2160,3f,1.3f,0,72,0,72)
        assertEquals(1080f,v.width,0f);assertEquals(672f,v.height,0f)
        assertEquals(1.3f,v.fontScale,0f)
        assertEquals(72f,v.screenY(0f),0f)
    }
    @Test fun touchAndDrawingUseTheSameCoordinateMap() {
        for(density in listOf(1f,2f,2.5f,3f)) {
            val v=ReaderViewport.calculate(1800,1200,density,1f,80,60,40,90)
            for(p in listOf(0f,24f,97.5f,200f)) {
                assertEquals(p,v.contentX(v.screenX(p)),.001f)
                assertEquals(p,v.contentY(v.screenY(p)),.001f)
            }
        }
    }
    @Test fun malformedBoundsCannotCreateAnEmptyOrNegativeViewport() {
        val v=ReaderViewport.calculate(0,0,Float.NaN,0f,50,-3,50,80)
        assertTrue(v.width>0 && v.height>0);assertEquals(1f,v.density,0f)
    }
}
