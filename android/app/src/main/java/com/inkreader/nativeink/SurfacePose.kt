package com.inkreader.nativeink

import kotlin.math.abs

/** Bounded, damped material pose. Sensor noise must not keep a still screen repainting. */
internal class SurfacePose {
    var x = 0f; private set
    var y = 0f; private set
    private var filteredX = 0f
    private var filteredY = 0f
    private var initialized = false

    fun reset(): Boolean {
        val changed = x != 0f || y != 0f
        x = 0f; y = 0f; filteredX = 0f; filteredY = 0f; initialized = false
        return changed
    }

    fun offer(targetX: Float, targetY: Float): Boolean {
        if (!targetX.isFinite() || !targetY.isFinite()) return false
        val tx = targetX.coerceIn(-.85f, .85f)
        val ty = targetY.coerceIn(-.85f, .85f)
        if (!initialized) {
            initialized = true; filteredX = tx; filteredY = ty
        } else {
            filteredX += (tx - filteredX) * .22f
            filteredY += (ty - filteredY) * .22f
        }
        if (abs(filteredX - x) < .018f && abs(filteredY - y) < .018f) return false
        x = filteredX; y = filteredY
        return true
    }
}
