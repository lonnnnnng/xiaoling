package com.longdev.xiaoling.knowledge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealProviderKnowledgeRelevanceIdentityInstrumentedTest {
    @Test
    fun explicitProviderBindsCandidateIdentityWithoutGrantingProductionEnforcement() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString(ARG_BASE_URL).orEmpty().trim()
        val apiKey = arguments.getString(ARG_API_KEY).orEmpty().trim()
        val model = arguments.getString(ARG_MODEL).orEmpty().trim()
        val providerId = arguments.getString(ARG_PROVIDER_ID).orEmpty().trim()
        assumeTrue("未显式提供真实 Embedding Provider，跳过身份绑定", baseUrl.isNotBlank())
        assumeTrue("未显式提供真实 Embedding API Key，跳过身份绑定", apiKey.isNotBlank())
        assumeTrue("未显式提供真实 Embedding 模型，跳过身份绑定", model.isNotBlank())
        assumeTrue("未显式提供真实 Embedding Provider ID，跳过身份绑定", providerId.isNotBlank())

        val client = OpenAiCompatibleClient()
        val config = ProviderRequestConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            providerId = providerId,
            embeddingModel = model,
        )
        val advertisedModels = client.fetchModels(config)
        assertTrue("Provider 模型列表未包含指定 Embedding 模型", advertisedModels.any { it.equals(model, ignoreCase = true) })
        val vectors = client.createEmbeddings(
            config = config,
            inputs = listOf("小灵生产身份候选验证", "Embedding 维度稳定性验证"),
        )
        assertEquals(2, vectors.size)
        assertTrue(vectors.all(FloatArray::isNotEmpty))
        assertTrue(vectors.all { vector -> vector.all(Float::isFinite) })
        assertEquals(vectors.first().size, vectors.last().size)

        val probe = KnowledgeRelevanceProviderProbe(
            providerId = providerId,
            model = model,
            configurationFingerprint = KnowledgeRelevanceIdentityFingerprint.forBaseUrl(baseUrl),
            advertisedModels = advertisedModels,
            vectorCount = vectors.size,
            vectorDimensions = vectors.first().size,
        )
        val result = KnowledgeRelevanceProductionIdentityPolicy.bindCandidate(probe)
        assertTrue(result.accepted)
        assertEquals(KnowledgeRelevanceProductionIdentityStatus.CANDIDATE, result.binding.status)
        // long: 这次只证明真实端点、模型列表和向量协议可用；没有同一身份的 final holdout 证据，绝不伪造 VERIFIED 或开启 enforcement。
        println(
            "$METRICS_TAG ${JSONObject()
                .put("providerId", providerId)
                .put("model", model)
                .put("vectorCount", vectors.size)
                .put("dimensions", vectors.first().size)
                .put("status", result.binding.status.name)}",
        )
    }

    private companion object {
        const val ARG_BASE_URL = "embeddingProviderBaseUrl"
        const val ARG_API_KEY = "embeddingProviderApiKey"
        const val ARG_MODEL = "embeddingProviderModel"
        const val ARG_PROVIDER_ID = "embeddingProviderId"
        const val METRICS_TAG = "XIAOLING_STAGE89_IDENTITY_CANDIDATE"
    }
}
