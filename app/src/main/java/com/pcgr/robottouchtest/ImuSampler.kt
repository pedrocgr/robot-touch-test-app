package com.pcgr.robottouchtest

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI

/** Keeps the latest phone motion sample so every recorded test event has its pose/motion context. */
class ImuSampler(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var acceleration: FloatArray? = null
    private var gyroscope: FloatArray? = null
    private var rotationVector: FloatArray? = null
    private var sampleTimeNs: Long? = null

    fun start() {
        register(Sensor.TYPE_ACCELEROMETER)
        register(Sensor.TYPE_GYROSCOPE)
        register(Sensor.TYPE_ROTATION_VECTOR)
    }

    fun stop() = manager.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values.copyOf()
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> acceleration = values
            Sensor.TYPE_GYROSCOPE -> gyroscope = values
            Sensor.TYPE_ROTATION_VECTOR -> rotationVector = values
        }
        sampleTimeNs = event.timestamp
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun snapshot(): JSONObject = JSONObject().apply {
        put("sample_time_elapsed_realtime_ns", sampleTimeNs ?: JSONObject.NULL)
        put("accelerometer_mps2", acceleration?.toJson() ?: JSONObject.NULL)
        put("gyroscope_rad_s", gyroscope?.toJson() ?: JSONObject.NULL)
        put("orientation_deg", rotationVector?.let(::orientationDegrees)?.toJson() ?: JSONObject.NULL)
    }

    private fun register(type: Int) {
        manager.getDefaultSensor(type)?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    private fun orientationDegrees(vector: FloatArray): FloatArray {
        val matrix = FloatArray(9)
        val angles = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(matrix, vector)
        SensorManager.getOrientation(matrix, angles)
        return FloatArray(3) { index -> (angles[index] * 180f / PI.toFloat()) }
    }

    private fun FloatArray.toJson() = JSONArray().apply { this@toJson.forEach(::put) }
}
