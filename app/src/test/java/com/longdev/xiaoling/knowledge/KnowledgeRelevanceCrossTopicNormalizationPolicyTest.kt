package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRelevanceCrossTopicNormalizationPolicyTest {
    @Test
    fun additiveScoreShiftKeepsRelativeFeaturesStable() {
        val baseline = normalizedFromScores(
            topScore = 0.8,
            scoreMean = 0.6,
            margin = 0.15,
            standardDeviation = 0.1,
        )
        val shifted = normalizedFromScores(
            topScore = 0.3,
            scoreMean = 0.1,
            margin = 0.15,
            standardDeviation = 0.1,
        )

        assertEquals(baseline.topScoreMeanGap, shifted.topScoreMeanGap, 0.000001)
        assertEquals(
            baseline.marginOverStandardDeviation,
            shifted.marginOverStandardDeviation,
            0.000001,
        )
    }

    @Test
    fun calibrationFreezesThresholdsBeforeValidation() {
        val report = KnowledgeRelevanceCrossTopicNormalizationPolicy.compare(
            productionIdentity = productionIdentity(),
            calibrationIdentity = dataset("stage91-calibration-v1"),
            validationIdentity = dataset("stage91-validation-v1"),
            calibrationSamples = samples("calibration", positiveGap = 0.30),
            validationSamples = samples("validation", positiveGap = 0.25),
        )

        val gapGate = report.calibrationGates.getValue(
            KnowledgeRelevanceCrossTopicFeatureSet.TOP_SCORE_MEAN_GAP,
        )
        val gapEvaluation = report.validationEvaluations.getValue(
            KnowledgeRelevanceCrossTopicFeatureSet.TOP_SCORE_MEAN_GAP,
        )
        assertEquals(0.30, gapGate.thresholds.getValue(KnowledgeRelevanceNormalizedFeature.TOP_SCORE_MEAN_GAP), 0.000001)
        assertEquals(gapGate.thresholds, gapEvaluation.thresholds)
        assertTrue(gapEvaluation.positiveAcceptanceRate < 1.0)
    }

    @Test
    fun providerModelFingerprintAndDatasetDriftAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceCrossTopicNormalizationPolicy.compare(
                productionIdentity = productionIdentity(),
                calibrationIdentity = dataset("stage91-calibration-v1", fingerprint = "other"),
                validationIdentity = dataset("stage91-validation-v1"),
                calibrationSamples = samples("calibration"),
                validationSamples = samples("validation"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceCrossTopicNormalizationPolicy.compare(
                productionIdentity = productionIdentity(),
                calibrationIdentity = dataset("stage91-calibration-v1"),
                validationIdentity = dataset("stage91-validation-v1", model = "other-model"),
                calibrationSamples = samples("calibration"),
                validationSamples = samples("validation"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceCrossTopicNormalizationPolicy.compare(
                productionIdentity = productionIdentity(),
                calibrationIdentity = dataset("stage91-same-v1"),
                validationIdentity = dataset("stage91-same-v1"),
                calibrationSamples = samples("calibration"),
                validationSamples = samples("validation"),
            )
        }
    }

    @Test
    fun missingLabelBucketAndLabelDriftAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceCrossTopicNormalizationPolicy.selectCalibrationGates(
                listOf(sample("positive", KnowledgeRelevanceLabel.POSITIVE, 0.2, 1.0)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceCrossTopicNormalizationPolicy.selectCalibrationGates(
                listOf(
                    sample("same", KnowledgeRelevanceLabel.POSITIVE, 0.2, 1.0),
                    sample("same", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.1, 0.5),
                    sample("far", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.05, 0.2),
                ),
            )
        }
    }

    @Test
    fun allFeatureFamiliesAreReported() {
        val report = KnowledgeRelevanceCrossTopicNormalizationPolicy.compare(
            productionIdentity = productionIdentity(),
            calibrationIdentity = dataset("stage91-calibration-v1"),
            validationIdentity = dataset("stage91-validation-v1"),
            calibrationSamples = samples("calibration"),
            validationSamples = samples("validation"),
        )

        assertEquals(
            KnowledgeRelevanceCrossTopicFeatureSet.entries.toSet(),
            report.calibrationGates.keys,
        )
        assertEquals(
            KnowledgeRelevanceCrossTopicFeatureSet.entries.toSet(),
            report.validationEvaluations.keys,
        )
    }

    @Test
    fun invalidCandidateDistributionIsRejectedBeforeFeatureComparison() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceCrossTopicFeatureVector.fromCandidateDistribution(
                topScore = 0.8,
                scoreMean = 0.6,
                scoreMargin = 0.1,
                scoreStandardDeviation = 0.0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceCrossTopicFeatureVector.fromCandidateDistribution(
                topScore = Double.NaN,
                scoreMean = 0.6,
                scoreMargin = 0.1,
                scoreStandardDeviation = 0.1,
            )
        }
    }

    private fun productionIdentity() = KnowledgeRelevanceProductionIdentity(
        providerId = "redmi-production-embedding-v1",
        model = "Qwen/Qwen3-Embedding-0.6B",
        configurationFingerprint = "fingerprint-a",
    )

    private fun dataset(
        version: String,
        model: String = "Qwen/Qwen3-Embedding-0.6B",
        fingerprint: String = "fingerprint-a",
    ) = KnowledgeRelevanceProductionDatasetIdentity(
        providerId = "redmi-production-embedding-v1",
        model = model,
        configurationFingerprint = fingerprint,
        datasetVersion = version,
    )

    private fun samples(prefix: String, positiveGap: Double = 0.3) = listOf(
        sample("$prefix-positive", KnowledgeRelevanceLabel.POSITIVE, positiveGap, 1.5),
        sample("$prefix-near", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.1, 0.4),
        sample("$prefix-far", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.05, 0.1),
    )

    private fun sample(
        caseId: String,
        label: KnowledgeRelevanceLabel,
        topScoreMeanGap: Double,
        marginOverStandardDeviation: Double,
    ) = KnowledgeRelevanceCrossTopicFeatureSample(
        caseId = caseId,
        label = label,
        features = normalized(topScoreMeanGap, marginOverStandardDeviation),
    )

    private fun normalized(
        topScoreMeanGap: Double,
        marginOverStandardDeviation: Double,
    ) = KnowledgeRelevanceCrossTopicFeatureVector(
        topScoreMeanGap = topScoreMeanGap,
        marginOverStandardDeviation = marginOverStandardDeviation,
    )

    private fun normalizedFromScores(
        topScore: Double,
        scoreMean: Double,
        margin: Double,
        standardDeviation: Double,
    ) = KnowledgeRelevanceCrossTopicFeatureVector.fromCandidateDistribution(
        topScore = topScore,
        scoreMean = scoreMean,
        scoreMargin = margin,
        scoreStandardDeviation = standardDeviation,
    )
}
