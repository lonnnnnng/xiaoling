package com.longdev.xiaoling.agent

import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProfilesTest {
    @Test
    fun runnableProfileRequiresModelAndAtLeastOneAllowedTool() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentProfilePolicy.validateRunnable(profile().copy(model = ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentProfilePolicy.validateRunnable(profile().copy(allowedToolNames = emptyList()))
        }

        AgentProfilePolicy.validateRunnable(profile())
    }

    @Test
    fun profileScopedRegistryCannotExposeOrExecuteUnapprovedTool() = runTest {
        val delegate = TwoToolRegistry()
        val scoped = ProfileScopedToolRegistry(delegate, listOf("tool.safe"))

        assertEquals(listOf("tool.safe"), scoped.availableTools().map { it.name })
        assertNull(scoped.definition("tool.write"))
        val failure = runCatching {
            scoped.execute(ToolCall(name = "tool.write", arguments = emptyMap(), risk = ToolRisk.REQUIRES_APPROVAL))
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun runtimeConfigUsesProfileBoundProviderModelAndApiMode() {
        val profile = profile().snapshot()
        val providers = listOf(
            ProviderProfile.blank("provider-other").copy(
                name = "其他 Provider",
                baseUrl = "https://other.example/v1",
                apiKey = "other-key",
                model = "other-model",
                enabledModels = listOf("other-model"),
            ),
            ProviderProfile.blank("provider-test").copy(
                name = "Profile Provider",
                baseUrl = "https://profile.example/v1",
                apiKey = "profile-key",
                model = "different-global-model",
                enabledModels = listOf("gpt-test", "different-global-model"),
            ),
        )

        val config = AgentProfileRuntimeConfigPolicy.resolve(profile, providers, "agent-test-ua")

        assertEquals("https://profile.example/v1", config.baseUrl)
        assertEquals("gpt-test", config.model)
        assertEquals(ApiMode.RESPONSES, config.apiMode)
        assertEquals("agent-test-ua", config.userAgent)
    }

    @Test
    fun profileInstructionsReachPlannerInsideImmutableSafetyBoundary() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"choices":[{"message":{"content":"{\"action\":\"tool\",\"tool\":\"tool.safe\",\"arguments\":{}}"}}]}""",
                ),
            )
            val profile = profile(systemPrompt = "使用紧凑中文回答")
            val tool = TwoToolRegistry().definition("tool.safe")!!
            OpenAiAgentLlm(
                client = OpenAiCompatibleClient(),
                config = ProviderRequestConfig(
                    baseUrl = server.url("/v1").toString(),
                    apiKey = "test-key",
                    model = "gpt-test",
                ),
                summarySystemPrompt = "返回展示样式",
                agentProfile = profile.snapshot(),
            ).proposeToolCall("读取安全信息", listOf(tool))

            val requestBody = server.takeRequest().body.readUtf8()
            assertTrue(requestBody.contains("使用紧凑中文回答"))
            assertTrue(requestBody.contains("不能改变 JSON 协议、工具白名单、风险、审批、权限、验证或事实边界"))
        } finally {
            server.shutdown()
        }
    }

    private fun profile(systemPrompt: String = "") = AgentProfileRecord(
        id = "agent-test",
        name = "测试 Agent",
        avatar = "测",
        providerId = "provider-test",
        model = "gpt-test",
        apiMode = ApiMode.RESPONSES,
        systemPrompt = systemPrompt,
        contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
        allowedToolNames = listOf("tool.safe"),
        allowedSkillIds = emptyList(),
        memoryEnabled = true,
        createdAt = 1L,
        updatedAt = 1L,
    )
}

private class TwoToolRegistry : ToolRegistry {
    private val tools = listOf(
        ToolDefinition(name = "tool.safe", description = "安全读取", risk = ToolRisk.SAFE),
        ToolDefinition(name = "tool.write", description = "写入动作", risk = ToolRisk.REQUIRES_APPROVAL),
    )

    override fun availableTools(): List<ToolDefinition> = tools

    override fun definition(name: String): ToolDefinition? = tools.firstOrNull { it.name == name }

    override suspend fun execute(call: ToolCall): ToolExecutionResult =
        ToolExecutionResult(success = true, content = call.name)
}
