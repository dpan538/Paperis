package com.inkreader.nativeink

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Ambient light is the medium: the paper is lit by the room, so the rendered substrate adapts to it.
 * Only the material uniforms change; layout, page boundaries and navigation never react to this sensor.
 *
 * The first stable sample is a reference, not a scene change: this keeps the accepted indoor appearance
 * (and test renders) stable on devices that report a constant value, while a real move from a dim room to
 * daylight changes the reference and settles to the mapped scene after a short debounce.
 */
internal class AmbientLight(context: Context, private val changed: (Int) -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val handler = Handler(Looper.getMainLooper())
    private val samples = FloatArray(3)
    private var sampleCount = 0
    private var filtered = Float.NaN
    private var baseline = Float.NaN
    private var lastAt = 0L
    private var calibrated = false
    private var pendingScene = OpticalTone.INDOOR
    private var pendingSince = 0L
    val available get() = sensor != null
    var scene = OpticalTone.INDOOR; private set
    var readings = 0; private set
    var running = false; private set

    private val commit = Runnable {
        if (!running || pendingScene == scene) return@Runnable
        scene = pendingScene
        changed(scene)
    }

    fun start() {
        if (running || sensor == null) return
        sampleCount = 0; filtered = Float.NaN; baseline = Float.NaN; calibrated = false
        pendingScene = scene; pendingSince = 0L; readings = 0
        running = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        if (!running) return
        handler.removeCallbacks(commit)
        manager.unregisterListener(this)
        running = false
    }

    /** Test seam: emulators cannot supply meaningful ambient lux. */
    internal fun setSceneForTest(value: Int) {
        handler.removeCallbacks(commit)
        scene = value.coerceIn(0, OpticalTone.SCENES - 1)
        pendingScene = scene
        changed(scene)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        val lux = event.values.firstOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: return
        readings++
        // Three-sample median removes single-frame flicker, then a time-based EMA (tau ~3 s) damps the rest.
        samples[sampleCount % 3] = lux
        sampleCount++
        val median = if (sampleCount >= 3) samples.copyOf(3).apply { sort() }[1] else lux
        val now = SystemClock.elapsedRealtime()
        if (!filtered.isFinite()) { filtered = median; lastAt = now }
        else {
            val dt = (now - lastAt).coerceIn(1L, 2_000L); lastAt = now
            val alpha = 1f - exp(-dt / 3_000.0).toFloat()
            filtered += (median - filtered) * alpha
        }
        if (!calibrated) {
            baseline = filtered; calibrated = true; pendingSince = now
            return
        }
        // A constant sensor reading must not slowly drift the accepted appearance: only a real change
        // (>=30% in log space) re-arms scene selection.
        val ref = baseline.takeIf { it.isFinite() && it > 0.001f } ?: return
        if (abs(ln((filtered.coerceAtLeast(0.001f)) / ref)) < 0.26f) return
        val next = OpticalTone.sceneForLux(filtered)
        if (next != pendingScene) { pendingScene = next; pendingSince = now }
        handler.removeCallbacks(commit)
        val wait = (2_000L - (now - pendingSince)).coerceAtLeast(0L)
        handler.postDelayed(commit, wait)
    }
}
