package com.longdev.xiaoling.agent

import com.longdev.xiaoling.device.DeviceBounds
import com.longdev.xiaoling.device.DeviceNodeAction
import com.longdev.xiaoling.device.DeviceSnapshot
import com.longdev.xiaoling.device.DeviceSnapshotNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherObservationPolicyTest {
    @Test
    fun readsUniqueCurrentTemperatureAndConditionWithoutLocationText() {
        val result = WeatherObservationPolicy.read(
            snapshot(
                nodes = listOf(
                    node(0, text = "当前天气"),
                    node(1, text = "27°"),
                    node(2, text = "晴"),
                    node(3, text = "上海"),
                ),
            ),
        )

        assertTrue(result is WeatherObservationReadResult.Success)
        val observation = (result as WeatherObservationReadResult.Success).observation
        assertEquals("27°", observation.currentTemperature)
        assertEquals("晴", observation.condition)
        assertTrue(WeatherObservationPolicy.encode(observation).contains("current_temperature"))
        assertTrue(!WeatherObservationPolicy.encode(observation).contains("上海"))
    }

    @Test
    fun refusesAmbiguousTemperatureInsteadOfGuessingForecast() {
        val result = WeatherObservationPolicy.read(
            snapshot(
                nodes = listOf(
                    node(0, text = "27°"),
                    node(1, text = "最高 30° 最低 22°"),
                    node(2, text = "晴"),
                ),
            ),
        )

        assertEquals(
            WeatherObservationReadResult.Incomplete("当前温度无法唯一识别"),
            result,
        )
    }

    @Test
    fun refusesNonWeatherForegroundPackage() {
        val result = WeatherObservationPolicy.read(snapshot(packageName = "com.android.deskclock"))

        assertEquals(WeatherObservationReadResult.WrongForegroundPackage, result)
    }

    private fun snapshot(
        packageName: String = WeatherObservationPolicy.WEATHER_PACKAGE,
        nodes: List<DeviceSnapshotNode> = emptyList(),
    ) = DeviceSnapshot(
        snapshotId = "device-snapshot-weather-test",
        packageName = packageName,
        windowTitle = "Weather",
        windowId = 42,
        windowGeneration = 7,
        capturedAt = 1_000L,
        expiresAt = 31_000L,
        nodes = nodes,
        redactedNodeCount = 0,
        truncated = false,
    )

    private fun node(index: Int, text: String) = DeviceSnapshotNode(
        index = index,
        parentIndex = null,
        depth = 0,
        role = "text",
        text = text,
        description = null,
        hint = null,
        bounds = DeviceBounds(0, 0, 100, 100),
        enabled = true,
        checked = null,
        selected = false,
        redacted = false,
        ref = null,
        actions = setOf(DeviceNodeAction.TAP),
    )
}
