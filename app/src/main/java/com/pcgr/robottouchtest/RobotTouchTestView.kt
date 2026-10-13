package com.pcgr.robottouchtest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import java.util.UUID

class RobotTouchTestView(
    context: Context,
    private val config: TestConfig,
    private val onFinished: (SessionResult) -> Unit,
    private val onOperatorAction: (label: String, enabled: Boolean) -> Unit,
) : FrameLayout(context) {
    data class SessionResult(
        val sessionId: String,
        val targetCount: Int,
        val hits: Int,
        val misses: Int,
        val averageResponseMs: Long,
    )

    private val store = TouchDataStore(context)
    private val imu = ImuSampler(context)
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
    private var touchAttempts = 0
    private var previousResult = "No previous touch"
    private var finished = false
    private var pressedTarget: CircleTarget? = null
    private data class CaptchaTouch(val target: CircleTarget, val x: Float, val y: Float, val responseMs: Long)
    private var pendingCaptchaTouch: CaptchaTouch? = null
    private var captchaFailed = false

    init {
        isFocusable = true
        contentDescription = "Robot touch test area"
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        imu.start()
    }

    override fun onDetachedFromWindow() {
        imu.stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        if (targets.isNotEmpty() || width == 0 || height == 0) return
        targets = runCatching {
            TouchTestLogic.generateTargets(config, width, height, density, 120 * density, height - 96 * density)
        }.getOrElse { error ->
            previousResult = "Targets do not fit"
            contentDescription = previousResult
            onOperatorAction("", false)
            Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
            return
        }
        if (config.captchaEnabled) {
            previousResult = "Loading reCAPTCHA..."
            onOperatorAction("", false)
            addView(RecaptchaView(context, targets, 120 * density,
                onReady = ::showNext,
                onVerified = { id ->
                    val touch = pendingCaptchaTouch
                    if (touch != null && touch.target.id == id && !captchaFailed) {
                        pendingCaptchaTouch = null
                        complete(touch.target, touch.x, touch.y, noTouch = false, responseMs = touch.responseMs)
                    }
                },
                onError = { message ->
                    captchaFailed = true
                    pendingCaptchaTouch = null
                    previousResult = "reCAPTCHA unavailable"
                    contentDescription = previousResult
                    onOperatorAction("", false)
                    invalidate()
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                },
                blocked = {
                    current == null || pendingCaptchaTouch != null || captchaFailed
                },
                touched = ::captchaTouch,
            ), LayoutParams(LayoutParams.MATCH_PARENT, height - (120 * density).toInt()).apply {
                topMargin = (120 * density).toInt()
            })
        } else showNext()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(247, 248, 250))
        val remaining = targets.size - completedTargetIds.size
        canvas.drawText("Remaining: $remaining of ${targets.size} · Touches: $touchAttempts", width / 2f, 40 * density, textPaint)
        canvas.drawText(previousResult, width / 2f, 72 * density, statusPaint)
        targets.filterNot { it.isSquare }.forEach { circle ->
            targetPaint.color = circle.color
            targetPaint.alpha = if (circle === pressedTarget) 170 else 255
            canvas.drawCircle(circle.x, circle.y, circle.radius, targetPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (config.captchaEnabled) return true
        val target = current ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedTarget = targets
                    .asSequence()
                    .firstOrNull { TouchTestLogic.isHit(it, event.x, event.y) }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                pressedTarget = null
                performClick()
                val touchedTarget = targets
                    .asSequence()
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
        if (pendingCaptchaTouch != null || captchaFailed) return
        endBatch("Batch ended: remaining targets recorded as NO TOUCH")
    }

    private fun endBatch(message: String) {
        if (finished) return
        targets.filter { it.id !in completedTargetIds }.forEach { target ->
            record(target, null, null, "no_touch")
            completedTargetIds += target.id
        }
        previousResult = message
        finishSession()
    }

    private fun showNext() {
        if (config.captchaEnabled) previousResult = if (completedTargetIds.isEmpty()) "Tap the checkbox" else previousResult
        if (completedTargetIds.size >= targets.size) {
            finishSession()
            return
        }
        current = targets.first { it.id !in completedTargetIds }
        appearedAt = SystemClock.elapsedRealtime()
        onOperatorAction("Continue after miss", true)
        invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
    }

    private fun captchaTouch(x: Float, y: Float): Boolean {
        if (current == null || pendingCaptchaTouch != null || captchaFailed) {
            return false
        }
        val target = targets.firstOrNull { it.id !in completedTargetIds && TouchTestLogic.isHit(it, x, y) }
        if (target == null) {
            complete(nearestRemainingTarget(x, y), x, y, noTouch = false)
            return false
        } else {
            pendingCaptchaTouch = CaptchaTouch(target, x, y, SystemClock.elapsedRealtime() - appearedAt)
            onOperatorAction("", false)
            previousResult = "Verifying checkbox..."
            invalidate()
            return true
        }
    }

    private fun complete(target: CircleTarget, touchX: Float?, touchY: Float?, noTouch: Boolean,
        responseMs: Long = SystemClock.elapsedRealtime() - appearedAt) {
        if (finished) return
        val targetAlreadyCompleted = target.id in completedTargetIds
        val distance = if (touchX == null || touchY == null) null else TouchTestLogic.distance(target, touchX, touchY)
        val hit = touchX != null && touchY != null && TouchTestLogic.isHit(target, touchX, touchY)
        val result = when {
            hit -> "hit"
            noTouch -> "no_touch"
            else -> "miss"
        }
        touchAttempts++
        if (hit) {
            previousResult = if (targetAlreadyCompleted) {
                "Previous touch: REPEATED HIT"
            } else {
                "Previous touch: HIT"
            }
            performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        } else {
            previousResult = if (noTouch) "Previous target: NO TOUCH" else "Previous touch: MISS"
        }
        record(target, touchX, touchY, result, targetAlreadyCompleted, responseMs)
        contentDescription = previousResult

        if (hit && !targetAlreadyCompleted) {
            completedTargetIds += target.id
            showNext()
        } else {
            invalidate()
        }
    }

    private fun record(
        target: CircleTarget,
        touchX: Float?,
        touchY: Float?,
        result: String,
        targetAlreadyCompleted: Boolean = false,
        responseMs: Long = SystemClock.elapsedRealtime() - appearedAt,
    ) {
        val distance = if (touchX == null || touchY == null) null else TouchTestLogic.distance(target, touchX, touchY)
        val hit = result == "hit"
        if (hit) hits++ else misses++
        totalResponseMs += responseMs
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
            put("target_shape", if (target.isSquare) "square" else "circle")
            if (target.isSquare) put("captcha_verified", hit)
            put("actual_touch_x", touchX ?: JSONObject.NULL)
            put("actual_touch_y", touchY ?: JSONObject.NULL)
            put("inside_target", hit)
            put("result", result)
            put("target_already_completed", targetAlreadyCompleted)
            put("distance_to_center", distance ?: JSONObject.NULL)
            put("response_time_ms", responseMs)
            put("previous_attempts_in_session", hits + misses - 1)
            put("timed_out", false)
            put("configuration", config.toJson())
            put("imu", imu.snapshot())
        })
    }

    private fun finishSession() {
        if (finished) return
        finished = true
        current = null
        onOperatorAction("", false)
        onFinished(SessionResult(
            sessionId, targets.size, hits, misses,
            if (hits + misses == 0) 0 else totalResponseMs / (hits + misses),
        ))
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
