package com.pcgr.robottouchtest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import java.util.UUID

class RobotTouchTestView(
    context: Context,
    private val config: TestConfig,
    private val onFinished: (SessionResult) -> Unit,
) : View(context) {
    data class SessionResult(
        val sessionId: String,
        val targetCount: Int,
        val hits: Int,
        val misses: Int,
        val averageResponseMs: Long,
    )

    private val store = TouchDataStore(context)
    private val density = resources.displayMetrics.density
    private val sessionId = UUID.randomUUID().toString()
    private val testId = UUID.randomUUID().toString()
    private val textPaint = paint(Color.rgb(24, 33, 45), 18f)
    private val statusPaint = paint(Color.rgb(21, 101, 192), 16f)
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
    }
    private var targets = emptyList<CircleTarget>()
    private val remaining = mutableListOf<CircleTarget>()
    private var appearedAt = 0L
    private var attempts = 0
    private var hits = 0
    private var misses = 0
    private var totalResponseMs = 0L
    private var previousResult = "No previous touch"
    private var pressedTarget: CircleTarget? = null
    private var finished = false

    init {
        isFocusable = true
        contentDescription = "Touch test area"
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        if (targets.isNotEmpty() || width == 0 || height == 0) return
        targets = TouchTestLogic.generateTargets(config, width, height, density, 136 * density, height - 24 * density)
        remaining += targets
        appearedAt = SystemClock.elapsedRealtime()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(247, 248, 250))
        canvas.drawText("Targets remaining: ${remaining.size} of ${targets.size}", width / 2f, 56 * density, textPaint)
        canvas.drawText(previousResult, width / 2f, 88 * density, statusPaint)
        remaining.forEach { target ->
            targetPaint.color = target.color
            targetPaint.alpha = if (pressedTarget === target) 170 else 255
            canvas.drawCircle(target.x, target.y, target.radius, targetPaint)
            canvas.drawCircle(target.x, target.y, target.radius, outlinePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (remaining.isEmpty() || finished) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedTarget = targetAt(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val target = remaining.minByOrNull { TouchTestLogic.distance(it, event.x, event.y) } ?: return true
                val hit = pressedTarget === target && TouchTestLogic.isHit(target, event.x, event.y)
                pressedTarget = null
                performClick()
                record(target, event.x, event.y, hit)
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedTarget = null
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun targetAt(x: Float, y: Float) = remaining
        .filter { TouchTestLogic.isHit(it, x, y) }
        .minByOrNull { TouchTestLogic.distance(it, x, y) }

    private fun record(target: CircleTarget, touchX: Float, touchY: Float, hit: Boolean) {
        val responseMs = SystemClock.elapsedRealtime() - appearedAt
        val distance = TouchTestLogic.distance(target, touchX, touchY)
        if (hit) {
            hits++
            remaining.remove(target)
            previousResult = "Previous touch: HIT"
            performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        } else {
            misses++
            previousResult = "Previous touch: MISS"
        }
        totalResponseMs += responseMs
        contentDescription = previousResult
        store.append(JSONObject().apply {
            put("event_id", TouchDataStore.newEventId())
            put("session_id", sessionId)
            put("test_id", testId)
            put("date_time", TouchDataStore.now())
            put("test_mode", config.modeLabel)
            put("screen_width", width)
            put("screen_height", height)
            put("target_id", target.id)
            put("target_center_x", target.x)
            put("target_center_y", target.y)
            put("target_radius", target.radius)
            put("target_radius_dp", target.radiusDp)
            put("target_diameter", target.radius * 2)
            put("target_color", String.format("#%08X", target.color))
            put("actual_touch_x", touchX)
            put("actual_touch_y", touchY)
            put("inside_target", hit)
            put("result", if (hit) "hit" else "miss")
            put("distance_to_center", distance)
            put("response_time_ms", responseMs)
            put("previous_attempts_in_session", attempts)
            put("timed_out", false)
            put("configuration", config.toJson())
        })
        attempts++
        invalidate()
        if (remaining.isEmpty()) {
            finished = true
            onFinished(SessionResult(sessionId, targets.size, hits, misses, totalResponseMs / attempts))
        }
    }

    private fun paint(color: Int, textSizeSp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = textSizeSp * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
    }
}
