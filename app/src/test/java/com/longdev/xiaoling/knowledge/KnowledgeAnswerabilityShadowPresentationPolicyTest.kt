package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KnowledgeAnswerabilityShadowPresentationPolicyTest {
    @Test
    fun acceptedEvidenceShowsDirectAnswerWithoutChangingReferences() {
        val references = listOf(reference())
        val result = KnowledgeAnswerabilityShadowPresentationPolicy.present(
            references = references,
            observation = observation(
                verdict = KnowledgeAnswerabilityVerdict.ANSWERED,
                confidence = 0.95,
                evidenceQuoteCount = 1,
                matchedEvidenceQuoteCount = 1,
                evidenceCoverage = 0.4,
            ),
            gate = exactEvidenceGate(),
        )

        assertEquals(KnowledgeAnswerabilityDecision.ACCEPT, result.decision)
        assertEquals(KnowledgeAnswerabilityUserState.DIRECTLY_ANSWERED, result.notice.state)
        assertEquals(references, result.references)
        assertFalse(result.enforcementApplied)
    }

    @Test
    fun partialAndNotAnsweredUseDistinctUserStates() {
        val partial = present(KnowledgeAnswerabilityVerdict.PARTIALLY_ANSWERED)
        val notAnswered = present(KnowledgeAnswerabilityVerdict.NOT_ANSWERED)

        assertEquals(KnowledgeAnswerabilityUserState.PARTIALLY_ANSWERED, partial.notice.state)
        assertEquals("本地知识仅覆盖部分问题", partial.notice.title)
        assertEquals(KnowledgeAnswerabilityUserState.NOT_ANSWERED, notAnswered.notice.state)
        assertEquals("本地知识未直接回答问题", notAnswered.notice.title)
    }

    @Test
    fun missingObservationOrGateStaysUnknownAndKeepsReferences() {
        val references = listOf(reference())
        val missingObservation = KnowledgeAnswerabilityShadowPresentationPolicy.present(
            references = references,
            observation = null,
            gate = exactEvidenceGate(),
        )
        val missingGate = KnowledgeAnswerabilityShadowPresentationPolicy.present(
            references = references,
            observation = observation(KnowledgeAnswerabilityVerdict.ANSWERED),
            gate = null,
        )

        assertEquals(KnowledgeAnswerabilityDecision.UNKNOWN, missingObservation.decision)
        assertEquals(KnowledgeAnswerabilityDecision.UNKNOWN, missingGate.decision)
        assertEquals(references, missingObservation.references)
        assertEquals(references, missingGate.references)
    }

    @Test
    fun contradictionAndFabricatedQuoteNeverAppearAsAnswered() {
        val contradiction = KnowledgeAnswerabilityShadowPresentationPolicy.present(
            references = listOf(reference()),
            observation = observation(
                verdict = KnowledgeAnswerabilityVerdict.ANSWERED,
                contradictionDetected = true,
            ),
            gate = exactEvidenceGate(),
        )
        val fabricatedQuote = KnowledgeAnswerabilityShadowPresentationPolicy.present(
            references = listOf(reference()),
            observation = observation(
                verdict = KnowledgeAnswerabilityVerdict.ANSWERED,
                evidenceQuoteCount = 1,
                matchedEvidenceQuoteCount = 0,
            ),
            gate = exactEvidenceGate(),
        )

        assertEquals(KnowledgeAnswerabilityUserState.CONTRADICTORY, contradiction.notice.state)
        assertEquals(KnowledgeAnswerabilityUserState.EVIDENCE_MISMATCH, fabricatedQuote.notice.state)
        assertEquals(KnowledgeAnswerabilityDecision.REJECT, contradiction.decision)
        assertEquals(KnowledgeAnswerabilityDecision.REJECT, fabricatedQuote.decision)
    }

    @Test
    fun belowFrozenThresholdIsOnlyAWarningAndDoesNotFilterReferences() {
        val references = listOf(reference())
        val result = KnowledgeAnswerabilityShadowPresentationPolicy.present(
            references = references,
            observation = observation(
                verdict = KnowledgeAnswerabilityVerdict.ANSWERED,
                confidence = 0.79,
                evidenceQuoteCount = 1,
                matchedEvidenceQuoteCount = 1,
                evidenceCoverage = 0.5,
            ),
            gate = confidenceGate(minimumConfidence = 0.8),
        )

        assertEquals(KnowledgeAnswerabilityDecision.REJECT, result.decision)
        assertEquals(KnowledgeAnswerabilityUserState.BELOW_FROZEN_GATE, result.notice.state)
        assertEquals(references, result.references)
        assertFalse(result.enforcementApplied)
    }

    private fun present(
        verdict: KnowledgeAnswerabilityVerdict,
    ) = KnowledgeAnswerabilityShadowPresentationPolicy.present(
        references = listOf(reference()),
        observation = observation(verdict),
        gate = exactEvidenceGate(),
    )

    private fun observation(
        verdict: KnowledgeAnswerabilityVerdict,
        confidence: Double = 0.9,
        evidenceQuoteCount: Int = if (verdict == KnowledgeAnswerabilityVerdict.ANSWERED) 1 else 0,
        matchedEvidenceQuoteCount: Int = evidenceQuoteCount,
        evidenceCoverage: Double = if (evidenceQuoteCount > 0) 0.3 else 0.0,
        contradictionDetected: Boolean = false,
    ) = KnowledgeAnswerabilityObservation(
        caseId = "stage93-shadow-case",
        label = KnowledgeRelevanceLabel.POSITIVE,
        verdict = verdict,
        confidence = confidence,
        evidenceQuoteCount = evidenceQuoteCount,
        matchedEvidenceQuoteCount = matchedEvidenceQuoteCount,
        evidenceCoverage = evidenceCoverage,
        contradictionDetected = contradictionDetected,
        reasonCode = "SHADOW_TEST",
    )

    private fun exactEvidenceGate() = gate(
        featureSet = KnowledgeAnswerabilityFeatureSet.VERDICT_AND_EXACT_EVIDENCE,
        minimumConfidence = null,
    )

    private fun confidenceGate(
        minimumConfidence: Double,
    ) = gate(
        featureSet = KnowledgeAnswerabilityFeatureSet.VERDICT_EVIDENCE_AND_CONFIDENCE,
        minimumConfidence = minimumConfidence,
    )

    private fun gate(
        featureSet: KnowledgeAnswerabilityFeatureSet,
        minimumConfidence: Double?,
    ) = KnowledgeAnswerabilityGate(
        featureSet = featureSet,
        minimumConfidence = minimumConfidence,
        minimumEvidenceCoverage = null,
        calibrationPositiveAcceptanceRate = 1.0,
        calibrationNearNegativeRejectionRate = 1.0,
        calibrationFarNegativeRejectionRate = 1.0,
        calibrationDecisionStableRate = 1.0,
        calibrationKnownDecisionRate = 1.0,
        calibrationUnknownRate = 0.0,
        calibrationBalancedAccuracy = 1.0,
    )

    private fun reference() = KnowledgeReference(
        retrievalId = "retrieval-stage93",
        documentId = "document-stage93",
        documentName = "stage93.md",
        documentRevision = 1,
        chunkId = "chunk-stage93-r1-0",
        chunkSequence = 0,
        startOffset = 0,
        endOffset = 32,
    )
}
