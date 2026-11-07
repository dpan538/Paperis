package com.inkreader.nativeink

/**
 * Display-referred ambient scenes. These are chosen visual parameters, not measured optics:
 * the panel is emissive, so the paper is simulated by shifting the rendered tone distribution,
 * never by claiming absolute cd/m² or real reflectance. Scene 2 (indoor) is exactly the
 * already-accepted 1.0.1 appearance, so a steady room is a pixel-for-pixel regression.
 */
internal data class AmbientTone(val paper: Float, val ink: Float, val veil: Float) {
    companion object { val NEUTRAL = AmbientTone(0f, 0f, 0f) }
}

internal object OpticalTone {
    const val SCENES = 6
    const val INDOOR = 2
    /** Upper lux bound for each scene, in the same order as [tone]. */
    private val bands = floatArrayOf(10f, 50f, 300f, 1_000f, 10_000f)

    fun sceneForLux(lux: Float): Int {
        if (!lux.isFinite()) return INDOOR
        for (i in bands.indices) if (lux < bands[i]) return i
        return SCENES - 1
    }

    /**
     * Encoded-space deltas applied to the light material (dark mode is left alone):
     * [paper] shifts the substrate, [ink] the pigment floor, [veil] lifts solid ink slightly
     * in bright scenes as a bounded surface-reflection cue.
     */
    fun tone(scene: Int): AmbientTone = when (scene.coerceIn(0, SCENES - 1)) {
        // Darkening stays gentle: paper is dim in a dark room, not switched to a dark theme.
        0 -> AmbientTone(-0.075f, 0.010f, 0f)
        1 -> AmbientTone(-0.038f, 0.005f, 0f)
        INDOOR -> AmbientTone.NEUTRAL
        3 -> AmbientTone(0.022f, -0.002f, 0.002f)
        4 -> AmbientTone(0.040f, -0.003f, 0.005f)
        else -> AmbientTone(0.055f, -0.005f, 0.008f)
    }

    fun mix(from: AmbientTone, to: AmbientTone, fraction: Float): AmbientTone {
        val t = fraction.coerceIn(0f, 1f)
        return AmbientTone(
            from.paper + (to.paper - from.paper) * t,
            from.ink + (to.ink - from.ink) * t,
            from.veil + (to.veil - from.veil) * t
        )
    }

    fun name(scene: Int): String = when (scene.coerceIn(0, SCENES - 1)) {
        0 -> "dark"; 1 -> "dim"; INDOOR -> "indoor"; 3 -> "bright"; 4 -> "daylight"; else -> "sun"
    }
}
