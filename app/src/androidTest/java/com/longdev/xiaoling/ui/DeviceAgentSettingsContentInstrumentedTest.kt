package com.longdev.xiaoling.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.longdev.xiaoling.device.DeviceAgentHealthState
import com.longdev.xiaoling.device.DeviceBounds
import com.longdev.xiaoling.device.DeviceNodeAction
import com.longdev.xiaoling.device.DeviceSnapshot
import com.longdev.xiaoling.device.DeviceSnapshotNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DeviceAgentSettingsContentInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun disabledAndReadyStatesExposeOnlyReadOnlyObservationControls() {
        var enabledChange: Boolean? = null
        val state = mutableStateOf(
            DeviceAgentSettingsUiState(
                enabled = false,
                health = DeviceAgentHealthState.AGENT_DISABLED,
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                DeviceAgentSettingsContent(
                    state = state.value,
                    onEnabledChanged = { enabledChange = it },
                    onOpenAccessibilitySettings = {},
                    onRefresh = {},
                    onCaptureSnapshot = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("设备 Agent 已关闭").assertExists()
        composeRule.onNodeWithContentDescription("启用设备 Agent").assertIsOff().performClick()
        composeRule.runOnIdle { assertEquals(true, enabledChange) }
        composeRule.onNodeWithText("读取当前界面").assertIsNotEnabled()
        composeRule.onNodeWithText("点击").assertDoesNotExist()
        composeRule.onNodeWithText("输入文字").assertDoesNotExist()
        composeRule.onNodeWithText("滑动").assertDoesNotExist()

        composeRule.runOnIdle {
            state.value = DeviceAgentSettingsUiState(
                enabled = true,
                health = DeviceAgentHealthState.READY,
            )
        }
        composeRule.onNodeWithText("服务正常，可读取当前界面").assertExists()
        composeRule.onNodeWithContentDescription("启用设备 Agent").assertIsOn()
        composeRule.onNodeWithText("读取当前界面").assertIsEnabled()
    }

    @Test
    fun snapshotPreviewShowsBoundedMetadataAndSanitizedNodes() {
        val snapshot = DeviceSnapshot(
            snapshotId = "snapshot-ui",
            packageName = "com.example.safe",
            windowTitle = "示例页",
            windowId = 2,
            windowGeneration = 9L,
            capturedAt = 1_000L,
            expiresAt = 31_000L,
            nodes = listOf(
                DeviceSnapshotNode(
                    index = 0,
                    parentIndex = null,
                    depth = 0,
                    role = "button",
                    text = "继续",
                    description = null,
                    hint = null,
                    bounds = DeviceBounds(10, 20, 110, 80),
                    enabled = true,
                    checked = null,
                    selected = false,
                    redacted = false,
                    ref = "r1",
                    actions = setOf(DeviceNodeAction.TAP),
                ),
                DeviceSnapshotNode(
                    index = 1,
                    parentIndex = null,
                    depth = 0,
                    role = "text_field",
                    text = null,
                    description = null,
                    hint = null,
                    bounds = DeviceBounds(10, 100, 300, 160),
                    enabled = true,
                    checked = null,
                    selected = false,
                    redacted = true,
                    ref = null,
                    actions = emptySet(),
                ),
            ),
            redactedNodeCount = 1,
            truncated = true,
        )
        composeRule.setContent {
            MaterialTheme {
                DeviceAgentSettingsContent(
                    state = DeviceAgentSettingsUiState(
                        enabled = true,
                        health = DeviceAgentHealthState.READY,
                        snapshot = snapshot,
                    ),
                    onEnabledChanged = {},
                    onOpenAccessibilitySettings = {},
                    onRefresh = {},
                    onCaptureSnapshot = {},
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("com.example.safe").assertExists()
        composeRule.onNodeWithText("2 个节点 · 1 个已脱敏 · 已截断").assertExists()
        composeRule.onNodeWithText("r1 · button · 继续", substring = true).assertExists()
        composeRule.onNodeWithText("已脱敏 · text_field", substring = true).assertExists()
        composeRule.onNodeWithText("secret").assertDoesNotExist()
    }
}
