package com.longdev.xiaoling.agent

import com.longdev.xiaoling.device.DeviceSnapshot
import com.longdev.xiaoling.device.DeviceSnapshotNode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class WeatherCurrentObservation(
    val snapshotId: String,
    val capturedAt: Long,
    val currentTemperature: String,
    val condition: String,
)

sealed interface WeatherObservationReadResult {
    data class Success(val observation: WeatherCurrentObservation) : WeatherObservationReadResult
    data class Incomplete(val reason: String) : WeatherObservationReadResult
    data object WrongForegroundPackage : WeatherObservationReadResult
}

/**
 * long: 天气事实只从当前 Google Weather 的脱敏 Accessibility 快照中提取；无法唯一定位当前温度或状况时宁可返回不完整，也不把预报/位置文案猜成当前事实。
 */
object WeatherObservationPolicy {
    const val WEATHER_PACKAGE = "com.google.android.apps.weather"

    private val TEMPERATURE_PATTERN = Regex(
        "(?<!\\d)-?\\d{1,3}(?:[.,]\\d+)?\\s*(?:°\\s*(?:[CFcf℃℉])?|[℃℉])",
    )
    private val CURRENT_MARKERS = setOf("现在", "当前", "目前", "now", "current")
    private val CONDITION_MARKERS = listOf(
        "雷阵雨",
        "雨夹雪",
        "暴雨",
        "大雨",
        "中雨",
        "小雨",
        "大雪",
        "中雪",
        "小雪",
        "多云",
        "晴间多云",
        "晴",
        "阴",
        "雾",
        "霾",
        "沙尘",
        "partly cloudy",
        "thunderstorm",
        "cloudy",
        "clear",
        "rain",
        "snow",
        "fog",
        "haze",
    )

    fun read(snapshot: DeviceSnapshot): WeatherObservationReadResult {
        if (snapshot.packageName != WEATHER_PACKAGE) {
            return WeatherObservationReadResult.WrongForegroundPackage
        }
        val nodes = snapshot.nodes.filterNot(DeviceSnapshotNode::redacted)
        val temperatureCandidates = nodes.flatMapIndexed { index, node ->
            node.searchableTexts().flatMap { text ->
                TEMPERATURE_PATTERN.findAll(text).map { match ->
                    TemperatureCandidate(
                        value = match.value.replace(Regex("\\s+"), ""),
                        nodeIndex = index,
                    )
                }
            }
        }.distinctBy { it.value to it.nodeIndex }
        val selectedTemperature = selectCurrentTemperature(nodes, temperatureCandidates)
            ?: return WeatherObservationReadResult.Incomplete("当前温度无法唯一识别")
        val conditionCandidates = nodes
            .asSequence()
            .filter { node -> node.index in selectedTemperature.nodeIndex - 5..selectedTemperature.nodeIndex + 5 }
            .flatMap { node -> node.searchableTexts().asSequence() }
            .flatMap { text -> conditionValues(text).asSequence() }
            .distinct()
            .toList()
        val condition = conditionCandidates.singleOrNull()
            ?: return WeatherObservationReadResult.Incomplete("当前天气状况无法唯一识别")
        return WeatherObservationReadResult.Success(
            WeatherCurrentObservation(
                snapshotId = snapshot.snapshotId,
                capturedAt = snapshot.capturedAt,
                currentTemperature = selectedTemperature.value,
                condition = condition,
            ),
        )
    }

    fun encode(observation: WeatherCurrentObservation): String = buildJsonObject {
        put("source_package", WEATHER_PACKAGE)
        put("snapshot_id", observation.snapshotId)
        put("captured_at", observation.capturedAt)
        put("current_temperature", observation.currentTemperature)
        put("condition", observation.condition)
    }.toString()

    private fun selectCurrentTemperature(
        nodes: List<DeviceSnapshotNode>,
        candidates: List<TemperatureCandidate>,
    ): TemperatureCandidate? {
        if (candidates.isEmpty()) return null
        val currentMarkedNodes = nodes.withIndex()
            .filter { (_, node) ->
                node.searchableTexts().any { text ->
                    CURRENT_MARKERS.any { marker -> text.contains(marker, ignoreCase = true) }
                }
            }
            .map { it.index }
        val marked = candidates.filter { candidate ->
            currentMarkedNodes.any { markerIndex -> kotlin.math.abs(markerIndex - candidate.nodeIndex) <= 2 }
        }.distinctBy { it.value }
        return when {
            marked.size == 1 -> marked.single()
            candidates.map { it.value }.distinct().size == 1 -> candidates.first()
            else -> null
        }
    }

    private fun conditionValues(text: String): List<String> {
        val normalized = text.trim()
        if (normalized.isBlank()) return emptyList()
        val matches = CONDITION_MARKERS
            .filter { marker -> normalized.contains(marker, ignoreCase = true) }
        return matches
            .filter { candidate ->
                matches.none { other ->
                    other.length > candidate.length && other.contains(candidate, ignoreCase = true)
                }
            }
            .sortedWith(compareByDescending(String::length).thenBy(String::lowercase))
    }

    private fun DeviceSnapshotNode.searchableTexts(): List<String> = listOfNotNull(text, description, hint)
        .map(String::trim)
        .filter(String::isNotBlank)

    private data class TemperatureCandidate(
        val value: String,
        val nodeIndex: Int,
    )
}
