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
 * long: 该探针只验证前台直接 Agent 的设备健康只读切片，确认真实 Provider 能选择独立 Skill，
 * 且 Tool Ledger/最终回答只包含四态健康摘要，不把观察树、包名或配置带出设备边界。
 */
@RunWith(AndroidJUnit4::class)
class RealProviderDeviceAgentHealthInstrumentedTest {
    @Test
    fun foregroundAgentReadsOnlyDeviceAgentHealth() = kotlinx.coroutines.runBlocking {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val stored = ProviderRepository(targetContext).load()
        val provider = stored.profiles.firstOrNull { it.id == stored.selectedProfileId }
        val baseUrl = provider?.baseUrl?.trim().orEmpty()
        val apiKey = provider?.apiKey?.trim().orEmpty()
        val model = provider?.model?.trim().orEmpty()
        assumeTrue("Redmi 当前 Provider Base URL 不可用", baseUrl.isNotBlank())
        assumeTrue("Redmi 当前 Provider API Key 不可用", apiKey.isNotBlank())
        assumeTrue("Redmi 当前 Provider 模型不可用", model.isNotBlank())

        val providerId = "stage228-device-health-provider"
        val profile = AgentProfileSnapshot(
            id = "stage228-device-health-agent",
            name = "第228阶段设备健康 Agent",
            avatar = "H",
            providerId = providerId,
            model = model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "只调用 app.get_device_agent_health；不得读取窗口、包名、节点、文本、设备身份或 Provider 配置。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("app.get_device_agent_health"),
            allowedSkillIds = listOf("device-agent-health"),
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
        val run = AgentRunUseCase(
            context = targetContext,
            client = OpenAiCompatibleClient(),
        ).run(
            conversationId = "conversation-stage228-${UUID.randomUUID()}",
            userMessageId = "message-stage228-${UUID.randomUUID()}",
            goal = "检查设备 Agent 是否可用，只使用 app.get_device_agent_health，不要调用其他工具。",
            config = config,
            summarySystemPrompt = "只根据健康工具结果回答设备 Agent 当前状态，不要复述敏感配置。",
            agentProfile = profile,
            memoryRecallEnabled = false,
        )

        assertEquals(AgentRunStatus.COMPLETED, run.status)
        assertTrue(run.responseText.isNotBlank())
        val detail = RoomAgentRunRepository(targetContext).runDetail(run.runId)
        assertNotNull(detail)
        val runDetail = requireNotNull(detail)
        val result = runDetail.toolLedger.results.single()
        assertEquals("app.get_device_agent_health", result.toolName)
        assertTrue(result.success)
        assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus)
        assertTrue(result.content in setOf("设备 Agent 健康状态：未启用", "设备 Agent 健康状态：未授权", "设备 Agent 健康状态：服务断连", "设备 Agent 健康状态：READY"))
        assertTrue(runDetail.approvals.isEmpty())
        assertFalse(result.content.contains("snapshot"))
        assertFalse(result.content.contains("package"))
        assertFalse(result.content.contains("com.longdev"))
        assertFalse(result.content.contains(baseUrl))
        assertFalse(result.content.contains(apiKey))
        assertFalse(run.responseText.contains(baseUrl))
        assertFalse(run.responseText.contains(apiKey))
        assertFalse(run.responseText.contains(profile.id))
        assertFalse(run.responseText.contains("工具白名单"))
        println(
            "STAGE228_REAL_DEVICE_HEALTH runId=${run.runId} status=${run.status} " +
                "tool=${result.toolName} verification=${result.verificationStatus} approvals=0 privacySafe=true",
        )
    }
}
