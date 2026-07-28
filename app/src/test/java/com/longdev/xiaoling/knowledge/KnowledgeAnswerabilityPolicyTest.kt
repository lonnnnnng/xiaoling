package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeAnswerabilityPolicyTest {
    @Test
    fun shadowExportPreservesUnknownTelemetryAndIsNeverEvaluationEligible() {
        val row = KnowledgeAnswerabilityShadowExportRow(
            idempotencyFingerprint = "a".repeat(64),
            candidateFingerprint = "b".repeat(64),
            judgeFingerprint = null,
            attemptCount = 0,
            observationStatus = KnowledgeAnswerabilityShadowObservationStatus.UNKNOWN,
            bindingStatus = KnowledgeAnswerabilityShadowBindingStatus.UNKNOWN,
            bindingReason = KnowledgeAnswerabilityShadowBindingReason.MISSING_JUDGE_IDENTITY,
            decision = KnowledgeAnswerabilityDecision.UNKNOWN,
            failureKind = null,
            latencyMs = null,
            firstByteLatencyMs = null,
            promptBytes = null,
            inputTokens = null,
            outputTokens = null,
            totalTokens = null,
            usageSamples = 0,
            failureCounts = emptyMap(),
            recordedAt = 100L,
        )
        val export = KnowledgeAnswerabilityShadowExportEnvelope(
            schemaVersion = 1,
            createdAtEpochMillis = 200L,
            rows = listOf(row),
        )
        assertEquals(KnowledgeAnswerabilityExportKind.SHADOW_OBSERVATIONS, export.kind)
        assertEquals(KnowledgeAnswerabilityExportProvenance.ROOM_V33_ANONYMOUS_LEDGER, export.provenance)
        assertNull(export.rows.single().latencyMs)
        assertFalse(export.eligibleForCalibrationOrValidation())
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeAnswerabilityShadowExportEnvelope(
                schemaVersion = 2,
                createdAtEpochMillis = 200L,
                rows = export.rows,
            )
        }
    }

    @Test
    fun authorizedContentExportRequiresIdentityAuthorizationAndMatchingLabel() {
        val assessment = observation("case-1", KnowledgeRelevanceLabel.POSITIVE)
        val export = KnowledgeAnswerabilityAuthorizedContentExportEnvelope(
            schemaVersion = 1,
            createdAtEpochMillis = 200L,
            datasetIdentity = dataset("calibration-v1"),
            contentAuthorization = KnowledgeAnswerabilityContentAuthorization.EXPLICIT_OFFLINE_EVALUATION,
            cases = listOf(
                KnowledgeAnswerabilityAuthorizedContentCase(
                    caseId = "case-1",
                    question = "q",
                    candidate = "c",
                    answer = "a",
                    references = listOf("r"),
                    label = KnowledgeRelevanceLabel.POSITIVE,
                    assessment = assessment,
                ),
            ),
        )
        assertEquals(KnowledgeAnswerabilityExportKind.AUTHORIZED_CONTENT_CASES, export.kind)
        assertEquals(KnowledgeAnswerabilityExportProvenance.EXPLICIT_USER_AUTHORIZED_CASES, export.provenance)
        assertTrue(export.eligibleForCalibrationOrValidation())
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeAnswerabilityAuthorizedContentCase(
                caseId = "case-1",
                question = "q",
                candidate = "c",
                answer = "a",
                references = listOf("r"),
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                assessment = assessment,
            )
        }
    }
    @Test
    fun strictJsonAndCandidateQuoteMatchingProduceAuditableObservation() {
        val output = KnowledgeAnswerabilityResponseCodec.decode(
            """
            {
              "verdict":"ANSWERED",
              "confidence":0.92,
              "evidence_quotes":["保持 25°C 发酵"],
              "contradiction_detected":false,
              "reason_code":"DIRECT_EVIDENCE"
            }
            """.trimIndent(),
        )

        val observation = KnowledgeAnswerabilityObservation.fromModelOutput(
            caseId = "positive-yogurt",
            label = KnowledgeRelevanceLabel.POSITIVE,
            candidateText = "酸奶需要保持 25°C 发酵，凝固后再冷藏。",
            output = output,
        )

        assertEquals(KnowledgeAnswerabilityVerdict.ANSWERED, observation.verdict)
        assertEquals(1, observation.evidenceQuoteCount)
        assertEquals(1, observation.matchedEvidenceQuoteCount)
        assertTrue(observation.evidenceCoverage > 0.0)
        assertEquals(
            KnowledgeAnswerabilityDecision.ACCEPT,
            observation.decision(
                featureSet = KnowledgeAnswerabilityFeatureSet.VERDICT_EVIDENCE_AND_CONFIDENCE,
                minimumConfidence = 0.8,
                minimumEvidenceCoverage = null,
            ),
        )
    }

    @Test
    fun malformedJsonAndSemanticInconsistencyFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeAnswerabilityResponseCodec.decode("```json {\"verdict\":\"ANSWERED\"} ```")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeAnswerabilityResponseCodec.decode(
                """
                {"verdict":"ANSWERED","confidence":0.8,"evidence_quotes":[],"contradiction_detected":false,"reason_code":"DIRECT_EVIDENCE"}
                """.trimIndent(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeAnswerabilityResponseCodec.decode(
                """
                {"verdict":"NOT_ANSWERED","confidence":0.1,"evidence_quotes":["同主题"],"contradiction_detected":false,"reason_code":"MISSING_FACT"}
                """.trimIndent(),
            )
        }
    }

    @Test
    fun unmatchedEvidenceCannotBeAccepted() {
        val observation = KnowledgeAnswerabilityObservation.fromModelOutput(
            caseId = "unmatched",
            label = KnowledgeRelevanceLabel.POSITIVE,
            candidateText = "文档只说明清洁步骤，没有温度数据。",
            output = KnowledgeAnswerabilityModelOutput(
                verdict = KnowledgeAnswerabilityVerdict.ANSWERED,
                confidence = 0.99,
                evidenceQuotes = listOf("温度必须是 80°C"),
                contradictionDetected = false,
                reasonCode = "DIRECT_EVIDENCE",
            ),
        )

        assertEquals(0, observation.matchedEvidenceQuoteCount)
        assertEquals(
            KnowledgeAnswerabilityDecision.REJECT,
            observation.decision(
                KnowledgeAnswerabilityFeatureSet.VERDICT_AND_EXACT_EVIDENCE,
                minimumConfidence = null,
                minimumEvidenceCoverage = null,
            ),
        )
    }

    @Test
    fun calibrationFreezesConfidenceAndValidationCannotRetuneIt() {
        val report = KnowledgeAnswerabilityPolicy.compare(
            calibrationIdentity = dataset("stage92-calibration-v1"),
            validationIdentity = dataset("stage92-validation-v1"),
            calibrationSamples = listOf(
                observation("c-positive", KnowledgeRelevanceLabel.POSITIVE, confidence = 0.8),
                observation("c-near", KnowledgeRelevanceLabel.NEAR_NEGATIVE, verdict = KnowledgeAnswerabilityVerdict.NOT_ANSWERED),
                observation("c-far", KnowledgeRelevanceLabel.FAR_NEGATIVE, verdict = KnowledgeAnswerabilityVerdict.NOT_ANSWERED),
            ),
            validationSamples = listOf(
                observation("v-positive", KnowledgeRelevanceLabel.POSITIVE, confidence = 0.6),
                observation("v-near", KnowledgeRelevanceLabel.NEAR_NEGATIVE, verdict = KnowledgeAnswerabilityVerdict.NOT_ANSWERED),
                observation("v-far", KnowledgeRelevanceLabel.FAR_NEGATIVE, verdict = KnowledgeAnswerabilityVerdict.NOT_ANSWERED),
            ),
        )

        val confidenceGate = report.calibrationGates.getValue(
            KnowledgeAnswerabilityFeatureSet.VERDICT_EVIDENCE_AND_CONFIDENCE,
        )
        val validation = report.validationEvaluations.getValue(
            KnowledgeAnswerabilityFeatureSet.VERDICT_EVIDENCE_AND_CONFIDENCE,
        )
        assertEquals(0.8, confidenceGate.minimumConfidence!!, 0.000001)
        assertEquals(confidenceGate.minimumConfidence, validation.minimumConfidence)
        assertEquals(0.0, validation.positiveAcceptanceRate, 0.000001)
    }

    @Test
    fun unknownIsNotCountedAsNegativeRejection() {
        val report = KnowledgeAnswerabilityPolicy.compare(
            calibrationIdentity = dataset("stage92-calibration-v1"),
            validationIdentity = dataset("stage92-validation-v1"),
            calibrationSamples = listOf(
                observation("c-positive", KnowledgeRelevanceLabel.POSITIVE),
                observation("c-near", KnowledgeRelevanceLabel.NEAR_NEGATIVE, verdict = KnowledgeAnswerabilityVerdict.NOT_ANSWERED),
                observation("c-far", KnowledgeRelevanceLabel.FAR_NEGATIVE, verdict = KnowledgeAnswerabilityVerdict.UNKNOWN),
            ),
            validationSamples = listOf(
                observation("v-positive", KnowledgeRelevanceLabel.POSITIVE),
                observation("v-near", KnowledgeRelevanceLabel.NEAR_NEGATIVE, verdict = KnowledgeAnswerabilityVerdict.UNKNOWN),
                observation("v-far", KnowledgeRelevanceLabel.FAR_NEGATIVE, verdict = KnowledgeAnswerabilityVerdict.NOT_ANSWERED),
            ),
        )

        val strict = report.validationEvaluations.getValue(
            KnowledgeAnswerabilityFeatureSet.VERDICT_AND_EXACT_EVIDENCE,
        )
        assertEquals(0.0, strict.nearNegativeRejectionRate, 0.000001)
        assertEquals(2.0 / 3.0, strict.knownDecisionRate, 0.000001)
        assertEquals(1.0 / 3.0, strict.unknownRate, 0.000001)
        assertFalse(
            strict.meets(
                KnowledgeAnswerabilityCriteria(
                    minimumPositiveAcceptanceRate = 0.0,
                    minimumNearNegativeRejectionRate = 0.8,
                    minimumFarNegativeRejectionRate = 0.0,
                    minimumDecisionStableRate = 1.0,
                    minimumKnownDecisionRate = 0.9,
                ),
            ),
        )
    }

    @Test
    fun identityDatasetAndInputDriftAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeAnswerabilityPolicy.compare(
                calibrationIdentity = dataset("stage92-same-v1"),
                validationIdentity = dataset("stage92-same-v1"),
                calibrationSamples = completeSamples("c"),
                validationSamples = completeSamples("v"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeAnswerabilityPolicy.compare(
                calibrationIdentity = dataset("stage92-calibration-v1"),
                validationIdentity = dataset(
                    version = "stage92-validation-v1",
                    model = "other-model",
                ),
                calibrationSamples = completeSamples("c"),
                validationSamples = completeSamples("v"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeAnswerabilityPolicy.selectCalibrationGates(
                listOf(
                    observation("same", KnowledgeRelevanceLabel.POSITIVE),
                    observation("same", KnowledgeRelevanceLabel.NEAR_NEGATIVE, verdict = KnowledgeAnswerabilityVerdict.NOT_ANSWERED),
                    observation("far", KnowledgeRelevanceLabel.FAR_NEGATIVE, verdict = KnowledgeAnswerabilityVerdict.NOT_ANSWERED),
                ),
            )
        }
    }

    @Test
    fun allFeatureFamiliesAreFrozenAndPartialOrContradictoryAnswersReject() {
        val report = KnowledgeAnswerabilityPolicy.compare(
            calibrationIdentity = dataset("stage92-calibration-v1"),
            validationIdentity = dataset("stage92-validation-v1"),
            calibrationSamples = completeSamples("c"),
            validationSamples = completeSamples("v"),
        )
        assertEquals(
            KnowledgeAnswerabilityFeatureSet.entries.toSet(),
            report.calibrationGates.keys,
        )
        assertEquals(
            KnowledgeAnswerabilityFeatureSet.entries.toSet(),
            report.validationEvaluations.keys,
        )

        val partial = observation(
            caseId = "partial",
            label = KnowledgeRelevanceLabel.POSITIVE,
            verdict = KnowledgeAnswerabilityVerdict.PARTIALLY_ANSWERED,
        )
        val contradictory = observation(
            caseId = "contradictory",
            label = KnowledgeRelevanceLabel.POSITIVE,
            contradictionDetected = true,
        )
        assertEquals(
            KnowledgeAnswerabilityDecision.REJECT,
            partial.decision(
                KnowledgeAnswerabilityFeatureSet.VERDICT_AND_EXACT_EVIDENCE,
                null,
                null,
            ),
        )
        assertEquals(
            KnowledgeAnswerabilityDecision.REJECT,
            contradictory.decision(
                KnowledgeAnswerabilityFeatureSet.VERDICT_AND_EXACT_EVIDENCE,
                null,
                null,
            ),
        )
    }

    private fun completeSamples(prefix: String) = listOf(
        observation("$prefix-positive", KnowledgeRelevanceLabel.POSITIVE),
        observation("$prefix-near", KnowledgeRelevanceLabel.NEAR_NEGATIVE, verdict = KnowledgeAnswerabilityVerdict.NOT_ANSWERED),
        observation("$prefix-far", KnowledgeRelevanceLabel.FAR_NEGATIVE, verdict = KnowledgeAnswerabilityVerdict.NOT_ANSWERED),
    )

    private fun observation(
        caseId: String,
        label: KnowledgeRelevanceLabel,
        verdict: KnowledgeAnswerabilityVerdict = KnowledgeAnswerabilityVerdict.ANSWERED,
        confidence: Double = 0.9,
        evidenceQuoteCount: Int = if (verdict == KnowledgeAnswerabilityVerdict.ANSWERED) 1 else 0,
        matchedEvidenceQuoteCount: Int = evidenceQuoteCount,
        evidenceCoverage: Double = if (evidenceQuoteCount == 0) 0.0 else 0.25,
        contradictionDetected: Boolean = false,
    ) = KnowledgeAnswerabilityObservation(
        caseId = caseId,
        label = label,
        verdict = verdict,
        confidence = confidence,
        evidenceQuoteCount = evidenceQuoteCount,
        matchedEvidenceQuoteCount = matchedEvidenceQuoteCount,
        evidenceCoverage = evidenceCoverage,
        contradictionDetected = contradictionDetected,
        reasonCode = if (verdict == KnowledgeAnswerabilityVerdict.NOT_ANSWERED) "MISSING_FACT" else "DIRECT_EVIDENCE",
    )

    private fun dataset(
        version: String,
        model: String = "gpt-5.5",
        fingerprint: String = "fingerprint-a",
    ) = KnowledgeAnswerabilityDatasetIdentity(
        judgeIdentity = KnowledgeAnswerabilityJudgeIdentity(
            providerId = "redmi-answerability-judge-v1",
            model = model,
            configurationFingerprint = fingerprint,
            promptVersion = "stage92-answerability-json-v1",
        ),
        datasetVersion = version,
    )
}
