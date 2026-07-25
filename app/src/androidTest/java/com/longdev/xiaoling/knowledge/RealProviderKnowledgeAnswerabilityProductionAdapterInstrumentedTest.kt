package com.longdev.xiaoling.knowledge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.model.ProviderRequestConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * long: 该显式探针验证第 96 阶段生产 adapter 与冻结绑定，缺少运行参数时跳过，不把外部 Provider 变成默认测试依赖。
 */
@RunWith(AndroidJUnit4::class)
class RealProviderKnowledgeAnswerabilityProductionAdapterInstrumentedTest {
    @Test
    fun productionAdapterCreatesBoundShadowWithoutEnforcement() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString(ARG_BASE_URL).orEmpty().trim()
        val apiKey = arguments.getString(ARG_API_KEY).orEmpty().trim()
        val model = arguments.getString(ARG_MODEL).orEmpty().trim()
        val providerId = arguments.getString(ARG_PROVIDER_ID).orEmpty().trim()
        assumeTrue("未显式提供生产 answerability Judge Base URL", baseUrl.isNotBlank())
        assumeTrue("未显式提供生产 answerability Judge API Key", apiKey.isNotBlank())
        assumeTrue("未显式提供生产 answerability Judge 模型", model.isNotBlank())
        assumeTrue("未显式提供生产 answerability Judge Provider ID", providerId.isNotBlank())

        val frozenBinding = KnowledgeAnswerabilityProductionShadowBinding.frozenBinding
        val actualIdentity = KnowledgeAnswerabilityJudgeIdentityFactory.fromConfig(
            ProviderRequestConfig(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                providerId = providerId,
            ),
        )
        assertEquals(frozenBinding.judgeIdentity, actualIdentity)
        val candidate = KnowledgeAnswerabilityShadowCandidate(
            sourceRunId = "stage96-production-adapter-run",
            question = "照片备份应采用什么副本、介质和位置策略？",
            candidateText = "照片备份采用三份副本、两种介质和一份异地保存，完成后随机打开文件核对完整性。",
            references = listOf(
                KnowledgeReference(
                    retrievalId = "stage96-production-adapter-retrieval",
                    documentId = "stage96-production-adapter-document",
                    documentName = "照片备份规范.md",
                    documentRevision = 1,
                    chunkId = "stage96-production-adapter-chunk",
                    chunkSequence = 0,
                    startOffset = 0,
                    endOffset = 43,
                ),
            ),
        )
        val coordinator = KnowledgeAnswerabilityShadowObservationCoordinator(
            judgePort = OpenAiKnowledgeAnswerabilityJudge(
                providerConfig = ProviderRequestConfig(
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    model = model,
                    providerId = providerId,
                ),
            ),
            clock = { 1_234L },
        )

        val outcome = coordinator.observe(
            KnowledgeAnswerabilityShadowObservationRequest(
                persistedMessageId = "stage96-production-adapter-message",
                candidate = candidate,
                frozenBinding = frozenBinding,
                mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW,
                origin = KnowledgeAnswerabilityShadowObservationOrigin.DIRECT_FOREGROUND,
                persistenceMode = KnowledgeAnswerabilityShadowPersistenceMode.NONE,
            ),
        )

        assertEquals(KnowledgeAnswerabilityShadowObservationStatus.COMPLETED, outcome.status)
        assertEquals(KnowledgeAnswerabilityShadowBindingStatus.BOUND, outcome.binding?.status)
        assertEquals(KnowledgeAnswerabilityDecision.ACCEPT, outcome.binding?.decision)
        assertEquals(KnowledgeAnswerabilityShadowPersistenceStatus.NOT_REQUESTED, outcome.persistenceStatus)
        assertFalse(outcome.binding?.enforcementApplied ?: true)
    }

    private companion object {
        const val ARG_BASE_URL = "answerabilityProviderBaseUrl"
        const val ARG_API_KEY = "answerabilityProviderApiKey"
        const val ARG_MODEL = "answerabilityProviderModel"
        const val ARG_PROVIDER_ID = "answerabilityProviderId"
    }
}
