package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeAnswerabilityShadowBindingPolicyTest {
    @Test
    fun bindsAcceptedObservationWithoutChangingReferenceOrderOrEnforcement() {
        val first = reference("first", 0, 20)
        val second = reference("second", 20, 40)
        val candidate = KnowledgeAnswerabilityShadowCandidate(
            sourceRunId = "run-shadow",
            question = "什么是本地知识？",
            candidateText = "本地知识是可回查的事实。",
            references = listOf(first, second),
        )
        val frozen = frozenBinding(KnowledgeAnswerabilityFeatureSet.VERDICT_AND_EXACT_EVIDENCE)
        val result = KnowledgeAnswerabilityShadowBindingPolicy.bind(
            candidate = candidate,
            actualJudgeIdentity = frozen.judgeIdentity,
            frozenBinding = frozen,
            observation = observation(),
            observedAt = 1_000L,
        )

        assertEquals(KnowledgeAnswerabilityShadowBindingStatus.BOUND, result.status)
        assertEquals(KnowledgeAnswerabilityDecision.ACCEPT, result.decision)
        assertEquals(listOf(first, second), result.references)
        assertFalse(result.enforcementApplied)
        assertEquals(1_000L, result.observedAt)
    }

    @Test
    fun coverageFeatureFamilyCannotEnterMessageShadowBinding() {
        val frozen = frozenBinding(KnowledgeAnswerabilityFeatureSet.VERDICT_EVIDENCE_CONFIDENCE_AND_COVERAGE)
        val result = KnowledgeAnswerabilityShadowBindingPolicy.bind(
            candidate = candidate(),
            actualJudgeIdentity = frozen.judgeIdentity,
            frozenBinding = frozen,
            observation = observation(),
            observedAt = 1_001L,
        )

        assertEquals(KnowledgeAnswerabilityShadowBindingStatus.UNKNOWN, result.status)
        assertEquals(
            KnowledgeAnswerabilityShadowBindingReason.UNSUPPORTED_FEATURE_SET,
            result.reason,
        )
        assertEquals(KnowledgeAnswerabilityDecision.UNKNOWN, result.decision)
        assertEquals(candidate().references, result.references)
    }

    @Test
    fun judgeIdentityDriftBecomesUnknownAndKeepsEvidence() {
        val frozen = frozenBinding(KnowledgeAnswerabilityFeatureSet.VERDICT_EVIDENCE_AND_CONFIDENCE)
        val result = KnowledgeAnswerabilityShadowBindingPolicy.bind(
            candidate = candidate(),
            actualJudgeIdentity = frozen.judgeIdentity.copy(model = "other-model"),
            frozenBinding = frozen,
            observation = observation(confidence = 0.99),
            observedAt = 1_002L,
        )

        assertEquals(KnowledgeAnswerabilityShadowBindingStatus.UNKNOWN, result.status)
        assertEquals(KnowledgeAnswerabilityShadowBindingReason.JUDGE_IDENTITY_MISMATCH, result.reason)
        assertEquals(candidate().references, result.references)
        assertTrue(result.notice.title.contains("尚未确认"))
    }

    @Test
    fun missingObservationIsUnknownInsteadOfRejectingAnswer() {
        val candidate = candidate()
        val frozen = frozenBinding(KnowledgeAnswerabilityFeatureSet.VERDICT_AND_EXACT_EVIDENCE)
        val result = KnowledgeAnswerabilityShadowBindingPolicy.bind(
            candidate = candidate,
            actualJudgeIdentity = frozen.judgeIdentity,
            frozenBinding = frozen,
            observation = null,
            observedAt = 1_003L,
        )

        assertEquals(KnowledgeAnswerabilityShadowBindingStatus.UNKNOWN, result.status)
        assertEquals(KnowledgeAnswerabilityShadowBindingReason.MISSING_OBSERVATION, result.reason)
        assertEquals(KnowledgeAnswerabilityDecision.UNKNOWN, result.decision)
        assertEquals(candidate.references, result.references)
        assertNull(result.observedAt)
    }

    @Test
    fun incompleteCandidateBecomesUnknownInsteadOfThrowing() {
        val reference = reference("invalid", 0, 20)
        val frozen = frozenBinding(KnowledgeAnswerabilityFeatureSet.VERDICT_AND_EXACT_EVIDENCE)
        val result = KnowledgeAnswerabilityShadowBindingPolicy.bind(
            candidate = candidate().copy(sourceRunId = "", references = listOf(reference)),
            actualJudgeIdentity = frozen.judgeIdentity,
            frozenBinding = frozen,
            observation = observation(),
            observedAt = 1_004L,
        )

        assertEquals(KnowledgeAnswerabilityShadowBindingStatus.UNKNOWN, result.status)
        assertEquals(KnowledgeAnswerabilityShadowBindingReason.INVALID_CANDIDATE, result.reason)
        assertEquals(listOf(reference), result.references)
        assertFalse(result.enforcementApplied)
    }

    @Test
    fun bindingKeepsReferenceSnapshotWhenCallerMutatesOriginalList() {
        val originalReferences = mutableListOf(reference("stable", 0, 20))
        val frozen = frozenBinding(KnowledgeAnswerabilityFeatureSet.VERDICT_AND_EXACT_EVIDENCE)
        val result = KnowledgeAnswerabilityShadowBindingPolicy.bind(
            candidate = candidate().copy(references = originalReferences),
            actualJudgeIdentity = frozen.judgeIdentity,
            frozenBinding = frozen,
            observation = observation(),
            observedAt = 1_005L,
        )

        originalReferences += reference("late", 20, 40)

        assertEquals(1, result.references.size)
        assertEquals(result.references, result.candidate.references)
    }

    @Test
    fun frozenBindingRejectsDatasetJudgeIdentityDrift() {
        val judgeIdentity = judgeIdentity()
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeAnswerabilityFrozenBinding(
                calibrationIdentity = KnowledgeAnswerabilityDatasetIdentity(
                    judgeIdentity = judgeIdentity,
                    datasetVersion = "stage92-calibration-v1",
                ),
                validationIdentity = KnowledgeAnswerabilityDatasetIdentity(
                    judgeIdentity = judgeIdentity.copy(model = "other-model"),
                    datasetVersion = "stage92-validation-v1",
                ),
                gate = gate(KnowledgeAnswerabilityFeatureSet.VERDICT_AND_EXACT_EVIDENCE),
            )
        }
    }

    private fun candidate() = KnowledgeAnswerabilityShadowCandidate(
        sourceRunId = "run-shadow",
        question = "什么是本地知识？",
        candidateText = "本地知识是可回查的事实。",
        references = listOf(reference("only", 0, 20)),
    )

    private fun observation(confidence: Double = 0.95) = KnowledgeAnswerabilityObservation(
        caseId = "run-shadow",
        label = KnowledgeRelevanceLabel.POSITIVE,
        verdict = KnowledgeAnswerabilityVerdict.ANSWERED,
        confidence = confidence,
        evidenceQuoteCount = 1,
        matchedEvidenceQuoteCount = 1,
        evidenceCoverage = 1.0,
        contradictionDetected = false,
        reasonCode = "DIRECT_EVIDENCE",
    )

    private fun frozenBinding(featureSet: KnowledgeAnswerabilityFeatureSet) =
        KnowledgeAnswerabilityFrozenBinding(
            calibrationIdentity = KnowledgeAnswerabilityDatasetIdentity(
                judgeIdentity = judgeIdentity(),
                datasetVersion = "stage92-calibration-v1",
            ),
            validationIdentity = KnowledgeAnswerabilityDatasetIdentity(
                judgeIdentity = judgeIdentity(),
                datasetVersion = "stage92-validation-v1",
            ),
            gate = gate(featureSet),
        )

    private fun judgeIdentity() = KnowledgeAnswerabilityJudgeIdentity(
        providerId = "redmi-answerability-judge-v1",
        model = "gpt-5.5",
        configurationFingerprint = "fingerprint-v1",
        promptVersion = "stage92-answerability-json-v1",
    )

    private fun gate(featureSet: KnowledgeAnswerabilityFeatureSet) = KnowledgeAnswerabilityGate(
        featureSet = featureSet,
        minimumConfidence = if (featureSet.usesConfidence) 0.8 else null,
        minimumEvidenceCoverage = if (featureSet.usesEvidenceCoverage) 0.5 else null,
        calibrationPositiveAcceptanceRate = 1.0,
        calibrationNearNegativeRejectionRate = 1.0,
        calibrationFarNegativeRejectionRate = 1.0,
        calibrationDecisionStableRate = 1.0,
        calibrationKnownDecisionRate = 1.0,
        calibrationUnknownRate = 0.0,
        calibrationBalancedAccuracy = 1.0,
    )

    private fun reference(id: String, start: Int, end: Int) = KnowledgeReference(
        retrievalId = "retrieval-shadow",
        documentId = "document-shadow",
        documentName = "shadow.md",
        documentRevision = 1,
        chunkId = "chunk-$id",
        chunkSequence = if (start == 0) 0 else 1,
        startOffset = start,
        endOffset = end,
    )
}
