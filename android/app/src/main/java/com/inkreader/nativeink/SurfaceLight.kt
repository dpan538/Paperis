package com.inkreader.nativeink

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.sqrt
import kotlin.math.atan2
import kotlin.math.PI

/** Real orientation input changes only the optical material; it never drives reading/navigation. */
internal class SurfaceLight(context: Context, private val rotation: () -> Int, private val changed: () -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val pose = SurfacePose()
    val sensorName get() = sensor?.name ?: "unavailable"
    var samples = 0; private set
    var running = false; private set
    private var referenceRoll = Float.NaN
    private var referencePitch = Float.NaN
    private var referenceRotation = -1

    fun start() {
        if (!running) sensor?.let {
            referenceRoll = Float.NaN; referencePitch = Float.NaN; referenceRotation = -1
            if (pose.reset()) changed()
            running = manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }
    fun stop() { manager.unregisterListener(this); running = false }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        samples++
        val v = event.values
        val magnitude = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        // Ignore freefall and short high-acceleration impulses rather than flashing the light.
        if (magnitude !in 5f..15f) return
        val x = v[0] / magnitude; val y = v[1] / magnitude
        val sx: Float; val sy: Float
        val displayRotation = rotation()
        when (displayRotation) {
            Surface.ROTATION_90 -> { sx = -y; sy = x }
            Surface.ROTATION_180 -> { sx = -x; sy = -y }
            Surface.ROTATION_270 -> { sx = y; sy = -x }
            else -> { sx = x; sy = y }
        }
        // Calibrate to the current reading posture. Raw gravity components saturate when
        // held upright, which would otherwise hide small forward/backward tilts.
        val z = v[2] / magnitude
        val roll = atan2(sx, sqrt(sy * sy + z * z))
        val pitch = atan2(sy, z)
        if (!referenceRoll.isFinite() || referenceRotation != displayRotation) {
            referenceRoll = roll; referencePitch = pitch; referenceRotation = displayRotation
            if (pose.reset()) changed()
            return
        }
        fun relative(value: Float, reference: Float): Float {
            var delta = value - reference
            if (delta > PI) delta -= (2 * PI).toFloat()
            if (delta < -PI) delta += (2 * PI).toFloat()
            return delta / 1.05f
        }
        if (pose.offer(relative(roll, referenceRoll), relative(pitch, referencePitch))) changed()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
