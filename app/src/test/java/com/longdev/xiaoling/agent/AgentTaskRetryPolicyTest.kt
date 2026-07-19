package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTaskRetryPolicyTest {
    @Test
    fun failedRunWithoutSuccessfulToolResultIsRetryableWithoutConfirmation() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            detail(status = AgentRunStatus.FAILED),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = false),
            eligibility,
        )
    }

    @Test
    fun cancelledRunIsRetryableForRecovery() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            detail(status = AgentRunStatus.CANCELLED),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = false),
            eligibility,
        )
    }

    @Test
    fun successfulWriteToolRequiresConfirmationBeforeRetry() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            detail(
                status = AgentRunStatus.FAILED,
                events = listOf(
                    event(
                        type = "tool.call.validated",
                        metadata = RunEventMetadata.ToolCall(
                            id = "tool-call-1",
                            toolName = "memory.remember",
                            risk = ToolRisk.REQUIRES_APPROVAL,
                            arguments = mapOf("content" to "用户喜欢紧凑界面"),
                        ),
                    ),
                    event(
                        type = "tool.result",
                        metadata = RunEventMetadata.ToolResult(
                            toolName = "memory.remember",
                            content = "已保存",
                            durationMs = 20,
                            success = true,
                            verified = true,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            eligibility,
        )
    }

    @Test
    fun budgetExhaustedRunIsRetryable() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            detail(status = AgentRunStatus.BUDGET_EXHAUSTED),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = false),
            eligibility,
        )
    }

    @Test
    fun interruptedExecutionRequiresConfirmationBeforeRecovery() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            detail(
                status = AgentRunStatus.CANCELLED,
                events = listOf(
                    event(
                        type = "run.recovered",
                        metadata = RunEventMetadata.Recovery(
                            fromStatus = AgentRunStatus.EXECUTING,
                            toStatus = AgentRunStatus.CANCELLED,
                            reason = "应用重启后终止上次未完成 Agent 任务",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            eligibility,
        )
    }

    @Test
    fun cancelledToolExecutionRequiresConfirmationBeforeRetry() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            detail(
                status = AgentRunStatus.CANCELLED,
                steps = listOf(
                    step(type = AgentStepTypes.TOOL_EXECUTE, status = AgentStepStatus.CANCELLED),
                ),
            ),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            eligibility,
        )
    }

    @Test
    fun failedToolVerificationRequiresConfirmationBeforeRetry() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            detail(
                status = AgentRunStatus.BUDGET_EXHAUSTED,
                steps = listOf(
                    step(type = AgentStepTypes.TOOL_VERIFY, status = AgentStepStatus.FAILED),
                ),
            ),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            eligibility,
        )
    }

    @Test
    fun v20SafeLedgerFailsSafeToConfirmationWhenResultEventIsMissing() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            v20Detail(
                callRisk = ToolRisk.SAFE,
                resultSuccess = true,
                includeResultEvent = false,
            ),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            eligibility,
        )
    }

    @Test
    fun v20SafeLedgerFailsSafeToConfirmationWhenVerificationEventIsNotAnchored() {
        val complete = v20Detail(
            callRisk = ToolRisk.SAFE,
            resultSuccess = true,
        )
        val call = complete.toolLedger.calls.single()
        val corrupted = complete.copy(
            snapshot = complete.snapshot.copy(
                events = complete.snapshot.events + event(
                    id = "event-ledger-extra-verify",
                    type = "tool.verify",
                    metadata = RunEventMetadata.ToolVerification(
                        toolName = call.toolName,
                        status = ToolVerificationStatus.PASSED,
                        toolCallId = call.id,
                    ),
                    createdAt = 4L,
                ),
            ),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            AgentTaskRetryPolicy.evaluate(corrupted),
        )
    }

    @Test
    fun v20SuccessfulNonSafeLedgerRequiresConfirmationByExactToolCallId() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            v20Detail(
                callRisk = ToolRisk.REQUIRES_APPROVAL,
                resultSuccess = true,
            ),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            eligibility,
        )
    }

    @Test
    fun v20NonSafeCommittedOrUnknownReceiptRequiresConfirmationWhenResultReportsFailure() {
        listOf(
            ToolExecutionReceiptStatus.COMMITTED,
            ToolExecutionReceiptStatus.UNKNOWN,
        ).forEach { receiptStatus ->
            val eligibility = AgentTaskRetryPolicy.evaluate(
                v20Detail(
                    callRisk = ToolRisk.REQUIRES_APPROVAL,
                    resultSuccess = false,
                    receiptStatus = receiptStatus,
                ),
            )

            assertEquals(
                AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
                eligibility,
            )
        }
    }

    @Test
    fun v20CallRiskDriftFailsSafeToConfirmation() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            v20Detail(
                callRisk = ToolRisk.REQUIRES_APPROVAL,
                eventCallRisk = ToolRisk.SAFE,
                resultSuccess = true,
            ),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = true),
            eligibility,
        )
    }

    @Test
    fun v20SuccessfulSafeLedgerDoesNotRequireConfirmation() {
        val eligibility = AgentTaskRetryPolicy.evaluate(
            v20Detail(
                callRisk = ToolRisk.SAFE,
                resultSuccess = true,
            ),
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = false),
            eligibility,
        )
    }

    @Test
    fun v20ValidatedNonSafeCallWithoutResultDoesNotRequireConfirmationBeforeExecution() {
        val detail = v20Detail(
            callRisk = ToolRisk.REQUIRES_APPROVAL,
            resultSuccess = null,
        )

        assertEquals(
            AgentTaskRetryEligibility.Retryable(requiresConfirmation = false),
            AgentTaskRetryPolicy.evaluate(detail),
        )
    }

    private fun detail(
        status: AgentRunStatus,
        events: List<RunEventRecord> = emptyList(),
        steps: List<AgentStepRecord> = emptyList(),
        toolLedger: AgentToolLedgerRecord = AgentToolLedgerRecord(),
    ): AgentRunDetailRecord {
        return AgentRunDetailRecord(
            snapshot = AgentRunSnapshot(
                run = AgentRunRecord(
                    id = "run-source",
                    conversationId = "conversation-1",
                    userMessageId = "message-1",
                    goal = "重试任务",
                    status = status,
                    result = null,
                    errorMessage = "模拟失败",
                    createdAt = 1L,
                    updatedAt = 2L,
                    completedAt = 2L,
                ),
                steps = steps,
                events = events,
            ),
            approvals = emptyList(),
            toolLedger = toolLedger,
        )
    }

    private fun v20Detail(
        callRisk: ToolRisk,
        resultSuccess: Boolean?,
        eventCallRisk: ToolRisk = callRisk,
        includeResultEvent: Boolean = resultSuccess != null,
        receiptStatus: ToolExecutionReceiptStatus? = null,
    ): AgentRunDetailRecord {
        val call = ToolCall(
            id = "tool-call-ledger-retry",
            name = if (callRisk == ToolRisk.SAFE) "app.current_time" else "notes.create",
            arguments = emptyMap(),
            risk = callRisk,
        )
        val proposed = event(
            id = "event-ledger-proposed",
            type = "tool.call.proposed",
            metadata = RunEventMetadata.ToolCall(call.id, call.name, eventCallRisk, call.arguments),
            createdAt = 1L,
        )
        val validated = event(
            id = "event-ledger-validated",
            type = "tool.call.validated",
            metadata = RunEventMetadata.ToolCall(call.id, call.name, eventCallRisk, call.arguments),
            createdAt = 2L,
        )
        val result = resultSuccess?.let { success ->
            val receipt = receiptStatus?.let { status ->
                ToolExecutionReceipt(
                    toolCallId = call.id,
                    operationId = "operation-ledger-retry",
                    idempotencyKey = call.id,
                    status = status,
                )
            }
            RunEventMetadata.ToolResult(
                toolName = call.name,
                content = if (success) "工具执行成功" else "工具执行失败",
                durationMs = 5L,
                success = success,
                verified = success,
                toolCallId = call.id,
                replaySafety = if (receipt == null) {
                    ToolReplaySafety.RESTART_REQUIRED
                } else {
                    ToolReplaySafety.IDEMPOTENT_BY_KEY
                },
                executionReceipt = receipt,
            )
        }
        val resultEvent = result?.let {
            event(
                id = "event-ledger-result",
                type = "tool.result",
                metadata = it,
                createdAt = 3L,
            )
        }
        return detail(
            status = AgentRunStatus.FAILED,
            events = listOfNotNull(proposed, validated, resultEvent.takeIf { includeResultEvent }),
            toolLedger = AgentToolLedgerRecord(
                calls = listOf(
                    AgentToolCallRecord(
                        id = call.id,
                        runId = "run-source",
                        toolName = call.name,
                        risk = call.risk,
                        arguments = call.arguments,
                        proposedEventId = proposed.id,
                        validatedEventId = validated.id,
                        createdAt = proposed.createdAt,
                        validatedAt = validated.createdAt,
                    ),
                ),
                results = result?.let {
                    listOf(
                        AgentToolResultRecord(
                            toolCallId = call.id,
                            runId = "run-source",
                            eventId = checkNotNull(resultEvent).id,
                            toolName = call.name,
                            content = it.content,
                            success = it.success,
                            errorMessage = if (it.success) null else it.content,
                            durationMs = it.durationMs,
                            executorVerified = it.verified,
                            verificationStatus = null,
                            verifiedEventId = null,
                            memoryIdsUsed = emptyList(),
                            replaySafety = it.replaySafety,
                            executionReceipt = it.executionReceipt,
                            createdAt = checkNotNull(resultEvent).createdAt,
                            verifiedAt = null,
                        ),
                    )
                }.orEmpty(),
            ),
        )
    }

    private fun event(type: String, metadata: RunEventMetadata): RunEventRecord {
        return event(
            id = "event-$type",
            type = type,
            metadata = metadata,
            createdAt = 1L,
        )
    }

    private fun event(
        id: String,
        type: String,
        metadata: RunEventMetadata,
        createdAt: Long,
    ): RunEventRecord {
        return RunEventRecord(
            id = id,
            runId = "run-source",
            type = type,
            message = type,
            createdAt = createdAt,
            metadata = metadata,
        )
    }

    private fun step(type: String, status: AgentStepStatus): AgentStepRecord {
        return AgentStepRecord(
            id = "step-$type",
            runId = "run-source",
            sequence = 1,
            type = type,
            status = status,
            title = type,
            detail = status.name,
            createdAt = 1L,
            completedAt = 2L,
        )
    }
}
