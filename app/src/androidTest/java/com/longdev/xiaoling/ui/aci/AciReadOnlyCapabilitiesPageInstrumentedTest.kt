package com.longdev.xiaoling.ui.aci

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AciReadOnlyCapabilitiesPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pageShowsOnlyProfileAllowedSafeCapabilitiesAndBackAction() {
        var backCalls = 0
        val tools = listOf(
            ToolDefinition("app.current_time", "读取当前时间", ToolRisk.SAFE),
            ToolDefinition("app.get_battery", "读取电量", ToolRisk.SAFE),
            ToolDefinition("app.get_info", "读取应用信息", ToolRisk.REQUIRES_APPROVAL),
            ToolDefinition("terminal.execute", "执行终端命令", ToolRisk.SAFE),
        )

        composeRule.setContent {
            MaterialTheme {
                AciReadOnlyCapabilitiesPage(
                    selectedProfileName = "执行",
                    registeredTools = tools,
                    allowedToolNames = setOf("app.current_time", "app.get_info", "terminal.execute"),
                    onBack = { backCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithText("执行").assertIsDisplayed()
        composeRule.onNodeWithText("app.current_time").assertIsDisplayed()
        composeRule.onNodeWithTag("aci-capability-app.current_time").assertExists()
        composeRule.onNodeWithText("app.get_battery").assertDoesNotExist()
        composeRule.onNodeWithText("app.get_info").assertDoesNotExist()
        composeRule.onNodeWithText("terminal.execute").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("返回设置").performClick()

        composeRule.runOnIdle { assertEquals(1, backCalls) }
    }

    @Test
    fun emptyProjectionExplainsProfileBoundary() {
        composeRule.setContent {
            MaterialTheme {
                AciReadOnlyCapabilitiesPage(
                    selectedProfileName = null,
                    registeredTools = emptyList(),
                    allowedToolNames = emptySet(),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("aci-empty").assertIsDisplayed()
        composeRule.onNodeWithText("当前 Profile 没有可发现的 ACI 只读能力。请在 Agent Profiles 中检查 SAFE 工具白名单。")
            .assertIsDisplayed()
    }
}
