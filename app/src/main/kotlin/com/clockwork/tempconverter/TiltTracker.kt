package com.clockwork.tempconverter

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

class TiltTracker(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _tilt = MutableStateFlow(Pair(0f, 0f))
    val tilt: StateFlow<Pair<Float, Float>> = _tilt

    private var filterX = 0f
    private var filterY = 0f
    private val alpha = 0.12f // Smooth low-pass coefficient

    fun start() {
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(this, rotationVectorSensor, SensorManager.SENSOR_DELAY_GAME)
        } else if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        var rawX = 0f
        var rawY = 0f

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientation)
            
            // orientation[2] is roll, orientation[1] is pitch
            rawX = -orientation[2] / (Math.PI.toFloat() / 3f) // Scale range to approx [-1.5, 1.5]
            rawY = orientation[1] / (Math.PI.toFloat() / 3f)
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            
            val norm = sqrt(ax * ax + ay * ay + az * az)
            if (norm > 0.1f) {
                rawX = -ax / 7f
                rawY = ay / 7f
            }
        }

        // Bound values
        rawX = rawX.coerceIn(-1.5f, 1.5f)
        rawY = rawY.coerceIn(-1.5f, 1.5f)

        // Exponential moving average filter
        filterX = filterX * (1f - alpha) + rawX * alpha
        filterY = filterY * (1f - alpha) + rawY * alpha

        _tilt.value = Pair(filterX, filterY)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }
}
