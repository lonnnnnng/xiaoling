package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentContextPolicy
import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLaunchPreflightCoordinatorTest {
    private val coordinator = AgentLaunchPreflightCoordinator()

    @Test
    fun requiredConversationFailurePrecedesProfileValidation() {
        val outcome = coordinator.evaluate(
            request(
                profileSource = AgentLaunchProfileSource.Selected("missing-profile"),
                conversationRequirement = AgentLaunchConversationRequirement.Existing(
                    conversationId = "missing-conversation",
                    missingMessage = "原会话已不存在",
                ),
            ),
        )

        val rejection = (outcome as AgentLaunchPreflightOutcome.Rejected).reason
        assertTrue(rejection is AgentLaunchPreflightRejection.ConversationMissing)
        assertEquals("原会话已不存在", rejection.message)
    }

    @Test
    fun optionalConversationStillRejectsMissingSelectedProfile() {
        val outcome = coordinator.evaluate(
            request(
                profileSource = AgentLaunchProfileSource.Selected("missing-profile"),
                conversationRequirement = AgentLaunchConversationRequirement.Optional,
            ),
        )

        val rejection = (outcome as AgentLaunchPreflightOutcome.Rejected).reason
        assertEquals(AgentLaunchPreflightRejection.ProfileMissing, rejection)
        assertEquals("请先在设置页创建并选择 Agent Profile", rejection.message)
    }

    @Test
    fun invalidProfileKeepsPolicyMessage() {
        val invalid = profile().copy(model = "")

        val outcome = coordinator.evaluate(
            request(
                profiles = listOf(invalid),
                profileSource = AgentLaunchProfileSource.Selected(invalid.id),
            ),
        )

        val rejection = (outcome as AgentLaunchPreflightOutcome.Rejected).reason
        assertTrue(rejection is AgentLaunchPreflightRejection.InvalidProfile)
        assertEquals("Agent 必须选择模型", rejection.message)
    }

    @Test
    fun unknownToolsAreSortedBeforeRejection() {
        val invalid = profile().copy(allowedToolNames = listOf("tool.z", "tool.safe", "tool.a"))

        val outcome = coordinator.evaluate(
            request(
                profiles = listOf(invalid),
                profileSource = AgentLaunchProfileSource.Selected(invalid.id),
            ),
        )

        val rejection = (outcome as AgentLaunchPreflightOutcome.Rejected).reason
            as AgentLaunchPreflightRejection.UnknownTools
        assertEquals(listOf("tool.a", "tool.z"), rejection.toolNames)
        assertEquals("Agent Profile 包含未注册工具：tool.a, tool.z", rejection.message)
    }

    @Test
    fun providerFailuresRemainTypedAndKeepPolicyMessages() {
        val missing = coordinator.evaluate(request(providers = emptyList()))
        val invalidUrl = coordinator.evaluate(
            request(providers = listOf(provider().copy(baseUrl = "not-a-url"))),
        )
        val disabledModel = coordinator.evaluate(
            request(providers = listOf(provider().copy(enabledModels = listOf("other-model")))),
        )

        val missingReason = (missing as AgentLaunchPreflightOutcome.Rejected).reason
        val invalidUrlReason = (invalidUrl as AgentLaunchPreflightOutcome.Rejected).reason
        val disabledReason = (disabledModel as AgentLaunchPreflightOutcome.Rejected).reason
        assertTrue(missingReason is AgentLaunchPreflightRejection.InvalidProvider)
        assertEquals("Agent Profile 使用的模型提供方已不存在", missingReason.message)
        assertTrue(invalidUrlReason is AgentLaunchPreflightRejection.InvalidProvider)
        assertFalse(invalidUrlReason.message.isBlank())
        assertTrue(disabledReason is AgentLaunchPreflightRejection.InvalidProvider)
        assertEquals("Agent Profile 使用的模型没有在提供方中启用", disabledReason.message)
    }

    @Test
    fun selectedProfileProducesFrozenRuntimeConfigWithoutExistingConversation() {
        val outcome = coordinator.evaluate(
            request(
                providers = listOf(
                    provider().copy(
                        baseUrl = "  https://profile.example/v1  ",
                        apiKey = "  profile-key  ",
                        availableModels = listOf("gpt-test", "embedding-test"),
                        enabledModels = listOf("gpt-test", "embedding-test"),
                    ),
                ),
                userAgent = "launch-test-ua",
                conversationRequirement = AgentLaunchConversationRequirement.Optional,
                existingConversationIds = emptySet(),
            ),
        )

        val ready = outcome as AgentLaunchPreflightOutcome.Ready
        assertEquals(null, ready.conversationId)
        assertEquals("https://profile.example/v1", ready.runtimeSelection.config.baseUrl)
        assertEquals("profile-key", ready.runtimeSelection.config.apiKey)
        assertEquals("provider-test", ready.runtimeSelection.config.providerId)
        assertEquals("gpt-test", ready.runtimeSelection.config.model)
        assertEquals(ApiMode.RESPONSES, ready.runtimeSelection.config.apiMode)
        assertEquals("launch-test-ua", ready.runtimeSelection.config.userAgent)
        assertFalse(ready.runtimeSelection.config.streamingEnabled)
        assertEquals(ProviderProfile.FIXED_MAX_TOKENS, ready.runtimeSelection.config.maxTokens)
        assertEquals("embedding-test", ready.runtimeSelection.config.embeddingModel)
        assertFalse(outcome.toString().contains("profile-key"))
    }

    @Test
    fun historicalSnapshotDoesNotFallBackToCurrentSelectedProfile() {
        val historical = profile(id = "historical-agent", model = "historical-model").snapshot()
        val current = profile(id = "current-agent", model = "current-model")
        val outcome = coordinator.evaluate(
            request(
                profiles = listOf(current),
                providers = listOf(
                    provider().copy(
                        enabledModels = listOf("historical-model", "current-model"),
                    ),
                ),
                profileSource = AgentLaunchProfileSource.forRecoveredRun(
                    sourceProfile = historical,
                    selectedProfileId = current.id,
                ),
            ),
        )

        val ready = outcome as AgentLaunchPreflightOutcome.Ready
        assertEquals("historical-agent", ready.runtimeSelection.profile.id)
        assertEquals("historical-model", ready.runtimeSelection.config.model)
    }

    @Test
    fun recoveredRunWithoutProfileSnapshotFallsBackToCurrentSelectedProfile() {
        val source = AgentLaunchProfileSource.forRecoveredRun(
            sourceProfile = null,
            selectedProfileId = "current-agent",
        )

        assertEquals(AgentLaunchProfileSource.Selected("current-agent"), source)
    }

    @Test
    fun existingConversationIsReturnedWithReadySelection() {
        val outcome = coordinator.evaluate(
            request(
                conversationRequirement = AgentLaunchConversationRequirement.Existing(
                    conversationId = "conversation-1",
                    missingMessage = "会话不存在",
                ),
                existingConversationIds = setOf("conversation-1", "conversation-2"),
            ),
        )

        val ready = outcome as AgentLaunchPreflightOutcome.Ready
        assertEquals("conversation-1", ready.conversationId)
        assertEquals("agent-test", ready.runtimeSelection.profile.id)
    }

    @Test
    fun blankRequiredConversationIsRejectedEvenIfMalformedSnapshotContainsIt() {
        val outcome = coordinator.evaluate(
            request(
                conversationRequirement = AgentLaunchConversationRequirement.Existing(
                    conversationId = "",
                    missingMessage = "请先打开一个会话",
                ),
                existingConversationIds = setOf(""),
            ),
        )

        val rejection = (outcome as AgentLaunchPreflightOutcome.Rejected).reason
        assertTrue(rejection is AgentLaunchPreflightRejection.ConversationMissing)
        assertEquals("请先打开一个会话", rejection.message)
    }

    private fun request(
        profiles: List<AgentProfileRecord> = listOf(profile()),
        providers: List<ProviderProfile> = listOf(provider()),
        profileSource: AgentLaunchProfileSource = AgentLaunchProfileSource.Selected("agent-test"),
        conversationRequirement: AgentLaunchConversationRequirement = AgentLaunchConversationRequirement.Optional,
        existingConversationIds: Set<String> = emptySet(),
        userAgent: String = "test-ua",
    ) = AgentLaunchPreflightRequest(
        profileSource = profileSource,
        agentProfiles = profiles,
        providers = providers,
        registeredToolNames = setOf("tool.safe"),
        userAgent = userAgent,
        conversationRequirement = conversationRequirement,
        existingConversationIds = existingConversationIds,
    )

    private fun profile(
        id: String = "agent-test",
        model: String = "gpt-test",
    ) = AgentProfileRecord(
        id = id,
        name = "测试 Agent",
        avatar = "测",
        providerId = "provider-test",
        model = model,
        apiMode = ApiMode.RESPONSES,
        systemPrompt = "",
        contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
        allowedToolNames = listOf("tool.safe"),
        allowedSkillIds = emptyList(),
        memoryEnabled = true,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun provider() = ProviderProfile.blank("provider-test").copy(
        name = "Profile Provider",
        baseUrl = "https://profile.example/v1",
        apiKey = "profile-key",
        model = "different-global-model",
        availableModels = listOf("gpt-test", "different-global-model"),
        enabledModels = listOf("gpt-test", "different-global-model"),
    )
}
