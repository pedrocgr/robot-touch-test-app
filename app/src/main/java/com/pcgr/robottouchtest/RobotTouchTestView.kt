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
    private val onOperatorAction: (label: String, enabled: Boolean) -> Unit,
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
    private var targets = emptyList<CircleTarget>()
    private val completedTargetIds = mutableSetOf<String>()
    private var current: CircleTarget? = null
    private var appearedAt = 0L
    private var hits = 0
    private var misses = 0
    private var totalResponseMs = 0L
    private var previousResult = "No previous touch"
    private var finished = false
    private var awaitingManualAdvance = false
    private var pressedTarget: CircleTarget? = null

    init {
        isFocusable = true
        contentDescription = "Robot touch test area"
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        if (targets.isNotEmpty() || width == 0 || height == 0) return
        targets = TouchTestLogic.generateTargets(
            config, width, height, density, 120 * density, height - 96 * density,
        )
        showNext()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(247, 248, 250))
        val target = current
        val progress = if (finished) targets.size else completedTargetIds.size + 1
        canvas.drawText("Target $progress of ${targets.size}", width / 2f, 40 * density, textPaint)
        canvas.drawText(previousResult, width / 2f, 72 * density, statusPaint)
        targets.forEach { circle ->
            targetPaint.color = circle.color
            targetPaint.alpha = if (circle === pressedTarget) 170 else 255
            canvas.drawCircle(circle.x, circle.y, circle.radius, targetPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val target = current ?: return true
        if (awaitingManualAdvance) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedTarget = targets
                    .asSequence()
                    .filter { it.id !in completedTargetIds }
                    .firstOrNull { TouchTestLogic.isHit(it, event.x, event.y) }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                pressedTarget = null
                performClick()
                val touchedTarget = targets
                    .asSequence()
                    .filter { it.id !in completedTargetIds }
                    .firstOrNull { TouchTestLogic.isHit(it, event.x, event.y) }
                complete(touchedTarget ?: nearestRemainingTarget(event.x, event.y), event.x, event.y, noTouch = false)
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

    /** Called by the operator button outside the test surface. */
    fun operatorAdvance() {
        val target = current ?: return
        if (awaitingManualAdvance) {
            awaitingManualAdvance = false
            completedTargetIds += target.id
            showNext()
        } else {
            complete(target, null, null, noTouch = true)
        }
    }

    private fun showNext() {
        if (completedTargetIds.size >= targets.size) {
            finished = true
            current = null
            onOperatorAction("", false)
            onFinished(SessionResult(
                sessionId, targets.size, hits, misses,
                if (hits + misses == 0) 0 else totalResponseMs / (hits + misses),
            ))
            return
        }
        current = targets.first { it.id !in completedTargetIds }
        appearedAt = SystemClock.elapsedRealtime()
        onOperatorAction("No touch / next", true)
        invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
    }

    private fun complete(target: CircleTarget, touchX: Float?, touchY: Float?, noTouch: Boolean) {
        if (target.id in completedTargetIds || finished) return
        val responseMs = SystemClock.elapsedRealtime() - appearedAt
        val distance = if (touchX == null || touchY == null) null else TouchTestLogic.distance(target, touchX, touchY)
        val hit = distance != null && distance <= target.radius
        val result = when {
            hit -> "hit"
            noTouch -> "no_touch"
            else -> "miss"
        }
        if (hit) {
            hits++
            previousResult = "Previous touch: HIT"
            performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        } else {
            misses++
            previousResult = if (noTouch) "Previous target: NO TOUCH" else "Previous touch: MISS"
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
            put("actual_touch_x", touchX ?: JSONObject.NULL)
            put("actual_touch_y", touchY ?: JSONObject.NULL)
            put("inside_target", hit)
            put("result", result)
            put("distance_to_center", distance ?: JSONObject.NULL)
            put("response_time_ms", responseMs)
            put("previous_attempts_in_session", completedTargetIds.size)
            put("timed_out", false)
            put("configuration", config.toJson())
        })

        if (hit || noTouch) {
            completedTargetIds += target.id
            showNext()
        } else {
            current = target
            awaitingManualAdvance = true
            onOperatorAction("Continue after miss", true)
            invalidate()
        }
    }

    private fun nearestRemainingTarget(x: Float, y: Float): CircleTarget =
        targets
            .asSequence()
            .filter { it.id !in completedTargetIds }
            .minBy { TouchTestLogic.distance(it, x, y) }

    private fun paint(color: Int, textSizeSp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = textSizeSp * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
    }
}
