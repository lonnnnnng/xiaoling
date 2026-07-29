package com.longdev.xiaoling.ui

import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowPersistentSummary
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerabilityShadowWindowEvidenceProjectionTest {
    @Test
    fun projectsRecordedRangeAndExactSpanWithoutClaimingWindowReadiness() {
        val projection = projectAnswerabilityShadowWindowEvidence(
            summary = KnowledgeAnswerabilityShadowPersistentSummary(
                observationCount = 2,
                oldestRecordedAt = Instant.parse("2026-07-28T23:27:36.666Z").toEpochMilli(),
                latestRecordedAt = Instant.parse("2026-07-29T00:13:50.112Z").toEpochMilli(),
            ),
            zoneId = ZoneId.of("Asia/Shanghai"),
        )

        assertEquals(
            "最早 2026-07-29 07:27:36 · 最新 2026-07-29 08:13:50",
            projection.recordedRangeText,
        )
        assertEquals(
            "记录跨度 46 分钟 13 秒 · 仅展示匿名账本时间证据，不自动判定为分隔窗口",
            projection.observationSpanText,
        )
    }

    @Test
    fun marksSpanUnknownWhenEitherRecordedTimeIsMissing() {
        val projection = projectAnswerabilityShadowWindowEvidence(
            summary = KnowledgeAnswerabilityShadowPersistentSummary(
                observationCount = 1,
                oldestRecordedAt = null,
                latestRecordedAt = Instant.parse("2026-07-29T00:13:50.112Z").toEpochMilli(),
            ),
            zoneId = ZoneId.of("Asia/Shanghai"),
        )

        assertEquals(
            "最早 未知 · 最新 2026-07-29 08:13:50",
            projection.recordedRangeText,
        )
        assertEquals(
            "记录跨度 未知 · 仅展示匿名账本时间证据，不自动判定为分隔窗口",
            projection.observationSpanText,
        )
    }

    @Test
    fun marksSpanUnknownWhenRecordedTimesAreReversed() {
        val projection = projectAnswerabilityShadowWindowEvidence(
            summary = KnowledgeAnswerabilityShadowPersistentSummary(
                observationCount = 2,
                oldestRecordedAt = Instant.parse("2026-07-29T00:13:50.112Z").toEpochMilli(),
                latestRecordedAt = Instant.parse("2026-07-28T23:27:36.666Z").toEpochMilli(),
            ),
            zoneId = ZoneId.of("Asia/Shanghai"),
        )

        assertEquals(
            "最早 2026-07-29 08:13:50 · 最新 2026-07-29 07:27:36",
            projection.recordedRangeText,
        )
        assertEquals(
            "记录跨度 未知 · 仅展示匿名账本时间证据，不自动判定为分隔窗口",
            projection.observationSpanText,
        )
    }
}
