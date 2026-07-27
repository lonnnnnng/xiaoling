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
    fun legacyVerificationWithoutToolCallIdIsEvidenceIncomplete() {
        val call = RunEventMetadata.ToolCall(
            id = "tool-call-missing-verification-id",
            toolName = "memory.remember",
            risk = ToolRisk.REQUIRES_APPROVAL,
            arguments = mapOf("content" to "用户喜欢紧凑界面"),
        )
        val evidence = AgentTaskRetryPolicy.assessEvidence(
            detail(
                status = AgentRunStatus.FAILED,
                events = listOf(
                    event(type = "tool.call.validated", metadata = call),
                    event(
                        type = "tool.result",
                        metadata = RunEventMetadata.ToolResult(
                            toolName = call.toolName,
                            content = "已保存",
                            durationMs = 20,
                            success = true,
                            verified = true,
                            toolCallId = call.id,
                        ),
                    ),
                    event(
                        type = "tool.verify",
                        metadata = RunEventMetadata.ToolVerification(
                            toolName = call.toolName,
                            status = ToolVerificationStatus.PASSED,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(AgentTaskRetryEvidenceCode.EVIDENCE_INCOMPLETE, evidence.code)
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
    fun retryEvidenceDistinguishesSafeToolsFromUncertainWrites() {
        assertEquals(
            AgentTaskRetryEvidenceCode.NO_SIDE_EFFECT,
            AgentTaskRetryPolicy.assessEvidence(
                v20Detail(callRisk = ToolRisk.SAFE, resultSuccess = true),
            ).code,
        )
        assertEquals(
            AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN,
            AgentTaskRetryPolicy.assessEvidence(
                v20Detail(callRisk = ToolRisk.REQUIRES_APPROVAL, resultSuccess = true),
            ).code,
        )
    }

    @Test
    fun retryEvidenceDistinguishesReceiptStates() {
        assertEquals(
            AgentTaskRetryEvidenceCode.NOT_COMMITTED,
            AgentTaskRetryPolicy.assessEvidence(
                v20Detail(
                    callRisk = ToolRisk.REQUIRES_APPROVAL,
                    resultSuccess = false,
                    receiptStatus = ToolExecutionReceiptStatus.NOT_COMMITTED,
                ),
            ).code,
        )
        assertEquals(
            AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN,
            AgentTaskRetryPolicy.assessEvidence(
                v20Detail(
                    callRisk = ToolRisk.REQUIRES_APPROVAL,
                    resultSuccess = false,
                    receiptStatus = ToolExecutionReceiptStatus.UNKNOWN,
                ),
            ).code,
        )
        assertEquals(
            AgentTaskRetryEvidenceCode.COMMITTED_UNVERIFIED,
            AgentTaskRetryPolicy.assessEvidence(
                v20Detail(
                    callRisk = ToolRisk.REQUIRES_APPROVAL,
                    resultSuccess = false,
                    receiptStatus = ToolExecutionReceiptStatus.COMMITTED,
                ),
            ).code,
        )
    }

    @Test
    fun retryEvidenceTreatsLedgerDriftAsIncomplete() {
        val complete = v20Detail(
            callRisk = ToolRisk.REQUIRES_APPROVAL,
            resultSuccess = true,
        )
        val call = complete.toolLedger.calls.single()
        val corrupted = complete.copy(
            snapshot = complete.snapshot.copy(
                events = complete.snapshot.events + event(
                    id = "event-retry-evidence-drift",
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
            AgentTaskRetryEvidenceCode.EVIDENCE_INCOMPLETE,
            AgentTaskRetryPolicy.assessEvidence(corrupted).code,
        )
    }

    @Test
    fun retryEvidenceTreatsInterruptedExecutionWithoutResultAsUnknown() {
        val detail = detail(
            status = AgentRunStatus.CANCELLED,
            steps = listOf(step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.CANCELLED)),
        )

        assertEquals(
            AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN,
            AgentTaskRetryPolicy.assessEvidence(detail).code,
        )
    }

    @Test
    fun confirmationIsAcceptedOnlyWhenEvidenceCodeStillMatches() {
        val unknown = v20Detail(
            callRisk = ToolRisk.REQUIRES_APPROVAL,
            resultSuccess = false,
            receiptStatus = ToolExecutionReceiptStatus.UNKNOWN,
        )
        val committed = v20Detail(
            callRisk = ToolRisk.REQUIRES_APPROVAL,
            resultSuccess = false,
            receiptStatus = ToolExecutionReceiptStatus.COMMITTED,
        )

        assertEquals(
            true,
            AgentTaskRetryPolicy.canConfirmRetry(AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN, unknown),
        )
        assertEquals(
            false,
            AgentTaskRetryPolicy.canConfirmRetry(AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN, committed),
        )
        assertEquals(
            true,
            AgentTaskRetryPolicy.canConfirmRetry(
                AgentTaskRetryEvidenceCode.NOT_COMMITTED,
                detail(status = AgentRunStatus.FAILED),
            ),
        )
    }

    @Test
    fun confirmationRejectsSameCodeWhenAValidToolCallIsAddedAfterPromptOpened() {
        val firstCall = RunEventMetadata.ToolCall(
            id = "legacy-call-1",
            toolName = "notes.create",
            risk = ToolRisk.REQUIRES_APPROVAL,
            arguments = mapOf("content" to "第一条"),
        )
        val firstResult = RunEventMetadata.ToolResult(
            toolName = firstCall.toolName,
            content = "已保存",
            durationMs = 5L,
            success = true,
            verified = true,
            toolCallId = firstCall.id,
        )
        val base = detail(
            status = AgentRunStatus.FAILED,
            events = listOf(
                event("tool.call.validated", firstCall),
                event("tool.result", firstResult),
            ),
        )
        val evidence = AgentTaskRetryPolicy.assessEvidence(base)
        assertEquals(AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN, evidence.code)
        assertEquals(true, AgentTaskRetryPolicy.canConfirmRetry(evidence.code, base, evidence.fingerprint))

        val secondCall = firstCall.copy(
            id = "legacy-call-2",
            arguments = mapOf("content" to "第二条"),
        )
        val secondResult = firstResult.copy(toolCallId = secondCall.id, content = "已保存第二条")
        val changed = base.copy(
            snapshot = base.snapshot.copy(
                events = base.snapshot.events + listOf(
                    event("tool.call.validated-2", secondCall),
                    event("tool.result-2", secondResult),
                ),
            ),
        )
        assertEquals(AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN, AgentTaskRetryPolicy.assessEvidence(changed).code)
        assertEquals(false, AgentTaskRetryPolicy.canConfirmRetry(evidence.code, changed, evidence.fingerprint))
    }

    @Test
    fun confirmationRejectsSameCodeWhenReceiptChangesButLedgerRemainsValid() {
        val base = v20Detail(
            callRisk = ToolRisk.REQUIRES_APPROVAL,
            resultSuccess = false,
            receiptStatus = ToolExecutionReceiptStatus.UNKNOWN,
        )
        val evidence = AgentTaskRetryPolicy.assessEvidence(base)
        val call = base.toolLedger.calls.single()
        val result = base.toolLedger.results.single()
        val changedReceipt = checkNotNull(result.executionReceipt).copy(operationId = "operation-retry-replaced")
        val changedResult = result.copy(executionReceipt = changedReceipt)
        val changedEvents = base.snapshot.events.map { event ->
            if (event.type == "tool.result") {
                event.copy(metadata = (event.metadata as RunEventMetadata.ToolResult).copy(executionReceipt = changedReceipt))
            } else {
                event
            }
        }
        val changed = base.copy(
            snapshot = base.snapshot.copy(events = changedEvents),
            toolLedger = base.toolLedger.copy(results = listOf(changedResult)),
        )
        assertEquals(AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN, AgentTaskRetryPolicy.assessEvidence(changed).code)
        assertEquals(false, AgentTaskRetryPolicy.canConfirmRetry(evidence.code, changed, evidence.fingerprint))
        assertEquals(call.id, changedResult.toolCallId)
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
    fun persistedRecoveryEvidenceMustMatchCurrentLedgerEvidence() {
        val base = detail(
            status = AgentRunStatus.CANCELLED,
            events = listOf(
                event(
                    type = "run.recovered",
                    metadata = RunEventMetadata.Recovery(
                        fromStatus = AgentRunStatus.EXECUTING,
                        toStatus = AgentRunStatus.CANCELLED,
                        reason = "应用重启后终止上次未完成 Agent 任务",
                        retryEvidenceCode = AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN,
                    ),
                ),
            ),
        )
        assertEquals(
            AgentTaskRetryEvidenceCode.EVIDENCE_INCOMPLETE,
            AgentTaskRetryPolicy.assessEvidence(base).code,
        )
        val current = base.copy(
            snapshot = base.snapshot.copy(
                events = base.snapshot.events.map { event ->
                    event.copy(
                        metadata = (event.metadata as RunEventMetadata.Recovery).copy(
                            retryEvidenceFingerprint = AgentTaskRetryEvidenceFingerprint.calculate(base),
                        ),
                    )
                },
            ),
        )
        assertEquals(AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN, AgentTaskRetryPolicy.assessEvidence(current).code)

        val drifted = current.copy(
            snapshot = current.snapshot.copy(
                events = listOf(
                    event(
                        type = "run.recovered",
                        metadata = RunEventMetadata.Recovery(
                            fromStatus = AgentRunStatus.EXECUTING,
                            toStatus = AgentRunStatus.CANCELLED,
                            reason = "应用重启后终止上次未完成 Agent 任务",
                            retryEvidenceCode = AgentTaskRetryEvidenceCode.NOT_COMMITTED,
                            retryEvidenceFingerprint = AgentTaskRetryEvidenceFingerprint.calculate(current),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(AgentTaskRetryEvidenceCode.EVIDENCE_INCOMPLETE, AgentTaskRetryPolicy.assessEvidence(drifted).code)
    }

    @Test
    fun startupCleanupCancellationDoesNotTurnPendingWriteIntoUnknownCommit() {
        val detail = detail(
            status = AgentRunStatus.CANCELLED,
            steps = listOf(step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.CANCELLED)),
            events = listOf(
                event(
                    type = "run.recovered",
                    metadata = RunEventMetadata.Recovery(
                        fromStatus = AgentRunStatus.THINKING,
                        toStatus = AgentRunStatus.CANCELLED,
                        reason = "应用重启后终止上次未完成 Agent 任务",
                        retryEvidenceCode = AgentTaskRetryEvidenceCode.NOT_COMMITTED,
                        retryEvidenceFingerprint = AgentTaskRetryEvidenceFingerprint.calculate(
                            detail(
                                status = AgentRunStatus.CANCELLED,
                                steps = listOf(step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.CANCELLED)),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(
            AgentTaskRetryEvidenceCode.NOT_COMMITTED,
            AgentTaskRetryPolicy.assessEvidence(detail).code,
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

    @Test
    fun startupRecoveryRequiresExecutionStepBeforeClaimingUnknownCommit() {
        val validated = v20Detail(
            callRisk = ToolRisk.REQUIRES_APPROVAL,
            resultSuccess = null,
        ).let { detail ->
            detail.copy(
                snapshot = detail.snapshot.copy(
                    run = detail.snapshot.run.copy(status = AgentRunStatus.EXECUTING),
                ),
            )
        }
        val validatedCall = validated.toolLedger.calls.single()
        val proposedOnly = validated.copy(
            snapshot = validated.snapshot.copy(
                events = validated.snapshot.events.filter { it.type == "tool.call.proposed" },
            ),
            toolLedger = validated.toolLedger.copy(
                calls = listOf(
                    validatedCall.copy(
                        validatedEventId = null,
                        validatedAt = null,
                    ),
                ),
            ),
        )

        assertEquals(
            AgentTaskRetryEvidenceCode.NOT_COMMITTED,
            AgentTaskRetryPolicy.assessEvidenceBeforeRecovery(
                proposedOnly,
                AgentRunStatus.EXECUTING,
            ).code,
        )
        assertEquals(
            AgentTaskRetryEvidenceCode.NOT_COMMITTED,
            AgentTaskRetryPolicy.assessEvidenceBeforeRecovery(
                validated,
                AgentRunStatus.EXECUTING,
            ).code,
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
