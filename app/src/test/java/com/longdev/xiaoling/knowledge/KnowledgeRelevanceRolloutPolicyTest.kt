package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRelevanceRolloutPolicyTest {
    @Test
    fun disabledPreferenceResolvesToShadowByDefault() {
        val resolution = KnowledgeRelevanceRolloutPolicy.resolve(
            frozenGate = frozenGate(),
            preference = KnowledgeRelevanceRolloutPreference(),
        )

        assertEquals(KnowledgeRelevanceRolloutMode.SHADOW, resolution.mode)
        assertEquals(KnowledgeRelevanceRolloutReason.DISABLED_BY_USER, resolution.reason)
        assertFalse(resolution.enforcementEnabled)
    }

    @Test
    fun matchingGateAndProviderIdentityAreRequiredForEnforcement() {
        val resolution = KnowledgeRelevanceRolloutPolicy.resolve(
            frozenGate = frozenGate(),
            preference = KnowledgeRelevanceRolloutPreference(
                enforcementEnabled = true,
                gateVersion = "stage85-raw-top1-qwen-v1",
                providerId = "provider-a",
                model = "embedding-a",
            ),
        )

        assertEquals(KnowledgeRelevanceRolloutMode.ENFORCE, resolution.mode)
        assertEquals(KnowledgeRelevanceRolloutReason.MATCHING_FROZEN_GATE, resolution.reason)
        assertTrue(resolution.enforcementEnabled)
    }

    @Test
    fun staleGateOrIdentityDriftFailsBackToShadow() {
        val stale = KnowledgeRelevanceRolloutPolicy.resolve(
            frozenGate = frozenGate(),
            preference = preference(gateVersion = "old-gate"),
        )
        val drifted = KnowledgeRelevanceRolloutPolicy.resolve(
            frozenGate = frozenGate(),
            preference = preference(providerId = "provider-other"),
        )

        assertEquals(KnowledgeRelevanceRolloutMode.SHADOW, stale.mode)
        assertEquals(KnowledgeRelevanceRolloutReason.STALE_GATE_VERSION, stale.reason)
        assertEquals(KnowledgeRelevanceRolloutMode.SHADOW, drifted.mode)
        assertEquals(KnowledgeRelevanceRolloutReason.IDENTITY_MISMATCH, drifted.reason)
    }

    @Test
    fun rollbackClearsExecutionEligibilityWithoutChangingFrozenGate() {
        val preference = preference().copy(
            identityEvidenceVersion = "evidence-v1",
            configurationFingerprint = "endpoint-a",
        )

        val rolledBack = KnowledgeRelevanceRolloutPolicy.rollback(preference)
        val resolution = KnowledgeRelevanceRolloutPolicy.resolve(frozenGate(), rolledBack)

        assertEquals(KnowledgeRelevanceRolloutPreference(), rolledBack)
        assertEquals(KnowledgeRelevanceRolloutMode.SHADOW, resolution.mode)
        assertEquals(KnowledgeRelevanceRolloutReason.DISABLED_BY_USER, resolution.reason)
        assertEquals(0.6416276358587735, frozenGate().minimumRawTopScore, 0.0)
    }

    @Test
    fun reusedOrIncompleteValidationDatasetCannotGrantEnforcement() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceRolloutPolicy.resolve(
                frozenGate = frozenGate().copy(
                    validationIdentity = identity("stage85-calibration-v1"),
                ),
                preference = preference(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceRolloutPolicy.resolve(
                frozenGate = frozenGate().copy(
                    validationIdentity = identity(" "),
                ),
                preference = preference(),
            )
        }
    }

    private fun preference(
        gateVersion: String = "stage85-raw-top1-qwen-v1",
        providerId: String = "provider-a",
        model: String = "embedding-a",
    ) = KnowledgeRelevanceRolloutPreference(
        enforcementEnabled = true,
        gateVersion = gateVersion,
        providerId = providerId,
        model = model,
    )

    private fun frozenGate() = KnowledgeRelevanceRawTopScoreFrozenGate(
        gateVersion = "stage85-raw-top1-qwen-v1",
        calibrationIdentity = identity("stage85-calibration-v1"),
        validationIdentity = identity("stage85-validation-v1"),
        minimumRawTopScore = 0.6416276358587735,
    )

    private fun identity(datasetVersion: String) = KnowledgeRelevanceFeatureDatasetIdentity(
        providerId = "provider-a",
        model = "embedding-a",
        datasetVersion = datasetVersion,
    )
}
