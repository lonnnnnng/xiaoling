package com.longdev.xiaoling.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * long: 该探针验证自然语言目标能够在正式 Agent Runtime 中读取两项本地状态，
 * 并确认 Tool Ledger 只保留脱敏状态，不把 Provider 凭据或设备身份带入结果。
 */
@RunWith(AndroidJUnit4::class)
class RealProviderDeviceStatusInstrumentedTest {
    @Test
    fun foregroundAgentReadsBatteryAndConnectivityFactsOnly() = kotlinx.coroutines.runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val stored = ProviderRepository(targetContext).load()
        val provider = stored.profiles.firstOrNull { it.id == stored.selectedProfileId }
        val baseUrl = provider?.baseUrl?.trim().orEmpty()
        val apiKey = provider?.apiKey?.trim().orEmpty()
        val model = provider?.model?.trim().orEmpty()
        assumeTrue("Redmi 当前 Provider Base URL 不可用", baseUrl.isNotBlank())
        assumeTrue("Redmi 当前 Provider API Key 不可用", apiKey.isNotBlank())
        assumeTrue("Redmi 当前 Provider 模型不可用", model.isNotBlank())

        val providerId = "stage217-status-provider"
        val profile = AgentProfileSnapshot(
            id = "stage217-status-agent",
            name = "第217阶段设备状态 Agent",
            avatar = "S",
            providerId = providerId,
            model = model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "只读取电量和网络状态；不得泄露 Provider、凭据、设备标识或调用其他工具。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("app.get_battery", "app.get_connectivity"),
            allowedSkillIds = listOf("battery-status", "connectivity-status"),
            memoryEnabled = false,
        )
        val config = ProviderRequestConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            providerId = providerId,
            apiMode = ApiMode.RESPONSES,
            streamingEnabled = false,
            reasoningSummaryEnabled = false,
            userAgent = ProviderRequestConfig.DEFAULT_USER_AGENT,
            maxTokens = 4_000,
        )
        val conversationId = "conversation-stage217-${UUID.randomUUID()}"
        val userMessageId = "message-stage217-${UUID.randomUUID()}"
        val run = AgentRunUseCase(
            context = targetContext,
            client = OpenAiCompatibleClient(),
        ).run(
            conversationId = conversationId,
            userMessageId = userMessageId,
            goal = "请读取当前电量和网络状态，只使用 app.get_battery 与 app.get_connectivity，不要调用其他工具。",
            config = config,
            summarySystemPrompt = "只根据工具结果简洁回答当前电量、充电状态、网络连接和互联网可达性。",
            agentProfile = profile,
            memoryRecallEnabled = false,
        )

        assertEquals(AgentRunStatus.COMPLETED, run.status)
        val detail = RoomAgentRunRepository(targetContext).runDetail(run.runId)
        assertNotNull(detail)
        val results = requireNotNull(detail).toolLedger.results
        assertEquals(setOf("app.get_battery", "app.get_connectivity"), results.map { it.toolName }.toSet())
        assertTrue(results.all { it.success && it.verificationStatus == ToolVerificationStatus.PASSED })
        assertTrue(requireNotNull(detail).approvals.isEmpty())
        assertFalse(run.responseText.contains(baseUrl))
        assertFalse(run.responseText.contains(apiKey))
        assertFalse(run.responseText.contains("stage217-status-agent"))
        assertFalse(run.responseText.contains("wsvwypiz7xwslvl7"))
        assertFalse(run.responseText.contains("com.longdev"))
        println(
            "STAGE217_REAL_STATUS runId=${run.runId} status=${run.status} " +
                "tools=${results.map { it.toolName }.sorted()} approvals=0 privacySafe=true",
        )
    }
}
