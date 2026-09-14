package com.pcgr.robottouchtest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min

/**
 * Fixed-layout first version for robot touch validation. All successful touches
 * are persisted locally so a later settings/export screen can expose them.
 */
private enum class Target(val displayName: String, val color: Int) {
    RED("VERMELHA", Color.rgb(229, 57, 53)),
    BLUE("AZUL", Color.rgb(30, 136, 229)),
    GREEN("VERDE", Color.rgb(67, 160, 71)),
}

class RobotTouchTestView(context: Context) : View(context) {

    private data class CircleTarget(val target: Target, val x: Float, val y: Float, val radius: Float)

    private val logStore = TouchLogStore(context)
    private val titlePaint = paint(Color.rgb(24, 33, 45), 26f, Paint.Align.CENTER)
    private val subtitlePaint = paint(Color.rgb(79, 91, 107), 16f, Paint.Align.CENTER)
    private val statusPaint = paint(Color.rgb(21, 101, 192), 19f, Paint.Align.CENTER)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var circles = emptyList<CircleTarget>()
    private var pressedTarget: Target? = null
    private var successTarget: Target? = logStore.lastTarget()

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val radius = min(width * 0.115f, height * 0.105f)
        val centerY = height * 0.54f
        circles = listOf(
            CircleTarget(Target.RED, width * 0.20f, centerY, radius),
            CircleTarget(Target.BLUE, width * 0.50f, centerY, radius),
            CircleTarget(Target.GREEN, width * 0.80f, centerY, radius),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(247, 248, 250))

        statusPaint.color = Color.BLACK
        canvas.drawText("Successful touches: ${logStore.totalSuccesses()}", width / 2f, height * 0.25f, statusPaint)

        circles.forEach { circle ->
            val isPressed = pressedTarget == circle.target
            targetPaint.color = circle.target.color
            targetPaint.alpha = if (isPressed) 170 else 255
            canvas.drawCircle(circle.x, circle.y, circle.radius, targetPaint)
            canvas.drawCircle(circle.x, circle.y, circle.radius, outlinePaint)
        }

        val result = successTarget
        if (result == null) {
            canvas.drawText("Aguardando toque", width / 2f, height * 0.80f, subtitlePaint)
        } else {
            statusPaint.color = result.color
            statusPaint.color = Color.BLACK
            canvas.drawText("SUCESSO: BOLA ${result.displayName}", width / 2f, height * 0.80f, statusPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val target = targetAt(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedTarget = target
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val selected = pressedTarget
                pressedTarget = null
                if (selected != null && selected == target) {
                    registerSuccess(selected, event.x, event.y)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedTarget = null
                invalidate()
                return true
            }
        }
        return true
    }

    private fun targetAt(x: Float, y: Float): Target? = circles.firstOrNull { circle ->
        hypot(x - circle.x, y - circle.y) <= circle.radius
    }?.target

    private fun registerSuccess(target: Target, x: Float, y: Float) {
        successTarget = target
        logStore.record(target, x / width, y / height)
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
        contentDescription = "Sucesso no toque da bola ${target.displayName.lowercase(Locale.ROOT)}"
        invalidate()
    }

    private fun paint(color: Int, textSize: Float, alignment: Paint.Align) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.textSize = textSize * resources.displayMetrics.scaledDensity
        textAlign = alignment
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
    }
}

private class TouchLogStore(context: Context) {
    private val preferences = context.getSharedPreferences("robot_touch_test", Context.MODE_PRIVATE)
    private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.US)

    fun totalSuccesses(): Int = preferences.getInt("total_successes", 0)

    fun lastTarget(): Target? = preferences.getString("last_target", null)?.let { name ->
        runCatching { Target.valueOf(name) }.getOrNull()
    }

    fun record(target: Target, normalizedX: Float, normalizedY: Float) {
        val targetName = target.name
        val events = JSONArray(preferences.getString("events", "[]"))
        events.put(JSONObject().apply {
            put("target", targetName)
            put("x_normalized", normalizedX)
            put("y_normalized", normalizedY)
            put("timestamp", dateFormat.format(Date()))
        })
        while (events.length() > MAX_EVENTS) {
            events.remove(0)
        }
        preferences.edit()
            .putInt("total_successes", totalSuccesses() + 1)
            .putString("last_target", targetName)
            .putString("events", events.toString())
            .apply()
    }

    private companion object {
        const val MAX_EVENTS = 500
    }
}
