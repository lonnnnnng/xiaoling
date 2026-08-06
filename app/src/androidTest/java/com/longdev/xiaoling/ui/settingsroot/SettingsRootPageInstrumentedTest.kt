package com.longdev.xiaoling.ui.settingsroot

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.longdev.xiaoling.model.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsRootPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun summariesAndEntryClicksStayBehindNarrowStateAndActions() {
        val actions = FakeSettingsRootActions()
        composeRule.setContent {
            MaterialTheme {
                SettingsRootPage(
                    state = populatedState(),
                    actions = actions,
                )
            }
        }

        listOf(
            "已配置 2 个提供方 · 可对话模型 3 个",
            "当前：执行 · 未配置模型 · 2 个 Profile",
            "已开启；仅匹配冻结 Judge 身份的前台 /agent 答案会异步观测",
            "2 个启用 · 2 个本地",
            "1 个启用 · 1 个运行中",
            "最近 3 条 · 2 条已完成",
            "已记录 4 条 · 不关联 Agent Run 或工作流",
        ).forEach { summary ->
            composeRule.onNodeWithText(summary).performScrollTo().assertExists()
        }

        listOf(
            "模型提供方管理",
            "网络请求",
            "提示词设置",
            "Agent Profiles",
            "设备 Agent",
            "日历访问",
            "答案可回答性 Shadow",
            "长期记忆",
            "本地笔记",
            "知识库",
            "相关性灰度控制面",
            "Agent Skills",
            "工作流",
            "Agent 任务中心",
            "进程退出观察",
            "数据备份与恢复",
        ).forEach { title ->
            composeRule.onNodeWithText(title).performScrollTo().performClick()
        }
        composeRule.onNodeWithContentDescription("恢复备份").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    "providers",
                    "network",
                    "prompts",
                    "agent-profiles",
                    "device-agent",
                    "calendar-access",
                    "answerability-shadow",
                    "memories",
                    "local-notes",
                    "knowledge",
                    "knowledge-relevance",
                    "skills",
                    "workflows",
                    "agent-runs",
                    "process-exits",
                    "export-backup",
                    "import-backup",
                ),
                actions.events,
            )
        }
    }

    @Test
    fun headerStaysVisibleWhenSettingsEntriesScrollToTheEnd() {
        composeRule.setContent {
            MaterialTheme {
                SettingsRootPage(
                    state = populatedState(),
                    actions = FakeSettingsRootActions(),
                )
            }
        }

        composeRule.onNodeWithText("数据备份与恢复").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("切换主题").assertIsDisplayed()
    }

    @Test
    fun themeSelectionDelegatesToActions() {
        val actions = FakeSettingsRootActions()
        composeRule.setContent {
            MaterialTheme {
                SettingsRootPage(state = populatedState(), actions = actions)
            }
        }

        composeRule.onNodeWithContentDescription("切换主题").performClick()
        composeRule.onNodeWithText("深色").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("theme:DARK"), actions.events)
        }
    }

    @Test
    fun emptyCollectionsKeepTheOriginalGuidanceSummaries() {
        val actions = FakeSettingsRootActions()
        composeRule.setContent {
            MaterialTheme {
                SettingsRootPage(
                    state = populatedState().copy(
                        providerCount = 0,
                        chatModelCount = 0,
                        selectedAgentProfileName = null,
                        selectedAgentProfileModel = null,
                        agentProfileCount = 0,
                        answerabilityShadowEnabled = false,
                        skillCount = 0,
                        enabledSkillCount = 0,
                        localSkillCount = 0,
                        workflowCount = 0,
                        enabledWorkflowCount = 0,
                        runningWorkflowCount = 0,
                        agentRunCount = 0,
                        completedAgentRunCount = 0,
                        processExitObservationCount = 0,
                    ),
                    actions = actions,
                )
            }
        }

        listOf(
            "还没有模型提供方",
            "配置 Agent 身份、模型、工具、Skill 和记忆边界",
            "默认关闭；答案保存后异步生成只读观察提示",
            "管理内置与本地 Skill",
            "保存可重复的 Agent 目标并查看执行记录",
            "查看最近 Agent Run 的步骤、审批和事件",
            "只读查看最近 30 条 Android 系统退出证据",
        ).forEach { summary ->
            composeRule.onNodeWithText(summary).performScrollTo().assertExists()
        }
    }

    @Test
    fun backupBusyDisablesIconActionsButKeepsCardExportBehavior() {
        val actions = FakeSettingsRootActions()
        composeRule.setContent {
            MaterialTheme {
                SettingsRootPage(
                    state = populatedState().copy(backupBusy = true),
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithText("正在处理备份...").performScrollTo().assertExists()
        composeRule.onNodeWithContentDescription("导出备份").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("恢复备份").assertIsNotEnabled()
        composeRule.onNodeWithText("数据备份与恢复").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("export-backup"), actions.events)
        }
    }

    private fun populatedState() = SettingsRootUiState(
        themeMode = AppThemeMode.SYSTEM,
        providerCount = 2,
        chatModelCount = 3,
        selectedAgentProfileName = "执行",
        selectedAgentProfileModel = "",
        agentProfileCount = 2,
        answerabilityShadowEnabled = true,
        skillCount = 3,
        enabledSkillCount = 2,
        localSkillCount = 2,
        workflowCount = 2,
        enabledWorkflowCount = 1,
        runningWorkflowCount = 1,
        agentRunCount = 3,
        completedAgentRunCount = 2,
        processExitObservationCount = 4,
        backupBusy = false,
    )

    private class FakeSettingsRootActions : SettingsRootActions {
        val events = mutableListOf<String>()

        override fun updateThemeMode(value: AppThemeMode) {
            events += "theme:${value.name}"
        }

        override fun openProviderManagement() = events.add("providers").let { Unit }
        override fun openNetworkRequest() = events.add("network").let { Unit }
        override fun openPromptSettings() = events.add("prompts").let { Unit }
        override fun openAgentProfileManagement() = events.add("agent-profiles").let { Unit }
        override fun openDeviceAgent() = events.add("device-agent").let { Unit }
        override fun openCalendarAccess() = events.add("calendar-access").let { Unit }
        override fun openAnswerabilityShadow() = events.add("answerability-shadow").let { Unit }
        override fun openMemoryManagement() = events.add("memories").let { Unit }
        override fun openLocalNoteManagement() = events.add("local-notes").let { Unit }
        override fun openKnowledgeManagement() = events.add("knowledge").let { Unit }
        override fun openKnowledgeRelevanceRollout() = events.add("knowledge-relevance").let { Unit }
        override fun openSkillManagement() = events.add("skills").let { Unit }
        override fun openWorkflowManagement() = events.add("workflows").let { Unit }
        override fun openAgentRunHistory() = events.add("agent-runs").let { Unit }
        override fun openProcessExitObservations() = events.add("process-exits").let { Unit }
        override fun exportBackup() = events.add("export-backup").let { Unit }
        override fun importBackup() = events.add("import-backup").let { Unit }
    }
}
