package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRelevanceProductionIdentityPolicyTest {
    @Test
    fun validEmbeddingProbeCreatesCandidateWithoutVerificationQualification() {
        val result = KnowledgeRelevanceProductionIdentityPolicy.bindCandidate(probe())

        assertTrue(result.accepted)
        assertEquals(KnowledgeRelevanceProductionIdentityReason.CANDIDATE_BOUND, result.reason)
        assertEquals(KnowledgeRelevanceProductionIdentityStatus.CANDIDATE, result.binding.status)
        assertEquals("provider-a", result.binding.identity?.providerId)
    }

    @Test
    fun missingAdvertisedModelOrInvalidVectorProbeIsRejected() {
        val missingModel = KnowledgeRelevanceProductionIdentityPolicy.bindCandidate(
            probe(advertisedModels = listOf("chat-model")),
        )
        val invalidVector = KnowledgeRelevanceProductionIdentityPolicy.bindCandidate(
            probe(vectorCount = 0),
        )

        assertFalse(missingModel.accepted)
        assertEquals(KnowledgeRelevanceProductionIdentityReason.MODEL_NOT_ADVERTISED, missingModel.reason)
        assertFalse(invalidVector.accepted)
        assertEquals(KnowledgeRelevanceProductionIdentityReason.INVALID_EMBEDDING_PROBE, invalidVector.reason)
    }

    @Test
    fun onlyMatchingPassedHoldoutCanPromoteCandidateToVerified() {
        val candidate = KnowledgeRelevanceProductionIdentityPolicy.bindCandidate(probe()).binding
        val result = KnowledgeRelevanceProductionIdentityPolicy.promoteVerified(
            candidate = candidate,
            frozenGate = frozenGate(),
            holdoutIdentity = identity("stage89-final-holdout-v1"),
            evidence = evidence(),
        )

        assertTrue(result.accepted)
        assertEquals(KnowledgeRelevanceProductionIdentityStatus.VERIFIED, result.binding.status)
        assertEquals("stage89-identity-evidence-v1", result.binding.evidenceVersion)
        assertEquals("stage89-final-holdout-v1", result.binding.holdoutDatasetVersion)
    }

    @Test
    fun failedEvidenceAndIdentityDriftCannotPromoteCandidate() {
        val candidate = KnowledgeRelevanceProductionIdentityPolicy.bindCandidate(probe()).binding
        val failed = KnowledgeRelevanceProductionIdentityPolicy.promoteVerified(
            candidate = candidate,
            frozenGate = frozenGate(),
            holdoutIdentity = identity("stage89-final-holdout-v1"),
            evidence = evidence(passed = false),
        )
        val drifted = KnowledgeRelevanceProductionIdentityPolicy.promoteVerified(
            candidate = candidate,
            frozenGate = frozenGate(),
            holdoutIdentity = identity("stage89-final-holdout-v1"),
            evidence = evidence(configurationFingerprint = "endpoint-other"),
        )

        assertFalse(failed.accepted)
        assertEquals(KnowledgeRelevanceProductionIdentityReason.EVIDENCE_FAILED, failed.reason)
        assertFalse(drifted.accepted)
        assertEquals(KnowledgeRelevanceProductionIdentityReason.EVIDENCE_IDENTITY_MISMATCH, drifted.reason)
    }

    @Test
    fun revokedBindingRetainsAuditIdentityButIsNotVerified() {
        val candidate = KnowledgeRelevanceProductionIdentityPolicy.bindCandidate(probe()).binding
        val revoked = KnowledgeRelevanceProductionIdentityPolicy.revoke(candidate)

        assertEquals(KnowledgeRelevanceProductionIdentityStatus.REVOKED, revoked.status)
        assertEquals(candidate.identity, revoked.identity)
    }

    @Test
    fun endpointFingerprintIsStableWithoutReturningRawUrl() {
        val first = KnowledgeRelevanceIdentityFingerprint.forBaseUrl("https://example.test/v1/")
        val second = KnowledgeRelevanceIdentityFingerprint.forBaseUrl("https://example.test/v1")

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertFalse(first.contains("example"))
    }

    private fun probe(
        advertisedModels: List<String> = listOf("embedding-a"),
        vectorCount: Int = 2,
    ) = KnowledgeRelevanceProviderProbe(
        providerId = "provider-a",
        model = "embedding-a",
        configurationFingerprint = "endpoint-a",
        advertisedModels = advertisedModels,
        vectorCount = vectorCount,
        vectorDimensions = 1024,
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

    private fun evidence(
        configurationFingerprint: String = "endpoint-a",
        passed: Boolean = true,
    ) = KnowledgeRelevanceProductionVerificationEvidence(
        evidenceVersion = "stage89-identity-evidence-v1",
        gateVersion = "stage89-production-gate-v1",
        providerId = "provider-a",
        model = "embedding-a",
        configurationFingerprint = configurationFingerprint,
        holdoutDatasetVersion = "stage89-final-holdout-v1",
        finalHoldoutPassed = passed,
    )
}
