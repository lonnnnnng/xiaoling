package com.longdev.xiaoling.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.storage.ProviderRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * long: 该探针验证显式 Provider 的单次计划请求；只有显式要求恢复配置时才在成功后写入 Provider，不创建任务或执行工具。
 */
@RunWith(AndroidJUnit4::class)
class RealProviderPersonalTaskPlanContextInstrumentedTest {
    @Test
    fun explicitProviderParsesOneRequestAfterContextCompaction() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString(ARG_BASE_URL).orEmpty().trim()
        val apiKey = arguments.getString(ARG_API_KEY).orEmpty().trim()
        val model = arguments.getString(ARG_MODEL).orEmpty().trim()
        val restoreProvider = arguments.getString(ARG_RESTORE_PROVIDER).orEmpty().toBooleanStrictOrNull() == true
        assumeTrue("未显式提供计划 Provider Base URL，跳过第137阶段真实模型探针", baseUrl.isNotBlank())
        assumeTrue("未显式提供计划 Provider API Key，跳过第137阶段真实模型探针", apiKey.isNotBlank())
        assumeTrue("未显式提供计划 Provider 模型，跳过第137阶段真实模型探针", model.isNotBlank())

        val longMemory = List(3) { index -> "记忆-$index-" + "中".repeat(795) }
        val longKnowledge = List(3) { index ->
            PersonalTaskKnowledgeContext(
                documentName = "资料-$index.md",
                text = "知识-$index-" + "文".repeat(795),
            )
        }
        val request = PersonalTaskPlanPolicy.prepareRequest(
            goal = "根据参考资料生成一个立即执行的单步计划，读取当前时间",
            allowedToolNames = listOf("app.current_time", "knowledge.search", "memory.search"),
            context = PersonalTaskPlanContext(
                memoryFacts = longMemory,
                knowledgeSnippets = longKnowledge,
            ),
        )
        assertTrue(request.contextUsage.contextBytes <= PersonalTaskPlanContextPolicy.MAX_CONTEXT_BYTES)
        assertTrue(request.contextUsage.memoryUsedCount > 0)
        assertTrue(request.contextUsage.knowledgeUsedCount > 0)
        assertTrue(request.contextUsage.memoryOmittedCount + request.contextUsage.knowledgeOmittedCount > 0)

        val response = OpenAiCompatibleClient().sendStructuredMessage(
            config = ProviderRequestConfig(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                maxTokens = 2_000,
                httpDebugLoggingEnabled = false,
            ),
            messages = request.messages,
            outputFormat = PersonalTaskPlanPolicy.outputFormat,
        )
        val plan = PersonalTaskPlanPolicy.parse(
            raw = response.responseText,
            allowedToolNames = setOf("app.current_time", "knowledge.search", "memory.search"),
        )

        assertEquals(PersonalTaskScheduleType.IMMEDIATE, plan.schedule.type)
        assertTrue(plan.steps.isNotEmpty())
        if (restoreProvider) {
            val profile = ProviderProfile(
                id = RESTORED_PROVIDER_ID,
                name = "Redmi 真实计划验收",
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                availableModels = listOf(model),
                enabledModels = listOf(model),
            )
            val repository = ProviderRepository(InstrumentationRegistry.getInstrumentation().targetContext)
            repository.save(profiles = listOf(profile), selectedProfileId = profile.id)
            val restored = repository.load()
            assertEquals(profile.id, restored.selectedProfileId)
            assertEquals(model, restored.profiles.single().model)
        }
        println(
            "STAGE137_REAL_PLAN contextBytes=${request.contextUsage.contextBytes} " +
                "memoryUsed=${request.contextUsage.memoryUsedCount} " +
                "memoryOmitted=${request.contextUsage.memoryOmittedCount} " +
                "knowledgeUsed=${request.contextUsage.knowledgeUsedCount} " +
                "knowledgeOmitted=${request.contextUsage.knowledgeOmittedCount} " +
                "promptBytes=${response.promptBytes} latencyMs=${response.latencyMs} steps=${plan.steps.size}",
        )
    }

    private companion object {
        const val ARG_BASE_URL = "personalTaskProviderBaseUrl"
        const val ARG_API_KEY = "personalTaskProviderApiKey"
        const val ARG_MODEL = "personalTaskProviderModel"
        const val ARG_RESTORE_PROVIDER = "restorePersonalTaskProvider"
        const val RESTORED_PROVIDER_ID = "stage137-redmi-provider"
    }
}
