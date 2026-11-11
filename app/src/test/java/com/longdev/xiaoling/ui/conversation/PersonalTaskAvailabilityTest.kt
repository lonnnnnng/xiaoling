package com.longdev.xiaoling.ui.conversation

import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.device.DeviceAgentHealthState
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.ui.*
import org.junit.Assert.*
import org.junit.Test

class PersonalTaskAvailabilityTest {
    private val template = personalTaskTemplates.single { it.id == "calculator" }

    @Test fun registeredEquivalentCalculatorAndHealthyServiceAreReady() {
        assertEquals(PersonalTaskAvailabilityStatus.READY, evaluate(environment()).status)
    }

    @Test fun missingAndTruncatedTargetDirectoriesHaveDifferentRecoveryMeaning() {
        assertEquals(PersonalTaskAvailabilityStatus.UNSUPPORTED, evaluate(environment(packages = emptyList())).status)
        assertEquals(PersonalTaskAvailabilityStatus.UNCONFIRMED, evaluate(environment(packages = emptyList(), truncated = true)).status)
        assertEquals(PersonalTaskAvailabilityStatus.UNCONFIRMED, evaluate(PersonalTaskEnvironment(InstalledAppDirectoryReadResult.Failed)).status)
        assertEquals(PersonalTaskAvailabilityStatus.CHECKING, evaluate(PersonalTaskEnvironment()).status)
    }

    @Test fun missingPermissionDisabledAndDisconnectedEachOfferDeviceConfiguration() {
        listOf(DeviceAgentHealthState.AGENT_DISABLED, DeviceAgentHealthState.ACCESSIBILITY_NOT_AUTHORIZED,
            DeviceAgentHealthState.SERVICE_DISCONNECTED).forEach { health ->
            val result = evaluate(environment().copy(health = health))
            assertEquals(PersonalTaskAvailabilityStatus.NEEDS_CONFIGURATION, result.status)
            assertEquals(PersonalTaskConfigurationTarget.DEVICE_AGENT, result.configurationTarget)
            assertTrue(result.reason.isNotBlank())
        }
    }

    @Test fun providerModelAndProfileToolChangesInvalidateReadinessImmediately() {
        val initial = state()
        val missingTool = initial.copy(agentProfiles = initial.agentProfiles.map { it.copy(allowedToolNames = listOf("device.snapshot")) })
        assertEquals(PersonalTaskConfigurationTarget.AGENT_PROFILE,
            PersonalTaskAvailabilityPolicy.evaluate(template, missingTool.personalTaskPreflightRequest(), environment()).configurationTarget)
        val removedModel = initial.copy(profiles = initial.profiles.map { it.copy(enabledModels = emptyList()) })
        assertEquals(PersonalTaskConfigurationTarget.PROVIDER,
            PersonalTaskAvailabilityPolicy.evaluate(template, removedModel.personalTaskPreflightRequest(), environment()).configurationTarget)
        val noProfile = initial.copy(agentProfiles = emptyList())
        assertEquals(PersonalTaskConfigurationTarget.AGENT_PROFILE,
            PersonalTaskAvailabilityPolicy.evaluate(template, noProfile.personalTaskPreflightRequest(), environment()).configurationTarget)
    }

    @Test fun freshInstallRoutesToProviderBeforeAskingForAnAgentModel() {
        val initial = state().copy(profiles = listOf(ProviderProfile.blank("provider")))
        val result = PersonalTaskAvailabilityPolicy.evaluate(template, initial.personalTaskPreflightRequest(), environment())
        assertEquals(PersonalTaskConfigurationTarget.PROVIDER, result.configurationTarget)
        assertEquals("请先配置模型提供方并启用可用模型", result.reason)
    }

    private fun evaluate(environment: PersonalTaskEnvironment) = PersonalTaskAvailabilityPolicy.evaluate(template, state().personalTaskPreflightRequest(), environment)

    private fun environment(packages: List<String> = listOf("com.android.calculator2"), truncated: Boolean = false) = PersonalTaskEnvironment(
        InstalledAppDirectoryReadResult.Success(InstalledAppDirectory(packages.map { InstalledAppRecord("计算器", it, InstalledAppCapability.CALCULATOR) }, truncated)),
        DeviceAgentHealthState.READY,
    )

    private fun state(): XiaoLingUiState {
        val tools = template.requiredToolNames
        val profile = AgentProfileRecord("agent", "测试", "灵", "provider", "test-model", ApiMode.CHAT_COMPLETIONS,
            "", AgentContextPolicy.CURRENT_CONVERSATION, tools.toList(), emptyList(), false, 1L, 1L)
        return XiaoLingUiState(selectedAgentProfileId = "agent", agentProfiles = listOf(profile),
            profiles = listOf(ProviderProfile.blank("provider").copy(baseUrl = "https://example.com/v1", apiKey = "fixture",
                model = "test-model", enabledModels = listOf("test-model"))),
            registeredAgentTools = tools.map { ToolDefinition(it, "测试工具", ToolRisk.SAFE) })
    }
}
