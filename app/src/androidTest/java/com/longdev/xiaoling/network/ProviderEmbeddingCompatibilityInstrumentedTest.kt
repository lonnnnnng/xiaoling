package com.longdev.xiaoling.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.model.preferredEmbeddingModel
import com.longdev.xiaoling.storage.ProviderRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProviderEmbeddingCompatibilityInstrumentedTest {
    @Test
    fun explicitProviderSynchronizesModelsAndCreatesCompatibleEmbeddings() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString(ARG_BASE_URL).orEmpty().trim()
        val apiKey = arguments.getString(ARG_API_KEY).orEmpty().trim()
        val chatModel = arguments.getString(ARG_CHAT_MODEL).orEmpty().trim()
        assumeTrue("未显式提供真实 Provider，跳过联网兼容验收", baseUrl.isNotBlank())
        assumeTrue("未显式提供真实 Provider API Key，跳过联网兼容验收", apiKey.isNotBlank())
        assumeTrue("未显式提供真实 Provider 对话模型，跳过联网兼容验收", chatModel.isNotBlank())

        val client = OpenAiCompatibleClient()
        val requestConfig = ProviderRequestConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = chatModel,
        )
        val availableModels = client.fetchModels(requestConfig)
        val profile = ProviderProfile(
            id = PROVIDER_ID,
            name = "Redmi 兼容验收",
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = chatModel,
            availableModels = availableModels,
            enabledModels = listOf(chatModel),
        )

        // long: connected test 可能重装应用并清空设备配置；先保存已同步 Provider，确保验收结束后 Redmi 仍能直接使用兜底模型。
        ProviderRepository(InstrumentationRegistry.getInstrumentation().targetContext).save(
            profiles = listOf(profile),
            selectedProfileId = profile.id,
        )

        val embeddingModel = profile.preferredEmbeddingModel()
        assumeTrue("Provider 模型列表未包含 Embedding 模型，仅验证模型同步与配置恢复", embeddingModel != null)
        val vectors = client.createEmbeddings(
            config = requestConfig.copy(
                providerId = profile.id,
                embeddingModel = embeddingModel,
            ),
            inputs = listOf("小灵知识检索兼容性验收", "Embedding 向量维度一致性验收"),
        )

        assertEquals(2, vectors.size)
        assertTrue(vectors.all(FloatArray::isNotEmpty))
        assertEquals(vectors.first().size, vectors.last().size)
        assertTrue(vectors.all { vector -> vector.all(Float::isFinite) })
    }

    private companion object {
        const val ARG_BASE_URL = "providerBaseUrl"
        const val ARG_API_KEY = "providerApiKey"
        const val ARG_CHAT_MODEL = "providerChatModel"
        const val PROVIDER_ID = "redmi-provider-compatibility"
    }
}
