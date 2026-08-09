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
 * long: 该探针验证自然语言目标会在正式 Agent Runtime 中选择存储状态 Skill，
 * 并确认最终回答只消费容量摘要，不泄露 Provider 凭据、文件路径或设备身份。
 */
@RunWith(AndroidJUnit4::class)
class RealProviderStorageStatusInstrumentedTest {
    @Test
    fun foregroundAgentReadsCurrentStorageFactsOnly() = kotlinx.coroutines.runBlocking {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val stored = ProviderRepository(targetContext).load()
        val provider = stored.profiles.firstOrNull { it.id == stored.selectedProfileId }
        val baseUrl = provider?.baseUrl?.trim().orEmpty()
        val apiKey = provider?.apiKey?.trim().orEmpty()
        val model = provider?.model?.trim().orEmpty()
        assumeTrue("Redmi 当前 Provider Base URL 不可用", baseUrl.isNotBlank())
        assumeTrue("Redmi 当前 Provider API Key 不可用", apiKey.isNotBlank())
        assumeTrue("Redmi 当前 Provider 模型不可用", model.isNotBlank())

        val providerId = "stage219-storage-provider"
        val profile = AgentProfileSnapshot(
            id = "stage219-storage-agent",
            name = "第219阶段存储状态 Agent",
            avatar = "D",
            providerId = providerId,
            model = model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "只读取存储容量摘要；不得泄露 Provider、凭据、文件路径、应用数据或设备身份。",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("app.get_storage"),
            allowedSkillIds = listOf("storage-status"),
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
            conversationId = "conversation-stage219-${UUID.randomUUID()}",
            userMessageId = "message-stage219-${UUID.randomUUID()}",
            goal = "请读取当前手机还剩多少存储空间，只使用 app.get_storage，不要调用其他工具。",
            config = config,
            summarySystemPrompt = "只根据工具结果简洁回答存储总量、可用空间和使用率。",
            agentProfile = profile,
            memoryRecallEnabled = false,
        )

        assertEquals(AgentRunStatus.COMPLETED, run.status)
        val detail = RoomAgentRunRepository(targetContext).runDetail(run.runId)
        assertNotNull(detail)
        val result = requireNotNull(detail).toolLedger.results.single()
        assertEquals("app.get_storage", result.toolName)
        assertTrue(result.success)
        assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus)
        assertTrue(result.content.lines().size == 3)
        assertTrue(requireNotNull(detail).approvals.isEmpty())
        assertFalse(result.content.contains("/data/"))
        assertFalse(run.responseText.contains(baseUrl))
        assertFalse(run.responseText.contains(apiKey))
        assertFalse(run.responseText.contains("stage219-storage-agent"))
        assertFalse(run.responseText.contains("wsvwypiz7xwslvl7"))
        assertFalse(run.responseText.contains("com.longdev"))
        println(
            "STAGE219_REAL_STORAGE runId=${run.runId} status=${run.status} " +
                "tool=${result.toolName} verified=${result.verificationStatus} approvals=0 privacySafe=true",
        )
    }
}
