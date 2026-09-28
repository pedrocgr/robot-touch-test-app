package com.pcgr.robottouchtest

import android.content.Context
import android.graphics.Color
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

data class TestConfig(
    val targetCount: Int = 3,
    val minRadiusDp: Float = 24f,
    val maxRadiusDp: Float = 48f,
    val randomPosition: Boolean = false,
    val randomSize: Boolean = false,
) {
    val triosPerSpawn get() = targetCount / 3
    val modeLabel get() = when {
        randomPosition && randomSize -> "Random positions and sizes"
        randomPosition -> "Random target positions"
        randomSize -> "Fixed positions with random sizes"
        else -> "Fixed target positions"
    }

    fun error(): String? = when {
        targetCount < 3 || targetCount % 3 != 0 -> "RGB trios per spawn must be at least 1."
        targetCount > 300 -> "RGB trios per spawn cannot exceed 100."
        !minRadiusDp.isFinite() || !maxRadiusDp.isFinite() -> "Target radii must be finite numbers."
        minRadiusDp < 8 -> "Minimum target radius must be at least 8 dp."
        maxRadiusDp < minRadiusDp -> "Maximum target radius must be greater than or equal to the minimum."
        else -> null
    }

    fun toJson() = JSONObject().apply {
        put("test_mode", modeLabel)
        put("rgb_trios_per_spawn", triosPerSpawn)
        put("target_count", targetCount)
        put("minimum_radius_dp", minRadiusDp)
        put("maximum_radius_dp", maxRadiusDp)
        put("random_position", randomPosition)
        put("random_target_size", randomSize)
    }

    companion object {
        fun fromJson(json: JSONObject): TestConfig {
            val oldMode = json.optString("test_mode")
            return TestConfig(
                targetCount = if (json.has("rgb_trios_per_spawn")) json.optInt("rgb_trios_per_spawn", 1) * 3 else json.optInt("target_count", 3),
                minRadiusDp = json.optDouble("minimum_radius_dp", 24.0).toFloat(),
                maxRadiusDp = json.optDouble("maximum_radius_dp", 48.0).toFloat(),
                randomPosition = json.optBoolean("random_position") || oldMode == "RANDOM_POSITION",
                randomSize = json.optBoolean("random_target_size") || oldMode == "RANDOM_SIZE",
            )
        }
    }
}

class ConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences("robot_touch_settings", Context.MODE_PRIVATE)

    fun load(): TestConfig = runCatching {
        TestConfig.fromJson(JSONObject(preferences.getString("configuration", "{}")!!))
    }.getOrDefault(TestConfig())

    fun save(config: TestConfig) = preferences.edit().putString("configuration", config.toJson().toString()).commit()
}

data class CircleTarget(
    val id: String,
    val x: Float,
    val y: Float,
    val radius: Float,
    val radiusDp: Float,
    val color: Int,
)

object TouchTestLogic {
    private val colors = intArrayOf(
        Color.rgb(229, 57, 53), Color.rgb(67, 160, 71), Color.rgb(30, 136, 229),
    )
    private val colorNames = arrayOf("red", "green", "blue")

    fun generateTargets(
        config: TestConfig,
        width: Int,
        height: Int,
        density: Float,
        top: Float,
        bottom: Float,
        random: Random = Random.Default,
    ): List<CircleTarget> {
        require(config.error() == null) { config.error()!! }
        val availableHeight = bottom - top
        val columns = if (config.randomPosition) {
            kotlin.math.ceil(kotlin.math.sqrt(config.targetCount * width / availableHeight)).toInt().coerceIn(1, config.targetCount)
        } else 3
        val rows = if (config.randomPosition) kotlin.math.ceil(config.targetCount.toFloat() / columns).toInt() else config.triosPerSpawn
        val cellWidth = width.toFloat() / columns
        val cellHeight = availableHeight / rows
        require(config.maxRadiusDp * density * 2 <= min(cellWidth, cellHeight) * .84f) {
            "Targets do not fit. Use fewer RGB trios or a smaller maximum radius."
        }
        val cells = (0 until config.targetCount).toList().let { if (config.randomPosition) it.shuffled(random) else it }
        return List(config.targetCount) { index ->
            val radiusDp = if (config.randomSize) {
                random.nextFloat() * (config.maxRadiusDp - config.minRadiusDp) + config.minRadiusDp
            } else config.maxRadiusDp
            val radius = radiusDp * density
            val cell = cells[index]
            val column = cell % columns
            val row = cell / columns
            val centerX = (column + .5f) * cellWidth
            val centerY = top + (row + .5f) * cellHeight
            val position = if (config.randomPosition) {
                centerX + (random.nextFloat() * 2 - 1) * (cellWidth / 2 - radius) to
                    centerY + (random.nextFloat() * 2 - 1) * (cellHeight / 2 - radius)
            } else centerX to centerY
            CircleTarget(
                "trio-${(index / 3 + 1).toString().padStart(3, '0')}-${colorNames[index % 3]}",
                position.first, position.second, radius, radiusDp, colors[index % 3],
            )
        }
    }

    fun distance(target: CircleTarget, x: Float, y: Float) = hypot(x - target.x, y - target.y)
    fun isHit(target: CircleTarget, x: Float, y: Float) = distance(target, x, y) <= target.radius

    fun selfCheck() {
        check(TestConfig(targetCount = 0).error() != null)
        check(TestConfig(minRadiusDp = 30f, maxRadiusDp = 20f).error() != null)
        val config = TestConfig(targetCount = 12, minRadiusDp = 8f, maxRadiusDp = 20f, randomPosition = true, randomSize = true)
        val targets = generateTargets(config, 600, 800, 1f, 60f, 780f, Random(7))
        check(targets.all { it.radius in 8f..20f && it.x - it.radius >= 0 && it.x + it.radius <= 600 && it.y - it.radius >= 60 && it.y + it.radius <= 780 })
        check(targets.indices.all { a -> (a + 1 until targets.size).all { b -> distance(targets[a], targets[b].x, targets[b].y) >= targets[a].radius + targets[b].radius } })
        check(isHit(targets.first(), targets.first().x, targets.first().y))
        check(!isHit(targets.first(), targets.first().x + targets.first().radius + 1, targets.first().y))
    }
}

class TouchDataStore(context: Context) {
    val file = File(context.filesDir, "touch_results.jsonl")

    fun append(record: JSONObject) {
        FileOutputStream(file, true).use { output ->
            output.write((record.toString() + "\n").toByteArray())
            output.fd.sync()
        }
    }

    fun records(): List<JSONObject> = if (!file.exists()) emptyList() else
        file.useLines { lines -> lines.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }.toList() }

    fun deleteSessions(sessionIds: Set<String>) = rewrite(records().filterNot { it.optString("session_id") in sessionIds })
    fun clear() = rewrite(emptyList())
    fun exportJson() = JSONArray(records()).toString(2)

    fun exportCsv(): String {
        val fields = listOf(
            "event_id", "session_id", "test_id", "date_time", "test_mode", "screen_width", "screen_height",
            "target_id", "target_center_x", "target_center_y", "target_radius", "target_radius_dp", "target_diameter", "target_color", "actual_touch_x",
            "actual_touch_y", "inside_target", "result", "distance_to_center", "response_time_ms",
            "previous_attempts_in_session", "timed_out", "configuration",
        )
        fun csv(value: Any?): String = "\"${(value ?: "").toString().replace("\"", "\"\"")}\""
        return buildString {
            appendLine(fields.joinToString(","))
            records().forEach { row -> appendLine(fields.joinToString(",") { csv(if (row.isNull(it)) "" else row.opt(it)) }) }
        }
    }

    fun importJson(text: String): Int {
        val parsed = if (text.trimStart().startsWith("[")) {
            val array = JSONArray(text)
            List(array.length()) { array.getJSONObject(it) }
        } else text.lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
        val eventIds = records().mapTo(mutableSetOf()) { it.optString("event_id") }
        val additions = parsed.filter {
            it.has("session_id") && it.has("target_id") && eventIds.add(it.optString("event_id", UUID.randomUUID().toString()))
        }
        additions.forEach(::append)
        return additions.size
    }

    private fun rewrite(records: List<JSONObject>) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            records.forEach { output.write((it.toString() + "\n").toByteArray()) }
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    companion object {
        fun newEventId() = UUID.randomUUID().toString()
        fun now() = Instant.now().toString()
    }
}
