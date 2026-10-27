package com.inkreader.nativeink

import org.junit.Assert.*
import org.junit.Test

class InkResponseTest {
    @Test fun rapidInputRetainsOnlyTheLatestTarget() {
        val display = InkResponse(0)
        assertTrue(display.offer(1, 100))
        for (page in 2..100) assertFalse(display.offer(page, 120 + page.toLong()))
        assertEquals(100, display.pending?.value)
        assertTrue(display.advance(600))
        assertEquals(100, display.displayed)
        assertEquals(1, display.previous)
        assertNull(display.pending)
        display.advance(1200)
        assertNull(display.active)
    }

    @Test fun explicitCleanIsNotLostWhenAnotherPageArrives() {
        val display = InkResponse(0)
        display.offer(1, 0)
        display.offer(1, 30, full = true)
        display.offer(2, 50)
        display.advance(500)
        assertEquals(2, display.displayed)
        assertTrue(display.active!!.full)
    }

    @Test fun ordinaryTurnsNeverForcePeriodicBlackFlashes() {
        val display=InkResponse(0)
        for(page in 1..30) {
            display.offer(page,page*2000L)
            assertFalse(display.active!!.full)
            display.advance(page*2000L+InkResponse.PARTIAL_MS)
            assertNull(display.active)
        }
    }

    @Test fun reducedMotionImmediatelyClearsEveryPendingFlash() {
        val display = InkResponse(0)
        display.offer(1, 0, full = true)
        display.offer(2, 10)
        display.offer(3, 20, instant = true)
        assertEquals(3, display.displayed)
        assertEquals(3, display.previous)
        assertNull(display.active)
        assertNull(display.pending)
    }

    @Test fun lifecycleSettlesOnLatestRequestedPage() {
        val display = InkResponse(0)
        display.offer(1, 0, full = true)
        display.offer(4, 10)
        display.settle()
        assertEquals(4, display.displayed)
        assertEquals(4, display.previous)
        assertFalse(display.advance(10000))
        assertNull(display.active)
    }
}
