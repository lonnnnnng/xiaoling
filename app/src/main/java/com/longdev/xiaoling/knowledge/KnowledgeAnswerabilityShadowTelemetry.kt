package com.longdev.xiaoling.knowledge

/**
 * long: 每次 Judge attempt 只携带数值遥测，不携带问题、候选正文、引用内容、原始响应或任何凭据。
 */
data class KnowledgeAnswerabilityShadowAttemptTelemetry(
    val latencyMs: Long? = null,
    val firstByteLatencyMs: Long? = null,
    val promptBytes: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
)

/**
 * long: 一次 shadow 观测可能包含一次受控重试；这里聚合该观测的数值成本，避免把 Judge 请求混入普通 Agent Run 指标。
 */
data class KnowledgeAnswerabilityShadowTelemetry(
    val attempts: Int = 0,
    val latencyMs: Long? = null,
    val firstByteLatencyMs: Long? = null,
    val promptBytes: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val usageSamples: Int = 0,
    val failureCounts: Map<KnowledgeAnswerabilityJudgeFailureKind, Int> = emptyMap(),
) {
    /**
     * long: 重试链中的每个可恢复失败都要进入分布统计，不能只保留最后一次失败或成功的结果。
     */
    fun recordFailure(kind: KnowledgeAnswerabilityJudgeFailureKind): KnowledgeAnswerabilityShadowTelemetry {
        val counts = failureCounts.toMutableMap()
        counts[kind] = (counts[kind] ?: 0) + 1
        return copy(failureCounts = counts.toMap())
    }

    fun plus(attempt: KnowledgeAnswerabilityShadowAttemptTelemetry): KnowledgeAnswerabilityShadowTelemetry = copy(
        // long: 这里累加一次真实 Provider attempt；缺失字段继续保持未知，不用 0 冒充上游已返回的成本。
        attempts = saturatingAdd(attempts, 1),
        latencyMs = addNullable(latencyMs, attempt.latencyMs),
        firstByteLatencyMs = addNullable(firstByteLatencyMs, attempt.firstByteLatencyMs),
        promptBytes = addNullable(promptBytes, attempt.promptBytes),
        inputTokens = addNullable(inputTokens, attempt.inputTokens),
        outputTokens = addNullable(outputTokens, attempt.outputTokens),
        totalTokens = addNullable(totalTokens, attempt.totalTokens),
        usageSamples = saturatingAdd(
            usageSamples,
            if (attempt.inputTokens != null || attempt.outputTokens != null || attempt.totalTokens != null) 1 else 0,
        ),
    )

    companion object {
        val EMPTY = KnowledgeAnswerabilityShadowTelemetry()

        private fun addNullable(left: Long?, right: Long?): Long? {
            if (left == null && right == null) return null
            return saturatingAdd(left ?: 0L, right ?: 0L)
        }

        private fun saturatingAdd(left: Long, right: Long): Long {
            if (right <= 0L) return left
            return if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
        }

        private fun saturatingAdd(left: Int, right: Int): Int {
            if (right <= 0) return left
            return if (Int.MAX_VALUE - left < right) Int.MAX_VALUE else left + right
        }
    }
}

enum class KnowledgeAnswerabilityShadowSampleKind {
    DISABLED,
    IDENTITY_MISMATCH,
    UNSUPPORTED_ORIGIN,
    CANDIDATE_MISSING,
    ANSWER_PERSISTENCE_FAILED,
    COMPLETED,
    UNKNOWN,
    CANCELLED,
    UNEXPECTED,
}

data class KnowledgeAnswerabilityShadowSampleEvent(
    val kind: KnowledgeAnswerabilityShadowSampleKind,
    val outcome: KnowledgeAnswerabilityShadowObservationOutcome? = null,
    val failureKind: KnowledgeAnswerabilityJudgeFailureKind? = outcome?.failureKind,
)

/**
 * long: 设置页只展示本进程聚合结果；不保存样本正文，也不把短生命周期的 messageId 带入统计对象。
 */
data class KnowledgeAnswerabilityShadowSampleSummary(
    val sampleCount: Int = 0,
    val completedCount: Int = 0,
    val unknownCount: Int = 0,
    val skippedCount: Int = 0,
    val answerPersistenceFailedCount: Int = 0,
    val shadowStoreFailedCount: Int = 0,
    val cancelledCount: Int = 0,
    val unexpectedCount: Int = 0,
    val judgeAttemptCount: Int = 0,
    val latencyMs: Long? = null,
    val firstByteLatencyMs: Long? = null,
    val promptBytes: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val usageSampleCount: Int = 0,
    val noticesPublishedCount: Int = 0,
    val activeNoticeCount: Int = 0,
    val noticesPrunedCount: Int = 0,
    val failureCounts: Map<KnowledgeAnswerabilityJudgeFailureKind, Int> = emptyMap(),
    val disabledCount: Int = 0,
    val identityMismatchCount: Int = 0,
    val unsupportedOriginCount: Int = 0,
    val candidateMissingCount: Int = 0,
    val bindingUnknownCount: Int = 0,
)

/**
 * long: 跨进程摘要只聚合匿名观测账本中的枚举和数值字段；notice 生命周期仍属于当前进程，不能借历史消息 ID 恢复。
 */
data class KnowledgeAnswerabilityShadowPersistentSummary(
    val observationCount: Int = 0,
    val judgeIdentityCount: Int = 0,
    val completedCount: Int = 0,
    val unknownCount: Int = 0,
    val boundCount: Int = 0,
    val bindingUnknownCount: Int = 0,
    val acceptCount: Int = 0,
    val rejectCount: Int = 0,
    val undecidedCount: Int = 0,
    val judgeAttemptCount: Int = 0,
    val latencyMs: Long? = null,
    val firstByteLatencyMs: Long? = null,
    val promptBytes: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
    val usageSampleCount: Int = 0,
    val failureCounts: Map<KnowledgeAnswerabilityJudgeFailureKind, Int> = emptyMap(),
    val oldestRecordedAt: Long? = null,
    val latestRecordedAt: Long? = null,
) {
    companion object {
        const val MAX_RETAINED_OBSERVATIONS = 2_000
    }
}

/**
 * long: 统计使用固定上限的计数和失败枚举 Map，长时间运行时也不会因为 shadow 旁路无限增长。
 */
class KnowledgeAnswerabilityShadowSampleTracker(
    private val maxCounter: Int = DEFAULT_MAX_COUNTER,
) {
    init {
        require(maxCounter > 0) { "answerability shadow 统计上限必须大于 0" }
    }

    private var summary = KnowledgeAnswerabilityShadowSampleSummary()

    @Synchronized
    fun record(event: KnowledgeAnswerabilityShadowSampleEvent): KnowledgeAnswerabilityShadowSampleSummary {
        // long: 样本分类、Judge 成本和失败分桶在同一次锁内提交，设置页不会看到互相错位的半成品统计。
        val outcome = event.outcome
        val attempts = outcome?.let { maxOf(it.attemptCount, it.telemetry.attempts) } ?: 0
        val nextFailureCounts = summary.failureCounts.toMutableMap()
        outcome?.telemetry?.failureCounts?.forEach { (kind, count) ->
            nextFailureCounts[kind] = cappedAdd(nextFailureCounts[kind] ?: 0, count)
        }
        val failureKind = event.failureKind
        if (failureKind != null && (outcome == null || outcome.telemetry.failureCounts[failureKind] == null)) {
            nextFailureCounts[failureKind] = cappedAdd(nextFailureCounts[failureKind] ?: 0, 1)
        }
        val next = summary.copy(
            sampleCount = cappedAdd(summary.sampleCount, 1),
            completedCount = cappedAdd(summary.completedCount, if (event.kind == KnowledgeAnswerabilityShadowSampleKind.COMPLETED) 1 else 0),
            unknownCount = cappedAdd(summary.unknownCount, if (event.kind == KnowledgeAnswerabilityShadowSampleKind.UNKNOWN) 1 else 0),
            skippedCount = cappedAdd(
                summary.skippedCount,
                if (event.kind == KnowledgeAnswerabilityShadowSampleKind.DISABLED ||
                    event.kind == KnowledgeAnswerabilityShadowSampleKind.IDENTITY_MISMATCH ||
                    event.kind == KnowledgeAnswerabilityShadowSampleKind.UNSUPPORTED_ORIGIN ||
                    event.kind == KnowledgeAnswerabilityShadowSampleKind.CANDIDATE_MISSING
                ) 1 else 0,
            ),
            disabledCount = cappedAdd(
                summary.disabledCount,
                if (event.kind == KnowledgeAnswerabilityShadowSampleKind.DISABLED) 1 else 0,
            ),
            identityMismatchCount = cappedAdd(
                summary.identityMismatchCount,
                if (event.kind == KnowledgeAnswerabilityShadowSampleKind.IDENTITY_MISMATCH) 1 else 0,
            ),
            unsupportedOriginCount = cappedAdd(
                summary.unsupportedOriginCount,
                if (event.kind == KnowledgeAnswerabilityShadowSampleKind.UNSUPPORTED_ORIGIN) 1 else 0,
            ),
            candidateMissingCount = cappedAdd(
                summary.candidateMissingCount,
                if (event.kind == KnowledgeAnswerabilityShadowSampleKind.CANDIDATE_MISSING) 1 else 0,
            ),
            bindingUnknownCount = cappedAdd(
                summary.bindingUnknownCount,
                if (outcome?.binding?.status == KnowledgeAnswerabilityShadowBindingStatus.UNKNOWN) 1 else 0,
            ),
            answerPersistenceFailedCount = cappedAdd(
                summary.answerPersistenceFailedCount,
                if (event.kind == KnowledgeAnswerabilityShadowSampleKind.ANSWER_PERSISTENCE_FAILED) 1 else 0,
            ),
            shadowStoreFailedCount = cappedAdd(
                summary.shadowStoreFailedCount,
                if (outcome?.persistenceStatus == KnowledgeAnswerabilityShadowPersistenceStatus.FAILED) 1 else 0,
            ),
            cancelledCount = cappedAdd(summary.cancelledCount, if (event.kind == KnowledgeAnswerabilityShadowSampleKind.CANCELLED) 1 else 0),
            unexpectedCount = cappedAdd(summary.unexpectedCount, if (event.kind == KnowledgeAnswerabilityShadowSampleKind.UNEXPECTED) 1 else 0),
            judgeAttemptCount = cappedAdd(summary.judgeAttemptCount, attempts),
            latencyMs = addNullable(summary.latencyMs, outcome?.telemetry?.latencyMs),
            firstByteLatencyMs = addNullable(
                summary.firstByteLatencyMs,
                outcome?.telemetry?.firstByteLatencyMs,
            ),
            promptBytes = addNullable(summary.promptBytes, outcome?.telemetry?.promptBytes),
            inputTokens = addNullable(summary.inputTokens, outcome?.telemetry?.inputTokens),
            outputTokens = addNullable(summary.outputTokens, outcome?.telemetry?.outputTokens),
            totalTokens = addNullable(summary.totalTokens, outcome?.telemetry?.totalTokens),
            usageSampleCount = cappedAdd(
                summary.usageSampleCount,
                outcome?.telemetry?.usageSamples ?: 0,
            ),
            failureCounts = nextFailureCounts.toMap(),
        )
        summary = next
        return summary
    }

    @Synchronized
    fun recordNoticePublished(activeNoticeCount: Int): KnowledgeAnswerabilityShadowSampleSummary {
        // long: notice 生命周期只接收数量，不持有 messageId；发布后以 UI 真实 Map 大小校准当前有效数。
        summary = summary.copy(
            noticesPublishedCount = cappedAdd(summary.noticesPublishedCount, 1),
            activeNoticeCount = activeNoticeCount.coerceIn(0, maxCounter),
        )
        return summary
    }

    @Synchronized
    fun recordNoticePruned(
        prunedCount: Int,
        activeNoticeCount: Int,
    ): KnowledgeAnswerabilityShadowSampleSummary {
        // long: 会话删除、重载或迟到 publish 顺带清理旧 notice 时统一累计，保证生命周期计数覆盖所有可见裁剪。
        summary = summary.copy(
            noticesPrunedCount = cappedAdd(summary.noticesPrunedCount, prunedCount.coerceAtLeast(0)),
            activeNoticeCount = activeNoticeCount.coerceIn(0, maxCounter),
        )
        return summary
    }

    @Synchronized
    fun snapshot(): KnowledgeAnswerabilityShadowSampleSummary = summary.copy(
        failureCounts = summary.failureCounts.toMap(),
    )

    private fun cappedAdd(left: Int, right: Int): Int {
        // long: 长时间运行只保留有界计数；达到上限后保持饱和，避免整数溢出让摘要倒退成负数。
        if (right <= 0) return left
        val sum = if (Int.MAX_VALUE - left < right) Int.MAX_VALUE else left + right
        return minOf(maxCounter, sum)
    }

    private fun addNullable(left: Long?, right: Long?): Long? {
        if (left == null && right == null) return null
        val safeLeft = left ?: 0L
        val safeRight = right ?: 0L
        return if (Long.MAX_VALUE - safeLeft < safeRight) Long.MAX_VALUE else safeLeft + safeRight
    }

    companion object {
        const val DEFAULT_MAX_COUNTER = 10_000
    }
}
