package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRelevanceRolloutControlPlaneTest {
    @Test
    fun candidateIdentityAlwaysRemainsShadowEvenWhenUserSwitchIsOn() {
        val snapshot = KnowledgeRelevanceRolloutControlPlane.resolve(
            frozenGate = frozenGate(),
            binding = candidateBinding(),
            preference = matchingPreference(),
        )

        assertEquals(KnowledgeRelevanceProductionIdentityStatus.CANDIDATE, snapshot.bindingStatus)
        assertEquals(KnowledgeRelevanceRolloutMode.SHADOW, snapshot.resolution.mode)
        assertEquals(KnowledgeRelevanceRolloutReason.PRODUCTION_IDENTITY_UNVERIFIED, snapshot.resolution.reason)
        assertTrue(snapshot.rollbackAvailable)
    }

    @Test
    fun verifiedIdentityAndMatchingPreferenceCanResolveEnforce() {
        val binding = verifiedBinding()
        val snapshot = KnowledgeRelevanceRolloutControlPlane.resolve(
            frozenGate = frozenGate(),
            binding = binding,
            preference = matchingPreference(),
        )

        assertEquals(KnowledgeRelevanceProductionIdentityStatus.VERIFIED, snapshot.bindingStatus)
        assertEquals(KnowledgeRelevanceRolloutMode.ENFORCE, snapshot.resolution.mode)
        assertEquals(KnowledgeRelevanceRolloutReason.MATCHING_FROZEN_GATE, snapshot.resolution.reason)
        assertTrue(snapshot.resolution.enforcementEnabled)
    }

    @Test
    fun endpointOrEvidenceDriftFallsBackToShadow() {
        val endpointDrift = KnowledgeRelevanceRolloutControlPlane.resolve(
            frozenGate = frozenGate(),
            binding = verifiedBinding(),
            preference = matchingPreference(configurationFingerprint = "endpoint-other"),
        )
        val evidenceDrift = KnowledgeRelevanceRolloutControlPlane.resolve(
            frozenGate = frozenGate(),
            binding = verifiedBinding().copy(evidenceVersion = "evidence-other"),
            preference = matchingPreference(),
        )

        assertEquals(KnowledgeRelevanceRolloutMode.SHADOW, endpointDrift.resolution.mode)
        assertEquals(KnowledgeRelevanceRolloutReason.IDENTITY_BINDING_MISMATCH, endpointDrift.resolution.reason)
        assertEquals(KnowledgeRelevanceRolloutMode.SHADOW, evidenceDrift.resolution.mode)
        assertEquals(KnowledgeRelevanceRolloutReason.IDENTITY_BINDING_MISMATCH, evidenceDrift.resolution.reason)
    }

    @Test
    fun disabledPreferenceStaysShadowWithoutRequiringBinding() {
        val snapshot = KnowledgeRelevanceRolloutControlPlane.resolve(
            frozenGate = frozenGate(),
            binding = KnowledgeRelevanceProductionIdentityBinding(),
            preference = KnowledgeRelevanceRolloutPreference(),
        )

        assertEquals(KnowledgeRelevanceRolloutMode.SHADOW, snapshot.resolution.mode)
        assertEquals(KnowledgeRelevanceRolloutReason.DISABLED_BY_USER, snapshot.resolution.reason)
        assertFalse(snapshot.resolution.enforcementEnabled)
        assertFalse(snapshot.rollbackAvailable)
    }

    private fun matchingPreference(configurationFingerprint: String = "endpoint-a") =
        KnowledgeRelevanceRolloutPreference(
            enforcementEnabled = true,
            gateVersion = "stage89-production-gate-v1",
            providerId = "provider-a",
            model = "embedding-a",
            identityEvidenceVersion = "stage89-identity-evidence-v1",
            configurationFingerprint = configurationFingerprint,
        )

    private fun candidateBinding() = KnowledgeRelevanceProductionIdentityBinding(
        status = KnowledgeRelevanceProductionIdentityStatus.CANDIDATE,
        identity = KnowledgeRelevanceProductionIdentity("provider-a", "embedding-a", "endpoint-a"),
    )

    private fun verifiedBinding() = KnowledgeRelevanceProductionIdentityBinding(
        status = KnowledgeRelevanceProductionIdentityStatus.VERIFIED,
        identity = KnowledgeRelevanceProductionIdentity("provider-a", "embedding-a", "endpoint-a"),
        gateVersion = "stage89-production-gate-v1",
        evidenceVersion = "stage89-identity-evidence-v1",
        holdoutDatasetVersion = "stage89-final-holdout-v1",
    )

    private fun frozenGate() = KnowledgeRelevanceRawTopScoreFrozenGate(
        gateVersion = "stage89-production-gate-v1",
        calibrationIdentity = identity("stage89-calibration-v1"),
        validationIdentity = identity("stage89-validation-v1"),
        minimumRawTopScore = 0.64,
    )

    private fun identity(datasetVersion: String) = KnowledgeRelevanceFeatureDatasetIdentity(
        providerId = "provider-a",
        model = "embedding-a",
        datasetVersion = datasetVersion,
    )
}
