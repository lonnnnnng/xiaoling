package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeAnswerabilityShadowTelemetryTest {
    @Test
    fun trackerAggregatesRetryCostAndFailureDistributionWithoutContent() {
        val telemetry = KnowledgeAnswerabilityShadowTelemetry.EMPTY
            .plus(
                KnowledgeAnswerabilityShadowAttemptTelemetry(
                    latencyMs = 100L,
                    firstByteLatencyMs = 30L,
                    promptBytes = 400L,
                    inputTokens = 20L,
                    outputTokens = 8L,
                    totalTokens = 28L,
                ),
            )
            .plus(
                KnowledgeAnswerabilityShadowAttemptTelemetry(
                    latencyMs = 150L,
                    firstByteLatencyMs = 40L,
                    promptBytes = 400L,
                    inputTokens = 20L,
                    outputTokens = 9L,
                    totalTokens = 29L,
                ),
            )
        val tracker = KnowledgeAnswerabilityShadowSampleTracker()

        val summary = tracker.record(
            KnowledgeAnswerabilityShadowSampleEvent(
                kind = KnowledgeAnswerabilityShadowSampleKind.UNKNOWN,
                outcome = KnowledgeAnswerabilityShadowObservationOutcome(
                    status = KnowledgeAnswerabilityShadowObservationStatus.UNKNOWN,
                    attemptCount = 2,
                    failureKind = KnowledgeAnswerabilityJudgeFailureKind.PROTOCOL,
                    telemetry = telemetry,
                ),
            ),
        )

        assertEquals(1, summary.sampleCount)
        assertEquals(1, summary.unknownCount)
        assertEquals(2, summary.judgeAttemptCount)
        assertEquals(250L, summary.latencyMs)
        assertEquals(70L, summary.firstByteLatencyMs)
        assertEquals(800L, summary.promptBytes)
        assertEquals(40L, summary.inputTokens)
        assertEquals(17L, summary.outputTokens)
        assertEquals(57L, summary.totalTokens)
        assertEquals(2, summary.usageSampleCount)
        assertEquals(1, summary.failureCounts[KnowledgeAnswerabilityJudgeFailureKind.PROTOCOL])
        assertFalse(summary.toString().contains("候选正文"))
    }

    @Test
    fun trackerSeparatesSkippedPersistenceAndNoticeLifecycle() {
        val tracker = KnowledgeAnswerabilityShadowSampleTracker()

        tracker.record(KnowledgeAnswerabilityShadowSampleEvent(KnowledgeAnswerabilityShadowSampleKind.DISABLED))
        tracker.record(KnowledgeAnswerabilityShadowSampleEvent(KnowledgeAnswerabilityShadowSampleKind.IDENTITY_MISMATCH))
        tracker.record(KnowledgeAnswerabilityShadowSampleEvent(KnowledgeAnswerabilityShadowSampleKind.CANDIDATE_MISSING))
        tracker.record(
            KnowledgeAnswerabilityShadowSampleEvent(
                KnowledgeAnswerabilityShadowSampleKind.ANSWER_PERSISTENCE_FAILED,
            ),
        )
        tracker.record(
            KnowledgeAnswerabilityShadowSampleEvent(
                kind = KnowledgeAnswerabilityShadowSampleKind.COMPLETED,
                outcome = KnowledgeAnswerabilityShadowObservationOutcome(
                    status = KnowledgeAnswerabilityShadowObservationStatus.COMPLETED,
                    persistenceStatus = KnowledgeAnswerabilityShadowPersistenceStatus.FAILED,
                ),
            ),
        )
        val afterPublish = tracker.recordNoticePublished(activeNoticeCount = 1)
        val afterPrune = tracker.recordNoticePruned(prunedCount = 1, activeNoticeCount = 0)

        assertEquals(5, afterPrune.sampleCount)
        assertEquals(1, afterPrune.completedCount)
        assertEquals(3, afterPrune.skippedCount)
        assertEquals(1, afterPrune.disabledCount)
        assertEquals(1, afterPrune.identityMismatchCount)
        assertEquals(1, afterPrune.candidateMissingCount)
        assertEquals(1, afterPrune.answerPersistenceFailedCount)
        assertEquals(1, afterPrune.shadowStoreFailedCount)
        assertEquals(1, afterPublish.noticesPublishedCount)
        assertEquals(0, afterPrune.activeNoticeCount)
        assertEquals(1, afterPrune.noticesPrunedCount)
    }

    @Test
    fun trackerCountersAreBounded() {
        val tracker = KnowledgeAnswerabilityShadowSampleTracker(maxCounter = 2)
        repeat(5) {
            tracker.record(KnowledgeAnswerabilityShadowSampleEvent(KnowledgeAnswerabilityShadowSampleKind.DISABLED))
        }
        tracker.recordNoticePruned(Int.MAX_VALUE, activeNoticeCount = Int.MAX_VALUE)
        val summary = tracker.snapshot()

        assertEquals(2, summary.sampleCount)
        assertEquals(2, summary.skippedCount)
        assertEquals(2, summary.noticesPrunedCount)
        assertEquals(2, summary.activeNoticeCount)
        assertTrue(summary.failureCounts.isEmpty())
    }
}
