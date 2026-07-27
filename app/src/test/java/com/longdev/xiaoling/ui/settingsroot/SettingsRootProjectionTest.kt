package com.longdev.xiaoling.ui.settingsroot

import com.longdev.xiaoling.agent.AgentContextPolicy
import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentSkillDefinition
import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.AgentSkillSource
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.automation.WorkflowRecord
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.AppThemeMode
import com.longdev.xiaoling.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRootProjectionTest {
    @Test
    fun projectKeepsOnlySettingsSummariesAndBindsCurrentProfileByStableId() {
        val state = SettingsRootProjection.project(
            themeMode = AppThemeMode.DARK,
            providers = listOf(
                ProviderProfile.blank("provider-1").copy(enabledModels = listOf("model-1", "model-2")),
                ProviderProfile.blank("provider-2").copy(enabledModels = listOf("model-3")),
            ),
            agentProfiles = listOf(
                profile(id = "agent-1", name = "研究", model = "model-1"),
                profile(id = "agent-2", name = "执行", model = ""),
            ),
            selectedAgentProfileId = "agent-2",
            answerabilityShadowEnabled = true,
            skills = listOf(
                skill(id = "built-in", enabled = true, source = AgentSkillSource.BUILT_IN),
                skill(id = "local-enabled", enabled = true, source = AgentSkillSource.LOCAL),
                skill(id = "local-disabled", enabled = false, source = AgentSkillSource.LOCAL),
            ),
            workflows = listOf(workflow(id = "workflow-1", enabled = true), workflow(id = "workflow-2", enabled = false)),
            workflowRunStatuses = listOf(WorkflowRunStatus.RUNNING, WorkflowRunStatus.COMPLETED),
            agentRunStatuses = listOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.COMPLETED),
            processExitObservationCount = 4,
            backupBusy = true,
        )

        assertEquals(AppThemeMode.DARK, state.themeMode)
        assertEquals(2, state.providerCount)
        assertEquals(3, state.chatModelCount)
        assertEquals("执行", state.selectedAgentProfileName)
        assertEquals("", state.selectedAgentProfileModel)
        assertEquals(2, state.agentProfileCount)
        assertTrue(state.answerabilityShadowEnabled)
        assertEquals(3, state.skillCount)
        assertEquals(2, state.enabledSkillCount)
        assertEquals(2, state.localSkillCount)
        assertEquals(2, state.workflowCount)
        assertEquals(1, state.enabledWorkflowCount)
        assertEquals(1, state.runningWorkflowCount)
        assertEquals(3, state.agentRunCount)
        assertEquals(2, state.completedAgentRunCount)
        assertEquals(4, state.processExitObservationCount)
        assertTrue(state.backupBusy)
    }

    private fun profile(id: String, name: String, model: String) = AgentProfileRecord(
        id = id,
        name = name,
        avatar = "A",
        providerId = "provider-1",
        model = model,
        apiMode = ApiMode.CHAT_COMPLETIONS,
        systemPrompt = "",
        contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
        allowedToolNames = emptyList(),
        allowedSkillIds = emptyList(),
        memoryEnabled = true,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun skill(id: String, enabled: Boolean, source: AgentSkillSource) = AgentSkillRecord(
        definition = AgentSkillDefinition(
            id = id,
            name = id,
            description = id,
            instructions = id,
            toolNames = emptySet(),
            keywords = emptySet(),
            declaredRisk = ToolRisk.SAFE,
            source = source,
        ),
        enabled = enabled,
        importedAt = 1L,
        updatedAt = 1L,
    )

    private fun workflow(id: String, enabled: Boolean) = WorkflowRecord(
        id = id,
        name = id,
        goal = id,
        enabled = enabled,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
