package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class KnowledgeRelevanceRelativeDiagnosticsPolicyTest {
    @Test
    fun computesPopulationDistributionAndTopScoreZScore() {
        val diagnostics = KnowledgeRelevanceRelativeDiagnosticsPolicy.evaluate(
            scores = listOf(1.0, 0.0, -1.0),
        )

        assertEquals(0.0, diagnostics.scoreMean, 0.000001)
        assertEquals(0.8164965809, diagnostics.scoreStandardDeviation, 0.000001)
        assertEquals(1.2247448713, diagnostics.topScoreZScore!!, 0.000001)
    }

    @Test
    fun zScoreIsStableWhenAllCandidateScoresShiftTogether() {
        val baseline = KnowledgeRelevanceRelativeDiagnosticsPolicy.evaluate(
            scores = listOf(0.7, 0.3, -0.1),
        )
        val shifted = KnowledgeRelevanceRelativeDiagnosticsPolicy.evaluate(
            scores = listOf(0.9, 0.5, 0.1),
        )

        assertEquals(baseline.scoreStandardDeviation, shifted.scoreStandardDeviation, 0.000001)
        assertEquals(baseline.topScoreZScore!!, shifted.topScoreZScore!!, 0.000001)
    }

    @Test
    fun singleCandidateAndZeroVarianceKeepZScoreUnknown() {
        val single = KnowledgeRelevanceRelativeDiagnosticsPolicy.evaluate(listOf(0.7))
        val tied = KnowledgeRelevanceRelativeDiagnosticsPolicy.evaluate(listOf(0.7, 0.7, 0.7))

        assertEquals(0.0, single.scoreStandardDeviation, 0.000001)
        assertNull(single.topScoreZScore)
        assertEquals(0.0, tied.scoreStandardDeviation, 0.000001)
        assertNull(tied.topScoreZScore)
    }

    @Test
    fun rejectsEmptyOrNonFiniteScores() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceRelativeDiagnosticsPolicy.evaluate(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceRelativeDiagnosticsPolicy.evaluate(listOf(0.7, Double.NaN))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeRelevanceRelativeDiagnosticsPolicy.evaluate(listOf(Double.POSITIVE_INFINITY))
        }
    }
}
