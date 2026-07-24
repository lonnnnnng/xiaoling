package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRelevanceHoldoutPolicyTest {
    @Test
    fun evaluatesIndependentHoldoutWithFrozenGateWithoutRetuning() {
        val frozenGate = KnowledgeRelevanceFrozenGate(
            gateVersion = "stage82-qwen-v1",
            calibrationIdentity = identity("stage82-calibration-v1"),
            minimumTopScore = 0.70,
            minimumScoreMargin = 0.10,
        )

        val report = KnowledgeRelevanceHoldoutPolicy.evaluate(
            frozenGate = frozenGate,
            holdoutIdentity = identity("stage83-holdout-v1"),
            samples = listOf(
                sample("positive", KnowledgeRelevanceLabel.POSITIVE, 0.80, 0.20),
                sample("positive", KnowledgeRelevanceLabel.POSITIVE, 0.79, 0.19),
                sample("near", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.69, 0.20),
                sample("near", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.75, 0.05),
                sample("far", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.40, 0.03),
                sample("far", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.35, 0.02),
            ),
            criteria = KnowledgeRelevanceHoldoutCriteria(
                minimumPositiveAcceptanceRate = 0.90,
                minimumNearNegativeRejectionRate = 0.80,
                minimumFarNegativeRejectionRate = 0.90,
                minimumDecisionStableRate = 1.0,
            ),
        )

        assertEquals(0.70, report.frozenGate.minimumTopScore, 0.000001)
        assertEquals(0.10, report.frozenGate.minimumScoreMargin, 0.000001)
        assertEquals(1.0, report.positiveAcceptanceRate, 0.000001)
        assertEquals(1.0, report.nearNegativeRejectionRate, 0.000001)
        assertEquals(1.0, report.farNegativeRejectionRate, 0.000001)
        assertEquals(1.0, report.decisionStableRate, 0.000001)
        assertTrue(report.passed)
    }

    @Test
    fun rejectsProviderModelDriftAndCalibrationDatasetReuse() {
        val frozenGate = KnowledgeRelevanceFrozenGate(
            gateVersion = "stage82-qwen-v1",
            calibrationIdentity = identity("stage82-calibration-v1"),
            minimumTopScore = 0.70,
            minimumScoreMargin = 0.10,
        )
        val samples = completeSamples()
        val criteria = completeCriteria()

        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceHoldoutPolicy.evaluate(
                frozenGate = frozenGate,
                holdoutIdentity = identity("stage83-holdout-v1").copy(providerId = "provider-b"),
                samples = samples,
                criteria = criteria,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceHoldoutPolicy.evaluate(
                frozenGate = frozenGate,
                holdoutIdentity = identity("stage83-holdout-v1").copy(model = "embedding-b"),
                samples = samples,
                criteria = criteria,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceHoldoutPolicy.evaluate(
                frozenGate = frozenGate,
                holdoutIdentity = identity("stage82-calibration-v1"),
                samples = samples,
                criteria = criteria,
            )
        }
    }

    @Test
    fun rejectsInvalidFrozenGateCriteriaAndHoldoutSamples() {
        val frozenGate = KnowledgeRelevanceFrozenGate(
            gateVersion = "stage82-qwen-v1",
            calibrationIdentity = identity("stage82-calibration-v1"),
            minimumTopScore = 0.70,
            minimumScoreMargin = 0.10,
        )

        assertThrows(IllegalArgumentException::class.java) {
            evaluate(frozenGate.copy(minimumTopScore = Double.NaN), completeSamples(), completeCriteria())
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(
                frozenGate,
                completeSamples(),
                completeCriteria().copy(minimumPositiveAcceptanceRate = 1.01),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(
                frozenGate,
                listOf(sample("positive", KnowledgeRelevanceLabel.POSITIVE, 0.8, 0.2)),
                completeCriteria(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluate(
                frozenGate,
                completeSamples() + sample("positive", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.6, 0.1),
                completeCriteria(),
            )
        }
    }

    @Test
    fun failedHoldoutDoesNotRetuneFrozenGateFromItsOwnSamples() {
        val frozenGate = KnowledgeRelevanceFrozenGate(
            gateVersion = "stage82-qwen-v1",
            calibrationIdentity = identity("stage82-calibration-v1"),
            minimumTopScore = 0.70,
            minimumScoreMargin = 0.10,
        )

        val report = evaluate(
            frozenGate = frozenGate,
            samples = listOf(
                sample("positive", KnowledgeRelevanceLabel.POSITIVE, 0.69, 0.20),
                sample("near", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.65, 0.15),
                sample("far", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.30, 0.02),
            ),
            criteria = completeCriteria(),
        )

        assertEquals(0.70, report.frozenGate.minimumTopScore, 0.000001)
        assertEquals(0.10, report.frozenGate.minimumScoreMargin, 0.000001)
        assertEquals(0.0, report.positiveAcceptanceRate, 0.000001)
        assertTrue(!report.passed)
    }

    private fun evaluate(
        frozenGate: KnowledgeRelevanceFrozenGate,
        samples: List<KnowledgeRelevanceCalibrationSample>,
        criteria: KnowledgeRelevanceHoldoutCriteria,
    ) = KnowledgeRelevanceHoldoutPolicy.evaluate(
        frozenGate = frozenGate,
        holdoutIdentity = identity("stage83-holdout-v1"),
        samples = samples,
        criteria = criteria,
    )

    private fun completeSamples() = listOf(
        sample("positive", KnowledgeRelevanceLabel.POSITIVE, 0.80, 0.20),
        sample("near", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.60, 0.08),
        sample("far", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.30, 0.02),
    )

    private fun completeCriteria() = KnowledgeRelevanceHoldoutCriteria(
        minimumPositiveAcceptanceRate = 0.90,
        minimumNearNegativeRejectionRate = 0.80,
        minimumFarNegativeRejectionRate = 0.90,
        minimumDecisionStableRate = 1.0,
    )

    private fun identity(datasetVersion: String) = KnowledgeRelevanceDatasetIdentity(
        providerId = "provider-a",
        model = "embedding-a",
        datasetVersion = datasetVersion,
    )

    private fun sample(
        caseId: String,
        label: KnowledgeRelevanceLabel,
        topScore: Double,
        margin: Double,
    ) = KnowledgeRelevanceCalibrationSample(
        caseId = caseId,
        label = label,
        topScore = topScore,
        scoreMargin = margin,
    )
}
