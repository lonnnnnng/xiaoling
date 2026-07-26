package com.longdev.xiaoling.ui.agentskill

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.longdev.xiaoling.agent.AgentSkillDefinition
import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.AgentSkillSource
import com.longdev.xiaoling.agent.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AgentSkillManagementPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ownsInitialRefreshAndRoutesStableIdActionsAndPlatformCallbacks() {
        val actions = FakeAgentSkillManagementActions()
        var state by mutableStateOf(AgentSkillManagementUiState())
        var backCount = 0
        composeRule.setContent {
            MaterialTheme {
                AgentSkillManagementPage(
                    state = state,
                    actions = actions,
                    onBack = { backCount += 1 },
                )
            }
        }

        composeRule.runOnIdle {
            assertEquals(1, actions.refreshCount)
            assertEquals(1, actions.auditRefreshCount)
            state = managementState()
        }
        composeRule.onNodeWithTag(agentSkillDeleteTag("built-in")).assertDoesNotExist()
        composeRule.onNodeWithTag(agentSkillToggleTag("local")).performClick()
        composeRule.onNodeWithTag(agentSkillDeleteTag("local")).performClick()
        composeRule.onNodeWithText("导入 JSON").performClick()
        composeRule.onNodeWithContentDescription("刷新 Skill").performClick()
        composeRule.onNodeWithContentDescription("返回设置").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("local" to false), actions.enabledChanges)
            assertEquals(listOf("local"), actions.requestedDeletes)
            assertEquals(2, actions.refreshCount)
            assertEquals(2, actions.auditRefreshCount)
            assertEquals(1, actions.importCount)
            assertEquals(1, backCount)
        }
    }

    @Test
    fun expandedStateFollowsStableSkillIdentityAcrossReorderAndReplacement() {
        var state by mutableStateOf(managementState())
        composeRule.setContent {
            MaterialTheme {
                AgentSkillManagementPage(
                    state = state,
                    actions = FakeAgentSkillManagementActions(),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(agentSkillItemTag("local")).performClick()
        composeRule.onNodeWithText("执行：local instructions").assertExists()
        composeRule.onNodeWithText("依赖工具：app.current_time · 已注册").assertExists()
        composeRule.onNodeWithText("最近 Run：暂无使用记录").assertExists()
        composeRule.runOnIdle {
            val local = state.skills.single { it.skill.definition.id == "local" }
            val builtIn = state.skills.single { it.skill.definition.id == "built-in" }
            state = state.copy(
                skills = listOf(
                    local.copy(skill = local.skill.copy(definition = local.skill.definition.copy(name = "替换后的本地 Skill"))),
                    builtIn,
                ),
            )
        }

        composeRule.onNodeWithText("替换后的本地 Skill").assertExists()
        composeRule.onNodeWithText("执行：local instructions").assertExists()
    }

    private fun managementState(): AgentSkillManagementUiState = AgentSkillManagementProjection.project(
        skills = listOf(
            skill("built-in", AgentSkillSource.BUILT_IN),
            skill("local", AgentSkillSource.LOCAL),
        ),
        loading = false,
        importing = false,
        mutatingSkillIds = emptySet(),
        registeredTools = listOf(
            com.longdev.xiaoling.agent.ToolDefinition(
                name = "app.current_time",
                description = "读取时间",
                risk = ToolRisk.SAFE,
            ),
        ),
        runHistory = emptyList(),
        loadingAudits = false,
        auditError = null,
        error = null,
    )

    private fun skill(id: String, source: AgentSkillSource) = AgentSkillRecord(
        definition = AgentSkillDefinition(
            id = id,
            name = id,
            description = "$id description",
            instructions = "$id instructions",
            toolNames = setOf("app.current_time"),
            keywords = setOf(id),
            declaredRisk = ToolRisk.SAFE,
            source = source,
        ),
        enabled = true,
        importedAt = 1L,
        updatedAt = 1L,
    )

    private class FakeAgentSkillManagementActions : AgentSkillManagementActions {
        var refreshCount = 0
        var auditRefreshCount = 0
        var importCount = 0
        val enabledChanges = mutableListOf<Pair<String, Boolean>>()
        val requestedDeletes = mutableListOf<String>()

        override fun refreshSkills() {
            refreshCount += 1
        }

        override fun refreshSkillAudits() {
            auditRefreshCount += 1
        }

        override fun requestSkillImport() {
            importCount += 1
        }

        override fun setSkillEnabled(skillId: String, enabled: Boolean) {
            enabledChanges += skillId to enabled
        }

        override fun requestLocalSkillDelete(skillId: String) {
            requestedDeletes += skillId
        }
    }
}
