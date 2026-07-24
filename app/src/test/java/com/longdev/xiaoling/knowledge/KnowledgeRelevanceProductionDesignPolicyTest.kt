package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class KnowledgeRelevanceProductionDesignPolicyTest {
    @Test
    fun disabledGateOnlyObservesLowSemanticScoreWithoutChangingResults() {
        val decision = policy(enabled = false).evaluate(retrieval(topScore = 0.60), lexicalHitCount = 2)

        assertEquals(KnowledgeRelevanceProductionDisposition.KEEP_CURRENT_RESULTS, decision.disposition)
        assertEquals(KnowledgeRelevanceProductionReason.GATE_DISABLED, decision.reason)
        assertFalse(decision.enforcementEnabled)
        assertTrue(decision.semanticRejectionWouldApply)
        assertTrue(decision.preserveLexicalFallback)
    }

    @Test
    fun enabledGateDropsOnlySemanticCandidatesAndKeepsLexicalFallback() {
        val withLexical = policy(enabled = true).evaluate(retrieval(topScore = 0.60), lexicalHitCount = 2)
        val withoutLexical = policy(enabled = true).evaluate(retrieval(topScore = 0.60), lexicalHitCount = 0)

        assertEquals(KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_KEEP_LEXICAL, withLexical.disposition)
        assertEquals(KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_NO_LEXICAL, withoutLexical.disposition)
        assertEquals(KnowledgeRelevanceProductionReason.BELOW_FROZEN_THRESHOLD, withLexical.reason)
        assertTrue(withLexical.preserveLexicalFallback)
        assertFalse(withoutLexical.preserveLexicalFallback)
    }

    @Test
    fun scoreAtOrAboveFrozenThresholdIsKept() {
        val decision = policy(enabled = true).evaluate(retrieval(topScore = 0.70), lexicalHitCount = 0)

        assertEquals(KnowledgeRelevanceProductionDisposition.KEEP_CURRENT_RESULTS, decision.disposition)
        assertEquals(KnowledgeRelevanceProductionReason.ACCEPTED_ABOVE_FROZEN_THRESHOLD, decision.reason)
        assertFalse(decision.semanticRejectionWouldApply)
    }

    @Test
    fun unknownSemanticStateAndIdentityDriftFailOpen() {
        val statuses = listOf(
            KnowledgeEmbeddingStatus.LEXICAL_ONLY,
            KnowledgeEmbeddingStatus.NO_INDEX,
            KnowledgeEmbeddingStatus.PROVIDER_UNAVAILABLE,
            KnowledgeEmbeddingStatus.DIMENSION_MISMATCH,
        )
        statuses.forEach { status ->
            val decision = policy(enabled = true).evaluate(retrieval(status = status), lexicalHitCount = 1)
            assertEquals(KnowledgeRelevanceProductionDisposition.KEEP_CURRENT_RESULTS, decision.disposition)
            assertEquals(KnowledgeRelevanceProductionReason.NON_SEMANTIC_RESULT, decision.reason)
            assertTrue(decision.preserveLexicalFallback)
        }
        val drifted = policy(enabled = true).evaluate(
            retrieval(providerId = "provider-other", model = "embedding-other"),
            lexicalHitCount = 0,
        )
        assertEquals(KnowledgeRelevanceProductionReason.IDENTITY_MISMATCH, drifted.reason)
        assertFalse(drifted.semanticRejectionWouldApply)
    }

    @Test
    fun unknownOrInvalidScoreFailsOpenAndInvalidInputsAreRejected() {
        val unknown = policy(enabled = true).evaluate(retrieval(topScore = null), lexicalHitCount = 1)
        val nonFinite = policy(enabled = true).evaluate(retrieval(topScore = Double.NaN), lexicalHitCount = 1)

        assertEquals(KnowledgeRelevanceProductionReason.SCORE_UNKNOWN, unknown.reason)
        assertEquals(KnowledgeRelevanceProductionReason.SCORE_NON_FINITE, nonFinite.reason)
        assertTrue(unknown.preserveLexicalFallback)
        assertTrue(nonFinite.preserveLexicalFallback)
        assertThrows(IllegalArgumentException::class.java) {
            policy(enabled = true).evaluate(retrieval(topScore = 0.60), lexicalHitCount = -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceProductionDesignPolicy(invalidGate(), enforcementEnabled = false)
        }
    }

    private fun policy(enabled: Boolean) = KnowledgeRelevanceProductionDesignPolicy(
        frozenGate = frozenGate(),
        enforcementEnabled = enabled,
    )

    private fun frozenGate() = KnowledgeRelevanceRawTopScoreFrozenGate(
        gateVersion = "stage85-raw-top1-qwen-v1",
        calibrationIdentity = identity("stage85-calibration-v1"),
        validationIdentity = identity("stage85-validation-v1"),
        minimumRawTopScore = 0.70,
    )

    private fun invalidGate() = frozenGate().copy(
        validationIdentity = identity("stage85-calibration-v1"),
    )

    private fun identity(datasetVersion: String) = KnowledgeRelevanceFeatureDatasetIdentity(
        providerId = "provider-a",
        model = "embedding-a",
        datasetVersion = datasetVersion,
    )

    private fun retrieval(
        status: KnowledgeEmbeddingStatus = KnowledgeEmbeddingStatus.USED,
        providerId: String? = "provider-a",
        model: String? = "embedding-a",
        topScore: Double? = 0.60,
    ) = KnowledgeRetrievalRecord(
        id = "retrieval-1",
        query = "query",
        chunkIds = listOf("chunk-1"),
        documentIds = listOf("document-1"),
        sourceConversationId = null,
        sourceRunId = null,
        embeddingProviderId = providerId,
        embeddingModel = model,
        embeddingStatus = status,
        embeddingTopScore = topScore,
        createdAt = 1L,
    )
}
