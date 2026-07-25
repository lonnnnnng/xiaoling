package com.longdev.xiaoling.knowledge

import com.longdev.xiaoling.model.ProviderRequestConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class KnowledgeAnswerabilityProductionShadowPolicyTest {
    @Test
    fun productionShadowRequiresExplicitOptInAndExactFrozenIdentity() {
        val frozenBinding = KnowledgeAnswerabilityProductionShadowBinding.frozenBinding
        val exactIdentity = frozenBinding.judgeIdentity

        assertEquals(
            KnowledgeAnswerabilityFeatureSet.VERDICT_EVIDENCE_AND_CONFIDENCE,
            frozenBinding.gate.featureSet,
        )
        assertEquals(0.85, frozenBinding.gate.minimumConfidence ?: -1.0, 0.0)
        assertEquals(
            KnowledgeAnswerabilityShadowObservationMode.DISABLED,
            KnowledgeAnswerabilityShadowActivationPolicy.modeFor(
                userEnabled = false,
                actualIdentity = exactIdentity,
                frozenBinding = frozenBinding,
            ),
        )
        assertEquals(
            KnowledgeAnswerabilityShadowObservationMode.DISABLED,
            KnowledgeAnswerabilityShadowActivationPolicy.modeFor(
                userEnabled = true,
                actualIdentity = exactIdentity.copy(model = "drifted-model"),
                frozenBinding = frozenBinding,
            ),
        )
        assertEquals(
            KnowledgeAnswerabilityShadowObservationMode.SHADOW,
            KnowledgeAnswerabilityShadowActivationPolicy.modeFor(
                userEnabled = true,
                actualIdentity = exactIdentity,
                frozenBinding = frozenBinding,
            ),
        )
    }

    @Test
    fun productionIdentityIsDerivedFromCurrentProviderConfig() {
        val baseUrl = "https://judge.example/v1"
        val actualIdentity = KnowledgeAnswerabilityJudgeIdentityFactory.fromConfig(
            ProviderRequestConfig(
                baseUrl = baseUrl,
                apiKey = "test-key",
                model = KnowledgeAnswerabilityProductionShadowBinding.MODEL,
                providerId = KnowledgeAnswerabilityProductionShadowBinding.PROVIDER_ID,
            ),
        )

        assertEquals(KnowledgeAnswerabilityProductionShadowBinding.PROVIDER_ID, actualIdentity.providerId)
        assertEquals(KnowledgeAnswerabilityProductionShadowBinding.MODEL, actualIdentity.model)
        assertEquals(KnowledgeRelevanceIdentityFingerprint.forBaseUrl(baseUrl), actualIdentity.configurationFingerprint)
        assertEquals(KnowledgeAnswerabilityJudgeProtocol.PROMPT_VERSION, actualIdentity.promptVersion)
    }
}
