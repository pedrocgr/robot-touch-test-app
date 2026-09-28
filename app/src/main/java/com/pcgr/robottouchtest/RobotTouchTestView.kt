package com.pcgr.robottouchtest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Build
import android.os.Looper
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
    data class SessionResult(val sessionId: String, val hits: Int, val misses: Int, val averageResponseMs: Long)

    private val store = TouchDataStore(context)
    private val density = resources.displayMetrics.density
    private val sessionId = UUID.randomUUID().toString()
    private val testId = UUID.randomUUID().toString()
    private val handler = Handler(Looper.getMainLooper())
    private val textPaint = paint(Color.rgb(24, 33, 45), 18f)
    private val statusPaint = paint(Color.rgb(21, 101, 192), 16f)
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
    }
    private var targets = emptyList<CircleTarget>()
    private var currentIndex = 0
    private var current: CircleTarget? = null
    private var appearedAt = 0L
    private var hits = 0
    private var misses = 0
    private var totalResponseMs = 0L
    private var previousResult = "No previous touch"
    private var finished = false
    private var pressed = false
    private var downTarget: CircleTarget? = null
    private val timeout = Runnable { current?.let { complete(it, null, null, true) } }

    init {
        isFocusable = true
        contentDescription = "Touch test area"
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        if (targets.isNotEmpty() || width == 0 || height == 0) return
        targets = TouchTestLogic.generateTargets(config, width, height, density, 120 * density, height - 24 * density)
        showNext()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(247, 248, 250))
        canvas.drawText("Target ${minOf(currentIndex + 1, config.targetCount)} of ${config.targetCount}", width / 2f, 40 * density, textPaint)
        canvas.drawText(previousResult, width / 2f, 72 * density, statusPaint)
        val target = current
        if (target == null) {
            canvas.drawText("Get ready…", width / 2f, height / 2f, statusPaint)
        } else {
            targetPaint.color = target.color
            targetPaint.alpha = if (pressed) 170 else 255
            canvas.drawCircle(target.x, target.y, target.radius, targetPaint)
            canvas.drawCircle(target.x, target.y, target.radius, outlinePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val target = current ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTarget = target
                pressed = TouchTestLogic.isHit(target, event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                pressed = false
                performClick()
                if (downTarget === target) complete(target, event.x, event.y, false)
                downTarget = null
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                downTarget = null
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun showNext() {
        if (currentIndex >= targets.size) {
            finished = true
            onFinished(SessionResult(sessionId, hits, misses, if (hits + misses == 0) 0 else totalResponseMs / (hits + misses)))
            return
        }
        current = targets[currentIndex]
        appearedAt = SystemClock.elapsedRealtime()
        if (config.timeoutMs > 0) handler.postDelayed(timeout, config.timeoutMs)
        invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
    }

    private fun complete(target: CircleTarget, touchX: Float?, touchY: Float?, timedOut: Boolean) {
        if (target !== current || finished) return
        handler.removeCallbacks(timeout)
        val responseMs = SystemClock.elapsedRealtime() - appearedAt
        val distance = if (touchX == null || touchY == null) null else TouchTestLogic.distance(target, touchX, touchY)
        val hit = distance != null && distance <= target.radius
        if (hit) {
            hits++
            previousResult = "Previous touch: HIT"
            performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        } else {
            misses++
            previousResult = if (timedOut) "Previous target: TIMEOUT" else "Previous touch: MISS"
        }
        totalResponseMs += responseMs
        contentDescription = previousResult
        store.append(JSONObject().apply {
            put("event_id", TouchDataStore.newEventId())
            put("session_id", sessionId)
            put("test_id", testId)
            put("date_time", TouchDataStore.now())
            put("test_mode", config.mode.label)
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
            put("result", if (hit) "hit" else "miss")
            put("distance_to_center", distance ?: JSONObject.NULL)
            put("response_time_ms", responseMs)
            put("previous_attempts_in_session", currentIndex)
            put("timed_out", timedOut)
            put("configuration", config.toJson())
        })
        current = null
        downTarget = null
        currentIndex++
        invalidate()
        handler.postDelayed(::showNext, config.delayMs)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun paint(color: Int, textSizeSp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = textSizeSp * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
    }
}
