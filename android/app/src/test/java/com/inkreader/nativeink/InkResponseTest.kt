package com.inkreader.nativeink

import org.junit.Assert.*
import org.junit.Test

class InkResponseTest {
    @Test fun sceneTimingChangesWithoutChangingWaveformEndpoints() {
        for(mode in 0..2)for(scene in RefreshPolicy.Scene.entries) {
            val r=InkResponse(0);val full=scene==RefreshPolicy.Scene.CLEAN
            val duration=RefreshPolicy.duration(mode,scene)
            r.offer(1,100,full,durationMs=duration,surface=scene==RefreshPolicy.Scene.SURFACE)
            assertEquals(0L,r.canonicalElapsed(100))
            assertEquals(if(full)InkResponse.FULL_MS else InkResponse.PARTIAL_MS,r.canonicalElapsed(100+duration))
            assertFalse(r.advance(100+duration-1));assertNotNull(r.active)
            r.advance(100+duration);assertNull(r.active);assertEquals(1,r.displayed)
        }
        assertTrue(RefreshPolicy.duration(0,RefreshPolicy.Scene.MENU)<RefreshPolicy.duration(0,RefreshPolicy.Scene.PAGE))
        assertTrue(RefreshPolicy.duration(2,RefreshPolicy.Scene.PAGE)<RefreshPolicy.duration(1,RefreshPolicy.Scene.PAGE))
    }
    @Test fun changingModeKeepsInFlightTimingAndLatestSurfaceTarget() {
        val r=InkResponse(0)
        r.offer(1,0,durationMs=600,surface=true)
        r.offer(2,40,durationMs=140)
        assertEquals(600L,r.duration);assertTrue(r.active!!.surface)
        r.advance(600);assertEquals(140L,r.duration);assertEquals(2,r.displayed)
    }
    @Test fun queuedCleanRetainsItsDurationAndSurfaceChange() {
        val r=InkResponse(0);r.offer(1,0,durationMs=200)
        r.offer(2,10,full=true,durationMs=880,surface=true)
        r.offer(3,20,durationMs=140)
        r.advance(200)
        assertEquals(3,r.displayed);assertTrue(r.active!!.full);assertTrue(r.active!!.surface);assertEquals(880L,r.duration)
    }
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
