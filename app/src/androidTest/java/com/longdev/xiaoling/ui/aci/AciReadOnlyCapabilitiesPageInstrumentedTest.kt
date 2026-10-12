package com.longdev.xiaoling.ui.aci

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.longdev.xiaoling.agent.AgentPluginManifest
import com.longdev.xiaoling.agent.AgentPluginPermission
import com.longdev.xiaoling.agent.InstalledAgentPlugin
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

    @Test
    fun pluginSectionShowsOnlyStaticReadOnlyIntersectionWithoutGrantingExecution() {
        val tools = listOf(
            ToolDefinition("app.current_time", "读取当前时间", ToolRisk.SAFE),
            ToolDefinition("app.get_info", "应用信息", ToolRisk.REQUIRES_APPROVAL),
            ToolDefinition("mcp.call", "MCP 工具", ToolRisk.SAFE),
        )
        val enabled = InstalledAgentPlugin(
            AgentPluginManifest(
                id = "demo",
                version = "1.0.0",
                name = "演示插件",
                description = "只读声明",
                publisher = "long",
                capabilities = setOf("app.current_time", "app.get_info", "mcp.call", "unknown.capability"),
                permissions = setOf(AgentPluginPermission.READ_ONLY_CAPABILITIES, AgentPluginPermission.MCP),
            ),
            enabled = true,
        )

        composeRule.setContent {
            MaterialTheme {
                AciReadOnlyCapabilitiesPage(
                    selectedProfileName = "执行",
                    registeredTools = tools,
                    allowedToolNames = setOf("app.current_time", "app.get_info", "mcp.call"),
                    onBack = {},
                    installedPlugins = listOf(enabled),
                )
            }
        }

        composeRule.onNodeWithTag("aci-plugin-demo").assertExists()
        composeRule.onNodeWithText("符合只读交集：app.current_time").assertExists()
        composeRule.onNodeWithText("仅保留声明：app.get_info, mcp.call, unknown.capability").assertExists()
        composeRule.onNodeWithText("以下仅是声明与当前 Profile、已注册 SAFE 工具的静态交集预览；不会安装执行器、扩充工具目录或授权 ACI、MCP、终端及设备动作。")
            .assertExists()
    }

    @Test
    fun corruptedPluginStateIsShownAsUnavailableInsteadOfEmptyInventory() {
        composeRule.setContent {
            MaterialTheme {
                AciReadOnlyCapabilitiesPage(
                    selectedProfileName = null,
                    registeredTools = emptyList(),
                    allowedToolNames = emptySet(),
                    onBack = {},
                    pluginStateUnavailable = true,
                )
            }
        }

        composeRule.onNodeWithTag("aci-plugin-state-unavailable").assertExists()
        composeRule.onNodeWithTag("aci-plugin-empty").assertDoesNotExist()
    }
}
