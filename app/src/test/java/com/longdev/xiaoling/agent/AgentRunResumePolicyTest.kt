package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunResumePolicyTest {
    @Test
    fun waitingApprovalWithoutExecutionCanResumeInPlace() {
        val assessment = AgentRunResumePolicy.assess(detail(status = AgentRunStatus.WAITING_APPROVAL))

        assertEquals(AgentRunResumeKind.APPROVAL_WAIT, assessment.kind)
        assertTrue(assessment.canResumeInPlace)
    }

    @Test
    fun waitingApprovalCannotExpandOriginalAgentProfileToolAllowList() {
        val profile = AgentProfileSnapshot(
            id = "agent-read-only",
            name = "只读 Agent",
            avatar = "读",
            providerId = "provider-1",
            model = "gpt-test",
            apiMode = com.longdev.xiaoling.model.ApiMode.RESPONSES,
            systemPrompt = "",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("app.current_time"),
            allowedSkillIds = listOf("device-time"),
            memoryEnabled = false,
        )

        val assessment = AgentRunResumePolicy.assess(
            detail(
                status = AgentRunStatus.WAITING_APPROVAL,
                events = listOf(
                    event(
                        AgentEventTypes.PROFILE_SELECTED,
                        RunEventMetadata.AgentProfileSelection(profile),
                        0L,
                    ),
                ),
            ),
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertTrue(assessment.reason.contains("Profile 白名单"))
    }

    @Test
    fun duplicateAgentProfileAuditFailsClosed() {
        val profile = AgentProfileSnapshot(
            id = "agent-duplicate",
            name = "重复 Agent",
            avatar = "重",
            providerId = "provider-1",
            model = "gpt-test",
            apiMode = com.longdev.xiaoling.model.ApiMode.CHAT_COMPLETIONS,
            systemPrompt = "",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf("notes.create"),
            allowedSkillIds = emptyList(),
            memoryEnabled = true,
        )
        val metadata = RunEventMetadata.AgentProfileSelection(profile)

        val assessment = AgentRunResumePolicy.assess(
            detail(
                status = AgentRunStatus.WAITING_APPROVAL,
                events = listOf(
                    event(AgentEventTypes.PROFILE_SELECTED, metadata, 0L),
                    event(AgentEventTypes.PROFILE_SELECTED, metadata, 1L),
                ),
            ),
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertTrue(assessment.reason.contains("重复 Profile"))
    }

    @Test
    fun executionStartedRequiresSafeRestart() {
        val assessment = AgentRunResumePolicy.assess(
            detail(
                status = AgentRunStatus.WAITING_APPROVAL,
                steps = listOf(
                    step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.COMPLETED),
                    step("approval", AgentStepStatus.RUNNING, sequence = 2),
                ),
            ),
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertFalse(assessment.canResumeInPlace)
    }

    @Test
    fun nonApprovalStatusRequiresSafeRestart() {
        val assessment = AgentRunResumePolicy.assess(detail(status = AgentRunStatus.THINKING))

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertTrue(assessment.reason.contains("等待用户审批"))
    }

    @Test
    fun committedIdempotentToolAwaitingVerificationCanResumeInPlace() {
        val definition = ToolDefinition(
            name = "notes.create",
            description = "创建笔记",
            risk = ToolRisk.REQUIRES_APPROVAL,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
        )
        val call = ToolCall(
            id = "tool-call-note-recovery",
            name = definition.name,
            arguments = mapOf("title" to "恢复笔记", "content" to "只重新验证已提交结果"),
            risk = definition.risk,
        )
        val result = RunEventMetadata.ToolResult(
            toolName = definition.name,
            content = "已创建并验证笔记：恢复笔记",
            durationMs = 10L,
            success = true,
            verified = true,
            toolCallId = call.id,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            executionReceipt = ToolExecutionReceipt(
                toolCallId = call.id,
                operationId = "note-recovery",
                idempotencyKey = call.id,
                status = ToolExecutionReceiptStatus.COMMITTED,
            ),
        )
        val assessment = AgentRunResumePolicy.assess(
            detail = detail(
                status = AgentRunStatus.EXECUTING,
                steps = listOf(step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.RUNNING)),
                approvals = emptyList(),
                events = listOf(
                    event("tool.call.validated", RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments), 1L),
                    event("tool.result", result, 2L),
                ),
            ),
            definitionLookup = { name -> definition.takeIf { it.name == name } },
            committedVerificationSupport = { name -> name == definition.name },
        )

        assertEquals(AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION, assessment.kind)
        assertTrue(assessment.canResumeInPlace)
        assertNotNull(assessment.committedTool)
        assertEquals(call, assessment.committedTool?.toolCall)
        assertEquals(result, assessment.committedTool?.persistedResult)
        assertEquals(
            AgentRunRecoveryEvidenceSource.EVENT_FALLBACK,
            assessment.committedTool?.evidenceSource,
        )
        assertTrue(assessment.reason.contains("旧 Run typed event"))
    }

    @Test
    fun idempotentToolWithoutReadOnlyVerificationSupportStillRequiresRestart() {
        val definition = ToolDefinition(
            name = "memory.remember",
            description = "写入长期记忆",
            risk = ToolRisk.REQUIRES_APPROVAL,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
        )
        val call = ToolCall(
            id = "tool-call-memory-no-recovery",
            name = definition.name,
            arguments = mapOf("note" to "用户喜欢紧凑界面"),
            risk = definition.risk,
        )
        val assessment = AgentRunResumePolicy.assess(
            detail = detail(
                status = AgentRunStatus.EXECUTING,
                steps = listOf(step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.RUNNING)),
                approvals = emptyList(),
                events = listOf(
                    event("tool.call.validated", RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments), 1L),
                    event(
                        "tool.result",
                        RunEventMetadata.ToolResult(
                            toolName = call.name,
                            content = "已保存长期记忆",
                            durationMs = 10L,
                            success = true,
                            verified = true,
                            toolCallId = call.id,
                            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                            executionReceipt = ToolExecutionReceipt(
                                toolCallId = call.id,
                                operationId = "memory-1",
                                idempotencyKey = call.id,
                                status = ToolExecutionReceiptStatus.COMMITTED,
                            ),
                        ),
                        2L,
                    ),
                ),
            ),
            definitionLookup = { name -> definition.takeIf { it.name == name } },
            committedVerificationSupport = { false },
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertTrue(assessment.reason.contains("未开放"))
    }

    @Test
    fun completeV20LedgerDrivesCommittedVerificationRecovery() {
        val definition = ToolDefinition(
            name = "notes.create",
            description = "创建笔记",
            risk = ToolRisk.REQUIRES_APPROVAL,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
        )
        val call = ToolCall(
            id = "tool-call-v20-ledger-resume",
            name = definition.name,
            arguments = mapOf("title" to "v20", "content" to "账本恢复"),
            risk = definition.risk,
        )
        val receipt = ToolExecutionReceipt(
            toolCallId = call.id,
            operationId = "note-v20-ledger-resume",
            idempotencyKey = call.id,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )
        val result = RunEventMetadata.ToolResult(
            toolName = call.name,
            content = "已创建笔记：v20",
            durationMs = 8L,
            success = true,
            verified = true,
            toolCallId = call.id,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            executionReceipt = receipt,
        )
        val assessment = AgentRunResumePolicy.assess(
            detail = detail(
                status = AgentRunStatus.EXECUTING,
                steps = listOf(step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.RUNNING)),
                approvals = emptyList(),
                events = listOf(
                    event(
                        "tool.call.proposed",
                        RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
                        1L,
                    ),
                    event(
                        "tool.call.validated",
                        RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
                        2L,
                    ),
                    event("tool.result", result, 3L),
                ),
                toolLedger = AgentToolLedgerRecord(
                    calls = listOf(
                        AgentToolCallRecord(
                            id = call.id,
                            runId = "run-1",
                            toolName = call.name,
                            risk = call.risk,
                            arguments = call.arguments,
                            proposedEventId = "event-1",
                            validatedEventId = "event-2",
                            createdAt = 1L,
                            validatedAt = 2L,
                        ),
                    ),
                    results = listOf(
                        AgentToolResultRecord(
                            toolCallId = call.id,
                            runId = "run-1",
                            eventId = "event-3",
                            toolName = call.name,
                            content = result.content,
                            success = true,
                            errorMessage = null,
                            durationMs = result.durationMs,
                            executorVerified = true,
                            verificationStatus = null,
                            verifiedEventId = null,
                            memoryIdsUsed = emptyList(),
                            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                            executionReceipt = receipt,
                            createdAt = 3L,
                            verifiedAt = null,
                        ),
                    ),
                ),
            ),
            definitionLookup = { definition },
            committedVerificationSupport = { true },
        )

        assertEquals(AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION, assessment.kind)
        assertEquals(AgentRunRecoveryEvidenceSource.LEDGER, assessment.committedTool?.evidenceSource)
        assertEquals(call, assessment.committedTool?.toolCall)
        assertEquals(result, assessment.committedTool?.persistedResult)
        assertTrue(assessment.reason.contains("独立工具账本"))
    }

    @Test
    fun multiStepV20LedgerRestoresVerifiedPrefixAndOnlyVerifiesTheLastResult() {
        val firstCall = ToolCall(
            id = "tool-call-ledger-prefix",
            name = "notes.create",
            arguments = mapOf("title" to "前序", "content" to "已验证"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val pendingCall = ToolCall(
            id = "tool-call-ledger-pending",
            name = "memory.remember",
            arguments = mapOf("note" to "第二步等待只读验证"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val firstReceipt = ToolExecutionReceipt(
            toolCallId = firstCall.id,
            operationId = "note-ledger-prefix",
            idempotencyKey = firstCall.id,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )
        val pendingReceipt = ToolExecutionReceipt(
            toolCallId = pendingCall.id,
            operationId = "memory-ledger-pending",
            idempotencyKey = pendingCall.id,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )
        val firstResult = RunEventMetadata.ToolResult(
            toolName = firstCall.name,
            content = "已创建并验证前序笔记",
            durationMs = 5L,
            success = true,
            verified = true,
            toolCallId = firstCall.id,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            executionReceipt = firstReceipt,
        )
        val pendingResult = RunEventMetadata.ToolResult(
            toolName = pendingCall.name,
            content = "已保存长期记忆，等待后置读取",
            durationMs = 7L,
            success = true,
            verified = true,
            toolCallId = pendingCall.id,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            executionReceipt = pendingReceipt,
        )
        val assessment = AgentRunResumePolicy.assess(
            detail = detail(
                status = AgentRunStatus.VERIFYING,
                steps = listOf(
                    step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.COMPLETED, sequence = 1),
                    step(AgentStepTypes.TOOL_VERIFY, AgentStepStatus.COMPLETED, sequence = 2),
                    step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.COMPLETED, sequence = 3),
                    step(AgentStepTypes.TOOL_VERIFY, AgentStepStatus.RUNNING, sequence = 4),
                ),
                approvals = emptyList(),
                events = listOf(
                    event(
                        "tool.call.proposed",
                        RunEventMetadata.ToolCall(firstCall.id, firstCall.name, firstCall.risk, firstCall.arguments),
                        1L,
                    ),
                    event(
                        "tool.call.validated",
                        RunEventMetadata.ToolCall(firstCall.id, firstCall.name, firstCall.risk, firstCall.arguments),
                        2L,
                    ),
                    event("tool.result", firstResult, 3L),
                    event(
                        "tool.verify",
                        RunEventMetadata.ToolVerification(
                            toolName = firstCall.name,
                            status = ToolVerificationStatus.PASSED,
                            toolCallId = firstCall.id,
                        ),
                        4L,
                    ),
                    event(
                        "tool.call.proposed",
                        RunEventMetadata.ToolCall(pendingCall.id, pendingCall.name, pendingCall.risk, pendingCall.arguments),
                        5L,
                    ),
                    event(
                        "tool.call.validated",
                        RunEventMetadata.ToolCall(pendingCall.id, pendingCall.name, pendingCall.risk, pendingCall.arguments),
                        6L,
                    ),
                    event("tool.result", pendingResult, 7L),
                ),
                toolLedger = AgentToolLedgerRecord(
                    calls = listOf(
                        AgentToolCallRecord(
                            id = firstCall.id,
                            runId = "run-1",
                            toolName = firstCall.name,
                            risk = firstCall.risk,
                            arguments = firstCall.arguments,
                            proposedEventId = "event-1",
                            validatedEventId = "event-2",
                            createdAt = 1L,
                            validatedAt = 2L,
                        ),
                        AgentToolCallRecord(
                            id = pendingCall.id,
                            runId = "run-1",
                            toolName = pendingCall.name,
                            risk = pendingCall.risk,
                            arguments = pendingCall.arguments,
                            proposedEventId = "event-5",
                            validatedEventId = "event-6",
                            createdAt = 5L,
                            validatedAt = 6L,
                        ),
                    ),
                    // long: Repository 当前按时间返回结果，但恢复契约必须依据调用锚点重建顺序，不能把传入列表顺序误当成工具执行顺序。
                    results = listOf(
                        AgentToolResultRecord(
                            toolCallId = firstCall.id,
                            runId = "run-1",
                            eventId = "event-3",
                            toolName = firstCall.name,
                            content = firstResult.content,
                            success = true,
                            errorMessage = null,
                            durationMs = firstResult.durationMs,
                            executorVerified = true,
                            verificationStatus = ToolVerificationStatus.PASSED,
                            verifiedEventId = "event-4",
                            memoryIdsUsed = emptyList(),
                            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                            executionReceipt = firstReceipt,
                            createdAt = 3L,
                            verifiedAt = 4L,
                        ),
                        AgentToolResultRecord(
                            toolCallId = pendingCall.id,
                            runId = "run-1",
                            eventId = "event-7",
                            toolName = pendingCall.name,
                            content = pendingResult.content,
                            success = true,
                            errorMessage = null,
                            durationMs = pendingResult.durationMs,
                            executorVerified = true,
                            verificationStatus = null,
                            verifiedEventId = null,
                            memoryIdsUsed = emptyList(),
                            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                            executionReceipt = pendingReceipt,
                            createdAt = 7L,
                            verifiedAt = null,
                        ),
                    ).reversed(),
                ),
            ),
            definitionLookup = { name ->
                ToolDefinition(
                    name = name,
                    description = "恢复最后一个已提交结果",
                    risk = ToolRisk.REQUIRES_APPROVAL,
                    replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                )
            },
            committedVerificationSupport = { it == pendingCall.name },
        )

        assertEquals(AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION, assessment.kind)
        val recovery = checkNotNull(assessment.committedTool)
        assertEquals(AgentRunRecoveryEvidenceSource.LEDGER, recovery.evidenceSource)
        assertEquals(firstCall, recovery.verifiedPrefix.single().toolCall)
        assertEquals(firstResult.content, recovery.verifiedPrefix.single().toolResult.content)
        assertEquals(pendingCall, recovery.toolCall)
        assertEquals(pendingResult, recovery.persistedResult)
        assertEquals("step-3", recovery.executionStepId)
        assertEquals("step-4", recovery.verificationStepId)
    }

    private fun detail(
        status: AgentRunStatus,
        steps: List<AgentStepRecord> = emptyList(),
        approvals: List<ApprovalRequestRecord> = listOf(
            ApprovalRequestRecord(
                id = "approval-1",
                runId = "run-1",
                conversationId = "conversation-1",
                toolCallId = "tool-call-1",
                toolName = "notes.create",
                toolDescription = "创建笔记",
                risk = ToolRisk.REQUIRES_APPROVAL,
                arguments = mapOf("title" to "待确认"),
                status = ApprovalRequestStatus.PENDING,
                decisionReason = null,
                createdAt = 1L,
                expiresAt = APPROVAL_REQUEST_NO_EXPIRY_AT,
                decidedAt = null,
            ),
        ),
        events: List<RunEventRecord> = emptyList(),
        toolLedger: AgentToolLedgerRecord = AgentToolLedgerRecord(),
    ) = AgentRunDetailRecord(
        snapshot = AgentRunSnapshot(
            run = AgentRunRecord(
                id = "run-1",
                conversationId = "conversation-1",
                userMessageId = "message-1",
                goal = "创建笔记",
                status = status,
                result = null,
                errorMessage = null,
                createdAt = 1L,
                updatedAt = 1L,
                completedAt = null,
            ),
            steps = steps,
            events = events,
        ),
        approvals = approvals,
        toolLedger = toolLedger,
    )

    private fun step(type: String, status: AgentStepStatus, sequence: Int = 1) = AgentStepRecord(
        id = "step-$sequence",
        runId = "run-1",
        sequence = sequence,
        type = type,
        status = status,
        title = "执行工具",
        detail = "notes.create",
        createdAt = 1L,
        completedAt = null,
    )

    private fun event(type: String, metadata: RunEventMetadata, createdAt: Long) = RunEventRecord(
        id = "event-$createdAt",
        runId = "run-1",
        type = type,
        message = type,
        createdAt = createdAt,
        metadata = metadata,
    )
}
