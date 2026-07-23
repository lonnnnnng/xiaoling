package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRelevanceCalibrationPolicyTest {
    @Test
    fun reportsBucketPercentilesAndPerfectCandidateGateForSeparatedSamples() {
        val report = KnowledgeRelevanceCalibrationPolicy.evaluate(
            listOf(
                sample("positive-a", KnowledgeRelevanceLabel.POSITIVE, 0.80, 0.30),
                sample("positive-b", KnowledgeRelevanceLabel.POSITIVE, 0.75, 0.25),
                sample("near-a", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.65, 0.15),
                sample("near-b", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.60, 0.12),
                sample("far-a", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.40, 0.05),
                sample("far-b", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.30, 0.02),
            ),
        )

        val positive = report.bucket(KnowledgeRelevanceLabel.POSITIVE)
        assertEquals(2, positive.sampleCount)
        assertEquals(2, positive.uniqueCaseCount)
        assertEquals(0.75, positive.topScore.p05, 0.000001)
        assertEquals(0.75, positive.topScore.p50, 0.000001)
        assertEquals(0.80, positive.topScore.p95, 0.000001)
        assertEquals(0.25, positive.scoreMargin.p05, 0.000001)
        assertEquals(1.0, report.candidateGate.positiveAcceptanceRate, 0.000001)
        assertEquals(1.0, report.candidateGate.nearNegativeRejectionRate, 0.000001)
        assertEquals(1.0, report.candidateGate.farNegativeRejectionRate, 0.000001)
        assertEquals(1.0, report.candidateGate.balancedAccuracy, 0.000001)
    }

    @Test
    fun overlappingSamplesCannotProducePerfectCandidateGate() {
        val report = KnowledgeRelevanceCalibrationPolicy.evaluate(
            listOf(
                sample("positive-a", KnowledgeRelevanceLabel.POSITIVE, 0.70, 0.15),
                sample("positive-b", KnowledgeRelevanceLabel.POSITIVE, 0.62, 0.10),
                sample("near-a", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.68, 0.16),
                sample("near-b", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.60, 0.09),
                sample("far-a", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.40, 0.04),
                sample("far-b", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.35, 0.03),
            ),
        )

        assertTrue(report.candidateGate.balancedAccuracy < 1.0)
        assertTrue(
            report.candidateGate.positiveAcceptanceRate < 1.0 ||
                report.candidateGate.nearNegativeRejectionRate < 1.0,
        )
    }

    @Test
    fun rejectsMissingBucketsInvalidScoresAndCaseLabelDrift() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceCalibrationPolicy.evaluate(
                listOf(sample("positive", KnowledgeRelevanceLabel.POSITIVE, 0.8, 0.2)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceCalibrationPolicy.evaluate(
                completeSamples().toMutableList().apply {
                    add(sample("bad", KnowledgeRelevanceLabel.POSITIVE, Double.NaN, 0.2))
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceCalibrationPolicy.evaluate(
                completeSamples().toMutableList().apply {
                    add(sample("positive", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.7, 0.1))
                },
            )
        }
    }

    private fun completeSamples() = listOf(
        sample("positive", KnowledgeRelevanceLabel.POSITIVE, 0.8, 0.2),
        sample("near", KnowledgeRelevanceLabel.NEAR_NEGATIVE, 0.6, 0.1),
        sample("far", KnowledgeRelevanceLabel.FAR_NEGATIVE, 0.3, 0.02),
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
