package com.inkreader.nativeink

import org.junit.Assert.*
import org.junit.Test

class SurfacePoseTest {
    @Test fun returningToReadingResetsOldPoseWithoutReplayingMovement() {
        val pose = SurfacePose()
        pose.offer(.5f, -.4f)
        assertTrue(pose.reset())
        assertEquals(0f, pose.x, 0f)
        assertEquals(0f, pose.y, 0f)
        assertFalse(pose.offer(.002f, -.002f))
    }
    @Test fun tinySensorNoiseDoesNotKeepRepaintingAStationaryPage() {
        val pose = SurfacePose()
        pose.offer(.3f, -.4f)
        var redraws = 0
        repeat(1000) { i ->
            val jitter = if (i % 2 == 0) .003f else -.003f
            if (pose.offer(.3f + jitter, -.4f - jitter)) redraws++
        }
        assertEquals(0, redraws)
    }
    @Test fun movementIsDampedBoundedAndEventuallySettles() {
        val pose = SurfacePose()
        pose.offer(0f, 0f)
        assertTrue(pose.offer(4f, -4f))
        assertTrue(pose.x in 0f..0.3f)
        repeat(80) { pose.offer(4f, -4f) }
        assertTrue(pose.x in .82f.. .85f)
        assertTrue(pose.y in -.85f..-.82f)
        assertFalse(pose.offer(4f, -4f))
        assertFalse(pose.offer(Float.NaN, Float.POSITIVE_INFINITY))
    }
}
