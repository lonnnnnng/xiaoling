package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KnowledgeSearchQualityPolicyTest {
    @Test
    fun evaluatesRecallRankNegativeAccuracyAndRepeatStability() {
        val report = KnowledgeSearchQualityPolicy.evaluate(
            listOf(
                KnowledgeSearchQualityCaseResult(
                    caseId = "multi-relevant",
                    relevantDocumentIds = setOf("doc-a", "doc-b"),
                    rankedDocumentIdsByRun = listOf(
                        listOf("noise", "doc-a", "doc-a", "doc-b"),
                        listOf("noise", "doc-a", "doc-a", "doc-b"),
                    ),
                    limit = 3,
                ),
                KnowledgeSearchQualityCaseResult(
                    caseId = "missed",
                    relevantDocumentIds = setOf("doc-c"),
                    rankedDocumentIdsByRun = listOf(emptyList(), emptyList()),
                    limit = 3,
                ),
                KnowledgeSearchQualityCaseResult(
                    caseId = "negative",
                    relevantDocumentIds = emptySet(),
                    rankedDocumentIdsByRun = listOf(emptyList(), emptyList()),
                    limit = 3,
                ),
            ),
        )

        assertEquals(2, report.positiveCaseCount)
        assertEquals(1, report.negativeCaseCount)
        assertEquals(0.5, report.meanRecallAtK, 0.000001)
        assertEquals(0.25, report.meanReciprocalRank, 0.000001)
        assertEquals(1.0, report.negativeAccuracy, 0.000001)
        assertEquals(1.0, report.stableRankingRate, 0.000001)
    }

    @Test
    fun emptyCorpusProducesZeroedReport() {
        val report = KnowledgeSearchQualityPolicy.evaluate(emptyList())

        assertEquals(0, report.positiveCaseCount)
        assertEquals(0, report.negativeCaseCount)
        assertEquals(0.0, report.meanRecallAtK, 0.000001)
        assertEquals(0.0, report.meanReciprocalRank, 0.000001)
        assertEquals(0.0, report.negativeAccuracy, 0.000001)
        assertEquals(0.0, report.stableRankingRate, 0.000001)
    }

    @Test
    fun missesBeyondLimitAndUnstableRunsAreReported() {
        val report = KnowledgeSearchQualityPolicy.evaluate(
            listOf(
                KnowledgeSearchQualityCaseResult(
                    caseId = "positive-miss",
                    relevantDocumentIds = setOf("relevant"),
                    rankedDocumentIdsByRun = listOf(
                        listOf("noise", "relevant"),
                        listOf("different", "relevant"),
                    ),
                    limit = 1,
                ),
                KnowledgeSearchQualityCaseResult(
                    caseId = "negative-hit",
                    relevantDocumentIds = emptySet(),
                    rankedDocumentIdsByRun = listOf(listOf("noise"), listOf("noise")),
                    limit = 1,
                ),
            ),
        )

        assertEquals(1, report.positiveCaseCount)
        assertEquals(1, report.negativeCaseCount)
        assertEquals(0.0, report.meanRecallAtK, 0.000001)
        assertEquals(0.0, report.meanReciprocalRank, 0.000001)
        assertEquals(0.0, report.negativeAccuracy, 0.000001)
        assertEquals(0.5, report.stableRankingRate, 0.000001)
    }

    @Test
    fun positiveOnlyCorpusKeepsNegativeAccuracyAtZero() {
        val report = KnowledgeSearchQualityPolicy.evaluate(
            listOf(
                KnowledgeSearchQualityCaseResult(
                    caseId = "positive-only",
                    relevantDocumentIds = setOf("doc"),
                    rankedDocumentIdsByRun = listOf(listOf("doc")),
                    limit = 1,
                ),
            ),
        )

        assertEquals(1.0, report.meanRecallAtK, 0.000001)
        assertEquals(1.0, report.meanReciprocalRank, 0.000001)
        assertEquals(0.0, report.negativeAccuracy, 0.000001)
    }

    @Test
    fun rejectsInvalidQualityCases() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeSearchQualityCaseResult(" ", setOf("doc"), listOf(emptyList()), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeSearchQualityCaseResult("case", setOf("doc"), emptyList(), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeSearchQualityCaseResult("case", setOf("doc"), listOf(emptyList()), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeSearchQualityCaseResult("case", setOf(" "), listOf(emptyList()), 1)
        }
    }
}
