package com.longdev.xiaoling.ui.agentprofile

import com.longdev.xiaoling.agent.AgentContextPolicy
import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.agent.AgentSkillDefinition
import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProfileManagementProjectionTest {
    @Test
    fun projectBindsSelectionMutationValidityAndEnabledSkillsByStableIds() {
        val provider = ProviderProfile.blank("provider-1").copy(
            name = "主提供方",
            enabledModels = listOf("model-1"),
        )
        val state = AgentProfileManagementProjection.project(
            profiles = listOf(
                profile(id = "agent-2", providerId = "missing-provider", model = "model-2"),
                profile(id = "agent-1", providerId = provider.id, model = "disabled-model"),
            ),
            providers = listOf(provider),
            selectedProfileId = "agent-2",
            mutatingProfileIds = setOf("agent-1"),
            error = "保存失败",
            tools = listOf(tool("tool.safe")),
            skills = listOf(skill("enabled-skill", enabled = true), skill("disabled-skill", enabled = false)),
        )

        assertEquals(listOf("agent-2", "agent-1"), state.profiles.map { it.profile.id })
        assertTrue(state.profiles[0].selected)
        assertFalse(state.profiles[0].mutating)
        assertTrue(state.profiles[0].deleteEnabled)
        assertEquals("Provider 已缺失", state.profiles[0].providerName)
        assertFalse(state.profiles[0].providerModelValid)
        assertFalse(state.profiles[1].selected)
        assertTrue(state.profiles[1].mutating)
        assertFalse(state.profiles[1].deleteEnabled)
        assertEquals("主提供方", state.profiles[1].providerName)
        assertFalse(state.profiles[1].providerModelValid)
        assertEquals("保存失败", state.error)
        assertEquals(listOf("tool.safe"), state.tools.map { it.name })
        assertEquals(listOf("enabled-skill"), state.enabledSkills.map { it.definition.id })
    }

    @Test
    fun createDraftFallsBackProviderAndModelWithoutExpandingExistingCapabilities() {
        val provider = ProviderProfile.blank("provider-1").copy(
            name = "主提供方",
            enabledModels = listOf("model-1"),
        )
        val tools = listOf(tool("tool.safe"), tool("tool.write"))
        val enabledSkill = skill("enabled-skill", enabled = true)
        val state = AgentProfileManagementProjection.project(
            profiles = emptyList(),
            providers = listOf(provider),
            selectedProfileId = "",
            mutatingProfileIds = emptySet(),
            error = null,
            tools = tools,
            skills = listOf(enabledSkill, skill("disabled-skill", enabled = false)),
        )

        val created = AgentProfileManagementProjection.createDraft(profile = null, state = state)
        assertEquals(provider.id, created.providerId)
        assertEquals("model-1", created.model)
        assertEquals(ApiMode.CHAT_COMPLETIONS, created.apiMode)
        assertTrue(created.memoryEnabled)
        assertEquals(setOf("tool.safe", "tool.write"), created.allowedToolNames)
        assertEquals(setOf("enabled-skill"), created.allowedSkillIds)

        val existing = profile(
            id = "agent-existing",
            providerId = "missing-provider",
            model = "missing-model",
        ).copy(
            name = "已有 Agent",
            avatar = "旧",
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "保留提示词",
            allowedToolNames = listOf("tool.safe"),
            allowedSkillIds = listOf("disabled-skill"),
            memoryEnabled = false,
        )
        val edited = AgentProfileManagementProjection.createDraft(profile = existing, state = state)

        assertEquals(existing.id, edited.id)
        assertEquals(existing.name, edited.name)
        assertEquals(existing.avatar, edited.avatar)
        assertEquals(provider.id, edited.providerId)
        assertEquals("model-1", edited.model)
        assertEquals(ApiMode.RESPONSES, edited.apiMode)
        assertEquals("保留提示词", edited.systemPrompt)
        assertFalse(edited.memoryEnabled)
        assertEquals(setOf("tool.safe"), edited.allowedToolNames)
        assertEquals(setOf("disabled-skill"), edited.allowedSkillIds)
    }

    private fun profile(id: String, providerId: String, model: String) = AgentProfileRecord(
        id = id,
        name = id,
        avatar = "A",
        providerId = providerId,
        model = model,
        apiMode = ApiMode.CHAT_COMPLETIONS,
        systemPrompt = "",
        contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
        allowedToolNames = listOf("tool.safe"),
        allowedSkillIds = emptyList(),
        memoryEnabled = true,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun tool(name: String) = ToolDefinition(
        name = name,
        description = name,
        risk = ToolRisk.SAFE,
    )

    private fun skill(id: String, enabled: Boolean) = AgentSkillRecord(
        definition = AgentSkillDefinition(
            id = id,
            name = id,
            description = id,
            instructions = id,
            toolNames = setOf("tool.safe"),
            keywords = emptySet(),
        ),
        enabled = enabled,
        importedAt = 1L,
        updatedAt = 1L,
    )
}
