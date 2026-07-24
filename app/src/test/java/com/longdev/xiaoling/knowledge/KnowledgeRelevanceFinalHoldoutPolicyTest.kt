package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRelevanceFinalHoldoutPolicyTest {
    @Test
    fun evaluatesThirdDatasetWithFrozenRawTopScoreWithoutRetuning() {
        val frozenGate = frozenGate()

        val report = KnowledgeRelevanceFinalHoldoutPolicy.evaluate(
            frozenGate = frozenGate,
            holdoutIdentity = identity("stage86-final-holdout-v1"),
            samples = completeSamples(),
            criteria = completeCriteria(),
        )

        assertEquals(frozenGate, report.frozenGate)
        assertEquals("stage86-final-holdout-v1", report.holdoutIdentity.datasetVersion)
        assertEquals(1.0, report.positiveAcceptanceRate, 0.000001)
        assertEquals(1.0, report.nearNegativeRejectionRate, 0.000001)
        assertEquals(1.0, report.farNegativeRejectionRate, 0.000001)
        assertEquals(1.0, report.decisionStableRate, 0.000001)
        assertTrue(report.passed)
    }

    @Test
    fun rejectsIdentityDriftAndAnyReusedStage85Dataset() {
        val frozenGate = frozenGate()

        assertThrows(IllegalArgumentException::class.java) {
            evaluate(frozenGate, identity("stage86-final-holdout-v1").copy(providerId = "provider-b"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(frozenGate, identity("stage86-final-holdout-v1").copy(model = "embedding-b"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(frozenGate, identity("stage85-calibration-v1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(frozenGate, identity("stage85-validation-v1"))
        }
    }

    @Test
    fun rejectsInvalidFrozenIdentityCriteriaAndSamples() {
        val frozenGate = frozenGate()

        assertThrows(IllegalArgumentException::class.java) {
            evaluate(frozenGate.copy(gateVersion = ""), identity("stage86-final-holdout-v1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(frozenGate.copy(minimumRawTopScore = Double.NaN), identity("stage86-final-holdout-v1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(
                frozenGate.copy(validationDatasetVersion = "stage85-calibration-v1"),
                identity("stage86-final-holdout-v1"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceFinalHoldoutPolicy.evaluate(
                frozenGate = frozenGate,
                holdoutIdentity = identity("stage86-final-holdout-v1"),
                samples = completeSamples(),
                criteria = completeCriteria().copy(minimumPositiveAcceptanceRate = 1.1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(frozenGate, identity("stage86-final-holdout-v1"), completeSamples().dropLast(2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(
                frozenGate,
                identity("stage86-final-holdout-v1"),
                completeSamples() + sample("positive", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.2),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(
                frozenGate,
                identity("stage86-final-holdout-v1"),
                completeSamples().mapIndexed { index, sample ->
                    if (index == 0) sample.copy(features = sample.features.copy(rawTopScore = Double.NaN)) else sample
                },
            )
        }
    }

    @Test
    fun failedFinalHoldoutKeepsFrozenThresholdUnchanged() {
        val frozenGate = frozenGate()
        val samples = completeSamples().map { sample ->
            if (sample.label == KnowledgeRelevanceLabel.POSITIVE) {
                sample.copy(features = sample.features.copy(rawTopScore = 0.69))
            } else {
                sample
            }
        }

        val report = evaluate(frozenGate, identity("stage86-final-holdout-v1"), samples)

        assertEquals(0.70, report.frozenGate.minimumRawTopScore, 0.000001)
        assertEquals(0.0, report.positiveAcceptanceRate, 0.000001)
        assertFalse(report.passed)
    }

    private fun evaluate(
        frozenGate: KnowledgeRelevanceRawTopScoreFrozenGate,
        holdoutIdentity: KnowledgeRelevanceFeatureDatasetIdentity,
        samples: List<KnowledgeRelevanceFeatureSample> = completeSamples(),
    ) = KnowledgeRelevanceFinalHoldoutPolicy.evaluate(
        frozenGate = frozenGate,
        holdoutIdentity = holdoutIdentity,
        samples = samples,
        criteria = completeCriteria(),
    )

    private fun frozenGate() = KnowledgeRelevanceRawTopScoreFrozenGate(
        gateVersion = "stage85-raw-top1-qwen-v1",
        calibrationIdentity = identity("stage85-calibration-v1"),
        validationDatasetVersion = "stage85-validation-v1",
        minimumRawTopScore = 0.70,
    )

    private fun identity(datasetVersion: String) = KnowledgeRelevanceFeatureDatasetIdentity(
        providerId = "provider-a",
        model = "embedding-a",
        datasetVersion = datasetVersion,
    )

    private fun completeCriteria() = KnowledgeRelevanceFinalHoldoutCriteria(
        minimumPositiveAcceptanceRate = 0.90,
        minimumNearNegativeRejectionRate = 0.80,
        minimumFarNegativeRejectionRate = 0.90,
        minimumDecisionStableRate = 1.0,
    )

    private fun completeSamples() = listOf(
        sample("positive", KnowledgeRelevanceLabel.POSITIVE, 0.80),
        sample("positive", KnowledgeRelevanceLabel.POSITIVE, 0.81),
        sample("near", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.55),
        sample("near", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.54),
        sample("far", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.20),
        sample("far", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.19),
    )

    private fun sample(
        caseId: String,
        label: KnowledgeRelevanceLabel,
        rawTopScore: Double,
    ) = KnowledgeRelevanceFeatureSample(
        caseId = caseId,
        label = label,
        features = KnowledgeRelevanceFeatureVector(
            rawTopScore = rawTopScore,
            scoreMargin = 0.1,
            topScoreZScore = 1.0,
        ),
    )
}
