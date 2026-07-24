package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KnowledgeRelevanceProductionCalibrationPolicyTest {
    @Test
    fun formalIdentityCarriesFingerprintAcrossIndependentDatasets() {
        val report = KnowledgeRelevanceProductionCalibrationPolicy.compare(
            productionIdentity = productionIdentity(),
            calibrationIdentity = dataset("stage90-calibration-v1"),
            validationIdentity = dataset("stage90-validation-v1"),
            calibrationSamples = samples("calibration"),
            validationSamples = samples("validation"),
        )

        assertEquals(productionIdentity(), report.productionIdentity)
        assertEquals("stage90-calibration-v1", report.calibrationIdentity.datasetVersion)
        assertEquals("stage90-validation-v1", report.validationIdentity.datasetVersion)
        assertEquals(
            KnowledgeRelevanceFeatureSet.entries.toSet(),
            report.featureComparison.calibrationGates.keys,
        )
    }

    @Test
    fun endpointProviderAndDatasetDriftAreRejectedBeforeComparison() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceProductionCalibrationPolicy.compare(
                productionIdentity = productionIdentity(),
                calibrationIdentity = dataset("stage90-calibration-v1", fingerprint = "other-endpoint"),
                validationIdentity = dataset("stage90-validation-v1"),
                calibrationSamples = samples("calibration"),
                validationSamples = samples("validation"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceProductionCalibrationPolicy.compare(
                productionIdentity = productionIdentity(),
                calibrationIdentity = dataset("stage90-calibration-v1"),
                validationIdentity = dataset("stage90-validation-v1", providerId = "other-provider"),
                calibrationSamples = samples("calibration"),
                validationSamples = samples("validation"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceProductionCalibrationPolicy.compare(
                productionIdentity = productionIdentity(),
                calibrationIdentity = dataset("stage90-same-v1"),
                validationIdentity = dataset("stage90-same-v1"),
                calibrationSamples = samples("calibration"),
                validationSamples = samples("validation"),
            )
        }
    }

    @Test
    fun modelDriftIsRejectedBeforeComparison() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceProductionCalibrationPolicy.compare(
                productionIdentity = productionIdentity(),
                calibrationIdentity = dataset("stage90-calibration-v1", model = "other-embedding-model"),
                validationIdentity = dataset("stage90-validation-v1"),
                calibrationSamples = samples("calibration"),
                validationSamples = samples("validation"),
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
        providerId: String = "redmi-production-embedding-v1",
        model: String = "Qwen/Qwen3-Embedding-0.6B",
        fingerprint: String = "fingerprint-a",
    ) = KnowledgeRelevanceProductionDatasetIdentity(
        providerId = providerId,
        model = model,
        configurationFingerprint = fingerprint,
        datasetVersion = version,
    )

    private fun samples(prefix: String) = listOf(
        sample("$prefix-positive", KnowledgeRelevanceLabel.POSITIVE, 0.8, 0.2, 2.0),
        sample("$prefix-near", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.5, 0.08, 0.6),
        sample("$prefix-far", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.2, 0.02, 0.1),
    )

    private fun sample(
        caseId: String,
        label: KnowledgeRelevanceLabel,
        rawTopScore: Double,
        margin: Double,
        zScore: Double,
    ) = KnowledgeRelevanceFeatureSample(
        caseId = caseId,
        label = label,
        features = KnowledgeRelevanceFeatureVector(rawTopScore, margin, zScore),
    )
}
