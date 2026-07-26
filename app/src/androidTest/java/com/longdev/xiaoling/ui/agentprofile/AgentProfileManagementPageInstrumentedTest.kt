package com.longdev.xiaoling.ui.agentprofile

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.longdev.xiaoling.agent.AgentContextPolicy
import com.longdev.xiaoling.agent.AgentProfilePolicy
import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.agent.AgentSkillDefinition
import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AgentProfileManagementPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listRoutesSelectionLocalEditorsDeleteAndBackWithoutConcreteViewModel() {
        val actions = FakeAgentProfileManagementActions()
        var backCount = 0
        composeRule.setContent {
            MaterialTheme {
                AgentProfileManagementPage(
                    state = managementState(),
                    actions = actions,
                    onBack = { backCount += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(agentProfileItemTag("agent-1")).performClick()
        composeRule.onNodeWithContentDescription("新增 Agent Profile").performClick()
        composeRule.onNodeWithText("新增 Agent Profile").assertExists()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.onNodeWithTag(agentProfileEditTag("agent-1")).performClick()
        composeRule.onNodeWithText("编辑 Agent Profile").assertExists()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.onNodeWithTag(agentProfileDeleteTag("agent-1")).performClick()
        composeRule.onNodeWithText("删除 Agent Profile").assertExists()
        composeRule.onNodeWithText("删除").performClick()
        composeRule.onNodeWithContentDescription("返回设置").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("agent-1"), actions.selectedProfileIds)
            assertEquals(listOf("agent-1"), actions.deletedProfileIds)
            assertEquals(1, backCount)
        }
    }

    @Test
    fun editorConstrainsDraftAndKeepsProviderSkillToolDependenciesCoherent() {
        val actions = FakeAgentProfileManagementActions()
        val longName = "N".repeat(AgentProfilePolicy.MAX_NAME_LENGTH + 2)
        val longAvatar = "A".repeat(AgentProfilePolicy.MAX_AVATAR_LENGTH + 2)
        val longPrompt = "P".repeat(AgentProfilePolicy.MAX_SYSTEM_PROMPT_LENGTH + 2)
        composeRule.setContent {
            MaterialTheme {
                AgentProfileManagementPage(
                    state = managementState(),
                    actions = actions,
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("新增 Agent Profile").performClick()
        composeRule.onNodeWithTag(agentProfileNameFieldTag()).performTextInput(longName)
        composeRule.onNodeWithTag(agentProfileAvatarFieldTag()).performTextInput(longAvatar)
        composeRule.onNodeWithTag(agentProfileProviderSelectorTag()).performClick()
        composeRule.onNodeWithText("备用提供方").performClick()
        composeRule.onNodeWithTag(agentProfileApiModeTag(ApiMode.RESPONSES)).performClick()
        composeRule.onNodeWithTag(agentProfileModelSelectorTag()).performClick()
        composeRule.onNodeWithText("model-alt-2").performClick()
        composeRule.onNodeWithTag(agentProfileSystemPromptFieldTag())
            .performScrollTo()
            .performTextInput(longPrompt)
        composeRule.onNodeWithTag(agentProfileMemorySwitchTag())
            .performScrollTo()
            .performClick()

        composeRule.onNodeWithTag(agentProfileToolRowTag("tool.safe"))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(agentProfileToolRowTag("tool.safe")).assertIsOff()
        composeRule.onNodeWithTag(agentProfileSkillRowTag("skill-safe")).assertIsOff()
        composeRule.onNodeWithTag(agentProfileSkillRowTag("skill-safe"))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(agentProfileToolRowTag("tool.safe")).assertIsOn()
        composeRule.onNodeWithTag(agentProfileSkillRowTag("skill-safe")).assertIsOn()

        composeRule.onNodeWithTag(agentProfileSaveTag()).performClick()
        composeRule.onNodeWithText("新增 Agent Profile").assertDoesNotExist()

        composeRule.runOnIdle {
            val draft = actions.savedDrafts.single()
            assertEquals(longName.take(AgentProfilePolicy.MAX_NAME_LENGTH), draft.name)
            assertEquals(longAvatar.take(AgentProfilePolicy.MAX_AVATAR_LENGTH), draft.avatar)
            assertEquals(longPrompt.take(AgentProfilePolicy.MAX_SYSTEM_PROMPT_LENGTH), draft.systemPrompt)
            assertEquals("provider-2", draft.providerId)
            assertEquals("model-alt-2", draft.model)
            assertEquals(ApiMode.RESPONSES, draft.apiMode)
            assertEquals(false, draft.memoryEnabled)
            assertEquals(setOf("tool.safe"), draft.allowedToolNames)
            assertEquals(setOf("skill-safe"), draft.allowedSkillIds)
        }
    }

    @Test
    fun editorKeepsStableProfileIdentityAcrossListReorderAndRecordReplacement() {
        val actions = FakeAgentProfileManagementActions()
        var state by mutableStateOf(managementState())
        composeRule.setContent {
            MaterialTheme {
                AgentProfileManagementPage(
                    state = state,
                    actions = actions,
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(agentProfileEditTag("agent-1")).performClick()
        composeRule.runOnIdle {
            val first = state.profiles.first { it.profile.id == "agent-1" }
            val second = state.profiles.first { it.profile.id == "agent-2" }
            state = state.copy(
                profiles = listOf(
                    second,
                    first.copy(profile = first.profile.copy(name = "替换后的 Agent")),
                ),
            )
        }
        composeRule.onNodeWithTag(agentProfileSaveTag()).performClick()

        composeRule.runOnIdle {
            assertEquals("agent-1", actions.savedDrafts.single().id)
        }
    }

    private fun managementState(): AgentProfileManagementUiState {
        val provider = ProviderProfile.blank("provider-1").copy(
            name = "主提供方",
            enabledModels = listOf("model-1"),
        )
        val alternateProvider = ProviderProfile.blank("provider-2").copy(
            name = "备用提供方",
            enabledModels = listOf("model-alt-1", "model-alt-2"),
        )
        return AgentProfileManagementProjection.project(
            profiles = listOf(profile("agent-1"), profile("agent-2")),
            providers = listOf(provider, alternateProvider),
            selectedProfileId = "agent-2",
            mutatingProfileIds = emptySet(),
            error = null,
            tools = listOf(tool("tool.safe")),
            skills = listOf(skill("skill-safe")),
        )
    }

    private fun profile(id: String) = AgentProfileRecord(
        id = id,
        name = id,
        avatar = "A",
        providerId = "provider-1",
        model = "model-1",
        apiMode = ApiMode.CHAT_COMPLETIONS,
        systemPrompt = "",
        contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
        allowedToolNames = listOf("tool.safe"),
        allowedSkillIds = listOf("skill-safe"),
        memoryEnabled = true,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun tool(name: String) = ToolDefinition(name, name, ToolRisk.SAFE)

    private fun skill(id: String) = AgentSkillRecord(
        definition = AgentSkillDefinition(
            id = id,
            name = id,
            description = id,
            instructions = id,
            toolNames = setOf("tool.safe"),
            keywords = emptySet(),
        ),
        enabled = true,
        importedAt = 1L,
        updatedAt = 1L,
    )

    private class FakeAgentProfileManagementActions : AgentProfileManagementActions {
        val selectedProfileIds = mutableListOf<String>()
        val savedDrafts = mutableListOf<AgentProfileEditDraft>()
        val deletedProfileIds = mutableListOf<String>()

        override fun selectAgentProfile(profileId: String) {
            selectedProfileIds += profileId
        }

        override fun saveAgentProfile(draft: AgentProfileEditDraft) {
            savedDrafts += draft
        }

        override fun deleteAgentProfile(profileId: String) {
            deletedProfileIds += profileId
        }
    }
}
