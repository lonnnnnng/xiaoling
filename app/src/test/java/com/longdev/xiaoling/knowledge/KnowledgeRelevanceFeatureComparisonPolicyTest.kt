package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KnowledgeRelevanceFeatureComparisonPolicyTest {
    @Test
    fun comparesAllSingleAndCombinedFeatureFamilies() {
        val report = KnowledgeRelevanceFeatureComparisonPolicy.compare(
            calibrationIdentity = calibrationIdentity(),
            validationIdentity = validationIdentity(),
            calibrationSamples = calibrationSamples(),
            validationSamples = validationSamples(),
        )

        assertEquals(KnowledgeRelevanceFeatureSet.entries.toSet(), report.calibrationGates.keys)
        assertEquals(KnowledgeRelevanceFeatureSet.entries.toSet(), report.validationEvaluations.keys)
        KnowledgeRelevanceFeatureSet.entries.forEach { featureSet ->
            val gate = report.calibrationGates.getValue(featureSet)
            val validation = report.validationEvaluations.getValue(featureSet)
            assertEquals(featureSet, gate.featureSet)
            assertEquals(featureSet, validation.featureSet)
            assertEquals(gate.thresholds, validation.thresholds)
            assertEquals(featureSet.features.size, gate.thresholds.size)
        }
    }

    @Test
    fun validationDoesNotRetuneCalibrationThresholds() {
        val calibration = calibrationSamples()
        val report = KnowledgeRelevanceFeatureComparisonPolicy.compare(
            calibrationIdentity = calibrationIdentity(),
            validationIdentity = validationIdentity(),
            calibrationSamples = calibration,
            validationSamples = validationSamples(),
        )
        val rawGate = report.calibrationGates.getValue(KnowledgeRelevanceFeatureSet.RAW_TOP_SCORE)
        val validation = report.validationEvaluations.getValue(KnowledgeRelevanceFeatureSet.RAW_TOP_SCORE)

        assertEquals(0.9, rawGate.thresholds.getValue(KnowledgeRelevanceFeature.RAW_TOP_SCORE), 0.000001)
        assertEquals(rawGate.thresholds, validation.thresholds)
        assertNotEquals(rawGate.calibrationBalancedAccuracy, validation.balancedAccuracy)
    }

    @Test
    fun rejectsMissingLabelNonFiniteValueAndLabelDrift() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceFeatureComparisonPolicy.compare(
                calibrationIdentity = calibrationIdentity(),
                validationIdentity = validationIdentity(),
                calibrationSamples = calibrationSamples().filterNot { it.label == KnowledgeRelevanceLabel.FAR_NEGATIVE },
                validationSamples = validationSamples(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceFeatureComparisonPolicy.compare(
                calibrationIdentity = calibrationIdentity(),
                validationIdentity = validationIdentity(),
                calibrationSamples = calibrationSamples() + sample(
                    caseId = "non-finite",
                    label = KnowledgeRelevanceLabel.POSITIVE,
                    topScore = Double.NaN,
                    margin = 0.1,
                    zScore = 1.0,
                ),
                validationSamples = validationSamples(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceFeatureComparisonPolicy.compare(
                calibrationIdentity = calibrationIdentity(),
                validationIdentity = validationIdentity(),
                calibrationSamples = calibrationSamples() + sample(
                    caseId = "positive-1",
                    label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                    topScore = 0.2,
                    margin = 0.1,
                    zScore = 0.4,
                ),
                validationSamples = validationSamples(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceFeatureComparisonPolicy.compare(
                calibrationIdentity = calibrationIdentity(),
                validationIdentity = calibrationIdentity(),
                calibrationSamples = calibrationSamples(),
                validationSamples = validationSamples(),
            )
        }
    }

    private fun calibrationSamples(): List<KnowledgeRelevanceFeatureSample> = listOf(
        sample("positive-1", KnowledgeRelevanceLabel.POSITIVE, 0.95, 0.4, 2.0),
        sample("positive-2", KnowledgeRelevanceLabel.POSITIVE, 0.9, 0.3, 1.7),
        sample("near-1", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.6, 0.1, 0.6),
        sample("near-2", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.5, 0.08, 0.4),
        sample("far-1", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.2, 0.03, 0.1),
        sample("far-2", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.1, 0.02, 0.05),
    )

    private fun validationSamples(): List<KnowledgeRelevanceFeatureSample> = listOf(
        sample("validation-positive", KnowledgeRelevanceLabel.POSITIVE, 0.85, 0.35, 1.8),
        sample("validation-near", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.55, 0.09, 0.5),
        sample("validation-far", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.15, 0.02, 0.08),
    )

    private fun calibrationIdentity() = KnowledgeRelevanceFeatureDatasetIdentity(
        providerId = "provider-a",
        model = "embedding-a",
        datasetVersion = "calibration-v1",
    )

    private fun validationIdentity() = KnowledgeRelevanceFeatureDatasetIdentity(
        providerId = "provider-a",
        model = "embedding-a",
        datasetVersion = "validation-v1",
    )

    private fun sample(
        caseId: String,
        label: KnowledgeRelevanceLabel,
        topScore: Double,
        margin: Double,
        zScore: Double,
    ) = KnowledgeRelevanceFeatureSample(
        caseId = caseId,
        label = label,
        features = KnowledgeRelevanceFeatureVector(
            rawTopScore = topScore,
            scoreMargin = margin,
            topScoreZScore = zScore,
        ),
    )
}
