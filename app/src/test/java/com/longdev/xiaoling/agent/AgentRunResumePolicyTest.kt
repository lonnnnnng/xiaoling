package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunResumePolicyTest {
    @Test
    fun waitingApprovalWithoutExecutionCanResumeInPlace() {
        val assessment = AgentRunResumePolicy.assess(validPendingApprovalDetail())

        assertEquals(AgentRunResumeKind.APPROVAL_WAIT, assessment.kind)
        assertTrue(assessment.canResumeInPlace)
        assertNotNull(assessment.approvalWait)
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
            validPendingApprovalDetail(
                extraEvents = listOf(
                    event(
                        AgentEventTypes.PROFILE_SELECTED,
                        RunEventMetadata.AgentProfileSelection(profile),
                        -1L,
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
            validPendingApprovalDetail(
                extraEvents = listOf(
                    event(AgentEventTypes.PROFILE_SELECTED, metadata, -2L),
                    event(AgentEventTypes.PROFILE_SELECTED, metadata, -1L),
                ),
            ),
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertTrue(assessment.reason.contains("重复 Profile"))
        assertEquals(
            AgentRunRestartDispositionCode.PROFILE_EVIDENCE_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
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
    fun executingLedgerCallWithoutResultUsesCommitUnknownDisposition() {
        val call = ToolCall(
            id = "tool-call-commit-unknown",
            name = "notes.create",
            arguments = mapOf("title" to "提交状态未知"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val assessment = AgentRunResumePolicy.assess(
            detail(
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
                ),
            ),
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertFalse(assessment.canResumeInPlace)
        val disposition = checkNotNull(assessment.restartDisposition)
        assertEquals(AgentRunRestartDispositionCode.COMMIT_UNKNOWN, disposition.code)
        assertTrue(disposition.reason.contains("提交状态未知"))
        assertTrue(disposition.evidenceBoundary.contains("持久化结果"))
    }

    @Test
    fun validatedLedgerCallWithoutExecutionStepDoesNotClaimCommitUnknown() {
        val call = ToolCall(
            id = "tool-call-before-execution-step",
            name = "notes.create",
            arguments = mapOf("title" to "尚未进入执行步骤"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val assessment = AgentRunResumePolicy.assess(
            detail(
                status = AgentRunStatus.EXECUTING,
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
                ),
            ),
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
    }

    @Test
    fun validatedIdempotentToolBeforeExecutionBoundaryQualifiesForControlledReplay() {
        val fixture = controlledReplayFixture()
        val assessment = AgentRunResumePolicy.assess(
            fixture.detail,
            definitionLookup = { name -> fixture.definition.takeIf { it.name == name } },
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertFalse(assessment.canResumeInPlace)
        assertEquals(
            AgentRunRestartDispositionCode.NOT_COMMITTED_REPLAY_ELIGIBLE,
            checkNotNull(assessment.restartDisposition).code,
        )
    }

    @Test
    fun settledControlledReplayRequalifiesAgainstLatestPersistedEvidence() {
        val fixture = controlledReplayFixture()
        val settled = settledControlledReplayDetail(fixture)

        val assessment = AgentNotCommittedReplayQualificationPolicy.assessRecovered(
            detail = settled,
            agentProfile = fixture.profile,
            definitionLookup = { name -> fixture.definition.takeIf { it.name == name } },
        )

        assertTrue(assessment is AgentNotCommittedReplayQualificationAssessment.Eligible)
        assertEquals(
            "tool-call-safe-replay",
            (assessment as AgentNotCommittedReplayQualificationAssessment.Eligible).qualification.toolCall.id,
        )
        assertEquals(AgentRunStatus.CANCELLED, settled.snapshot.run.status)
    }

    @Test
    fun settledControlledReplayRejectsMissingRecoveryEvent() {
        val fixture = controlledReplayFixture()
        val settled = fixture.detail.copy(
            snapshot = fixture.detail.snapshot.copy(
                run = fixture.detail.snapshot.run.copy(status = AgentRunStatus.CANCELLED),
            ),
        )

        val assessment = AgentNotCommittedReplayQualificationPolicy.assessRecovered(
            detail = settled,
            agentProfile = fixture.profile,
            definitionLookup = { fixture.definition },
        )

        assertIneligibleReason(assessment, "缺少最新的启动恢复事件")
    }

    @Test
    fun settledControlledReplayRejectsRecoveryStatusDrift() {
        val fixture = controlledReplayFixture()
        val cases = listOf(
            settledControlledReplayDetail(fixture, fromStatus = AgentRunStatus.THINKING),
            settledControlledReplayDetail(fixture, toStatus = AgentRunStatus.FAILED),
        )

        cases.forEach { settled ->
            val assessment = AgentNotCommittedReplayQualificationPolicy.assessRecovered(
                detail = settled,
                agentProfile = fixture.profile,
                definitionLookup = { fixture.definition },
            )
            assertIneligibleReason(assessment, "不再匹配")
        }
    }

    @Test
    fun settledControlledReplayRejectsBusinessEvidenceAfterRecovery() {
        val fixture = controlledReplayFixture()
        val settled = settledControlledReplayDetail(
            fixture = fixture,
            extraTrailingEvents = listOf(
                event("tool.result", RunEventMetadata.Reason("恢复后出现异常业务证据"), 7L),
            ),
        )

        val assessment = AgentNotCommittedReplayQualificationPolicy.assessRecovered(
            detail = settled,
            agentProfile = fixture.profile,
            definitionLookup = { fixture.definition },
        )

        assertIneligibleReason(assessment, "非预期运行证据")
    }

    @Test
    fun settledControlledReplayRejectsRetryEvidenceFingerprintDrift() {
        val fixture = controlledReplayFixture()
        val settled = settledControlledReplayDetail(fixture)
        val drifted = settled.copy(
            toolLedger = settled.toolLedger.copy(
                calls = settled.toolLedger.calls.map { call ->
                    call.copy(arguments = call.arguments + ("content" to "证据已变化"))
                },
            ),
        )

        val assessment = AgentNotCommittedReplayQualificationPolicy.assessRecovered(
            detail = drifted,
            agentProfile = fixture.profile,
            definitionLookup = { fixture.definition },
        )

        assertIneligibleReason(assessment, "证据已经漂移")
    }

    @Test
    fun settledControlledReplayRejectsCurrentToolDefinitionDrift() {
        val fixture = controlledReplayFixture()
        val settled = settledControlledReplayDetail(fixture)
        val driftedDefinition = fixture.definition.copy(description = "创建本地笔记 v2")

        val assessment = AgentNotCommittedReplayQualificationPolicy.assessRecovered(
            detail = settled,
            agentProfile = fixture.profile,
            definitionLookup = { driftedDefinition },
        )

        assertIneligibleReason(assessment, "指纹不一致")
    }

    @Test
    fun controlledReplayRejectsCurrentToolDefinitionDrift() {
        val fixture = controlledReplayFixture()
        val driftedDefinition = fixture.definition.copy(description = "创建本地笔记 v2")

        val assessment = AgentNotCommittedReplayQualificationPolicy.assess(
            detail = fixture.detail,
            agentProfile = fixture.profile,
            definitionLookup = { name -> driftedDefinition.takeIf { it.name == name } },
        )

        assertTrue(assessment is AgentNotCommittedReplayQualificationAssessment.Ineligible)
        assertTrue((assessment as AgentNotCommittedReplayQualificationAssessment.Ineligible).reason.contains("指纹不一致"))
    }

    @Test
    fun controlledReplayRejectsApprovalDecisionFingerprintDrift() {
        val fixture = controlledReplayFixture(decidedDefinitionFingerprint = "drifted-definition")

        val assessment = AgentNotCommittedReplayQualificationPolicy.assess(
            detail = fixture.detail,
            agentProfile = fixture.profile,
            definitionLookup = { name -> fixture.definition.takeIf { it.name == name } },
        )

        assertTrue(assessment is AgentNotCommittedReplayQualificationAssessment.Ineligible)
        assertTrue((assessment as AgentNotCommittedReplayQualificationAssessment.Ineligible).reason.contains("审批事件"))
    }

    @Test
    fun controlledReplayRejectsApprovalArgumentsDrift() {
        val fixture = controlledReplayFixture(
            decidedArguments = mapOf("title" to "被替换", "content" to "尚未执行"),
        )

        val assessment = AgentNotCommittedReplayQualificationPolicy.assess(
            detail = fixture.detail,
            agentProfile = fixture.profile,
            definitionLookup = { name -> fixture.definition.takeIf { it.name == name } },
        )

        assertTrue(assessment is AgentNotCommittedReplayQualificationAssessment.Ineligible)
        assertTrue((assessment as AgentNotCommittedReplayQualificationAssessment.Ineligible).reason.contains("审批事件"))
    }

    @Test
    fun controlledReplayRejectsRequestedEventThatIsNotPending() {
        val fixture = controlledReplayFixture(requestedStatus = ApprovalRequestStatus.APPROVED)

        val assessment = AgentNotCommittedReplayQualificationPolicy.assess(
            detail = fixture.detail,
            agentProfile = fixture.profile,
            definitionLookup = { name -> fixture.definition.takeIf { it.name == name } },
        )

        assertTrue(assessment is AgentNotCommittedReplayQualificationAssessment.Ineligible)
        assertTrue((assessment as AgentNotCommittedReplayQualificationAssessment.Ineligible).reason.contains("审批事件"))
    }

    @Test
    fun controlledReplayRejectsApprovalEventsPersistedOutOfOrder() {
        val fixture = controlledReplayFixture(reverseApprovalEventOrder = true)

        val assessment = AgentNotCommittedReplayQualificationPolicy.assess(
            detail = fixture.detail,
            agentProfile = fixture.profile,
            definitionLookup = { name -> fixture.definition.takeIf { it.name == name } },
        )

        assertTrue(assessment is AgentNotCommittedReplayQualificationAssessment.Ineligible)
        assertTrue((assessment as AgentNotCommittedReplayQualificationAssessment.Ineligible).reason.contains("顺序"))
    }

    @Test
    fun controlledReplayRejectsLegacyToolCallWithoutRecoveryContract() {
        val fixture = controlledReplayFixture(
            proposedRecoveryContract = null,
            validatedRecoveryContract = null,
        )

        val assessment = AgentNotCommittedReplayQualificationPolicy.assess(
            detail = fixture.detail,
            agentProfile = fixture.profile,
            definitionLookup = { name -> fixture.definition.takeIf { it.name == name } },
        )

        assertTrue(assessment is AgentNotCommittedReplayQualificationAssessment.Ineligible)
        assertTrue((assessment as AgentNotCommittedReplayQualificationAssessment.Ineligible).reason.contains("历史记录"))
    }

    @Test
    fun controlledReplayRejectsToolWhosePolicyDefaultsToDeny() {
        val definition = controlledReplayDefinition().copy(
            notCommittedReplayPolicy = ToolNotCommittedReplayPolicy.DENY,
        )
        val fixture = controlledReplayFixture(definition = definition)

        val assessment = AgentNotCommittedReplayQualificationPolicy.assess(
            detail = fixture.detail,
            agentProfile = fixture.profile,
            definitionLookup = { name -> fixture.definition.takeIf { it.name == name } },
        )

        assertTrue(assessment is AgentNotCommittedReplayQualificationAssessment.Ineligible)
        assertTrue((assessment as AgentNotCommittedReplayQualificationAssessment.Ineligible).reason.contains("没有声明"))
    }

    @Test
    fun controlledReplayWithPersistedExecutionStepRemainsCommitUnknown() {
        val fixture = controlledReplayFixture(includeExecutionStep = true)

        val assessment = AgentRunResumePolicy.assess(
            fixture.detail,
            definitionLookup = { name -> fixture.definition.takeIf { it.name == name } },
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertFalse(assessment.canResumeInPlace)
        assertEquals(
            AgentRunRestartDispositionCode.COMMIT_UNKNOWN,
            checkNotNull(assessment.restartDisposition).code,
        )
    }

    @Test
    fun legacyValidatedCallWithRunningExecutionStepUsesCommitUnknownDisposition() {
        val call = ToolCall(
            id = "legacy-tool-call-commit-unknown",
            name = "notes.create",
            arguments = mapOf("title" to "旧 Run 提交状态未知"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val assessment = AgentRunResumePolicy.assess(
            detail(
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
                ),
            ),
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.COMMIT_UNKNOWN,
            checkNotNull(assessment.restartDisposition).code,
        )
    }

    @Test
    fun executingLedgerCallWithoutValidationDoesNotClaimCommitUnknown() {
        val call = ToolCall(
            id = "tool-call-not-validated",
            name = "notes.create",
            arguments = mapOf("title" to "尚未校验"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val assessment = AgentRunResumePolicy.assess(
            detail(
                status = AgentRunStatus.EXECUTING,
                steps = listOf(step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.RUNNING)),
                approvals = emptyList(),
                events = listOf(
                    event(
                        "tool.call.proposed",
                        RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
                        1L,
                    ),
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
                            validatedEventId = null,
                            createdAt = 1L,
                            validatedAt = null,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
    }

    @Test
    fun secondApprovalAfterVerifiedToolCanResumeInPlace() {
        val firstCall = ToolCall(
            id = "tool-call-first",
            name = "notes.create",
            arguments = mapOf("title" to "第一步"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val pendingCall = ToolCall(
            id = "tool-call-second",
            name = "memory.remember",
            arguments = mapOf("note" to "第二步"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val detail = pendingApprovalAfterVerifiedPrefix(firstCall, pendingCall)

        val assessment = AgentRunResumePolicy.assess(detail)

        assertEquals(AgentRunResumeKind.APPROVAL_WAIT, assessment.kind)
        val recovery = checkNotNull(assessment.approvalWait)
        assertEquals(pendingCall, recovery.toolCall)
        assertEquals(firstCall, recovery.verifiedPrefix.single().toolCall)
        assertEquals("step-3", recovery.approvalStepId)
        assertEquals(AgentRunRecoveryEvidenceSource.LEDGER, recovery.evidenceSource)
    }

    @Test
    fun secondApprovalWithMismatchedRequestRequiresRestart() {
        val firstCall = ToolCall(
            id = "tool-call-first",
            name = "notes.create",
            arguments = mapOf("title" to "第一步"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val pendingCall = ToolCall(
            id = "tool-call-second",
            name = "memory.remember",
            arguments = mapOf("note" to "第二步"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val valid = pendingApprovalAfterVerifiedPrefix(firstCall, pendingCall)
        val drifted = valid.copy(
            approvals = valid.approvals.map { it.copy(arguments = mapOf("note" to "参数漂移")) },
        )

        val assessment = AgentRunResumePolicy.assess(drifted)

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertTrue(assessment.reason.contains("不一致"))
        assertEquals(
            AgentRunRestartDispositionCode.APPROVAL_BOUNDARY_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
    }

    @Test
    fun secondApprovalWithUnverifiedPrefixRequiresRestart() {
        val firstCall = ToolCall(
            id = "tool-call-first",
            name = "notes.create",
            arguments = mapOf("title" to "第一步"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val pendingCall = ToolCall(
            id = "tool-call-second",
            name = "memory.remember",
            arguments = mapOf("note" to "第二步"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val valid = pendingApprovalAfterVerifiedPrefix(firstCall, pendingCall)
        val unverified = valid.copy(
            toolLedger = valid.toolLedger.copy(
                results = valid.toolLedger.results.map { it.copy(verificationStatus = null, verifiedEventId = null, verifiedAt = null) },
            ),
        )

        val assessment = AgentRunResumePolicy.assess(unverified)

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
    }

    @Test
    fun nonApprovalStatusRequiresSafeRestart() {
        val assessment = AgentRunResumePolicy.assess(detail(status = AgentRunStatus.THINKING))

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertTrue(assessment.reason.contains("等待用户审批"))
        val disposition = checkNotNull(assessment.restartDisposition)
        assertEquals(AgentRunRestartDispositionCode.RUN_STATE_NOT_RESUMABLE, disposition.code)
        assertTrue(disposition.evidenceBoundary.contains("旧模型协程"))
        assertTrue(disposition.suggestedAction.contains("关联新 Run"))
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
    fun fullyVerifiedToolCanCompleteAfterVerificationControlPlaneInterruption() {
        listOf(AgentStepStatus.RUNNING, AgentStepStatus.COMPLETED).forEach { verificationStepStatus ->
            val assessment = AgentRunResumePolicy.assess(fullyVerifiedDetail(verificationStepStatus))

            assertEquals(AgentRunResumeKind.VERIFIED_TOOL_COMPLETION, assessment.kind)
            val recovery = checkNotNull(assessment.verifiedTool)
            assertEquals("tool-call-verified", recovery.verifiedTools.single().toolCall.id)
            assertEquals("step-2", recovery.lastVerificationStepId)
            assertEquals(AgentRunRecoveryEvidenceSource.EVENT_FALLBACK, recovery.evidenceSource)
        }
    }

    @Test
    fun fullyVerifiedToolCanResumeAfterRecoverySummaryTailWasPartiallyPersisted() {
        listOf(AgentStepStatus.RUNNING, AgentStepStatus.COMPLETED).forEach { summaryStepStatus ->
            val base = fullyVerifiedDetail(AgentStepStatus.COMPLETED)
            val withRecoveryTail = base.copy(
                snapshot = base.snapshot.copy(
                    steps = base.snapshot.steps + step(
                        type = AgentStepTypes.RECOVERY_SUMMARIZE,
                        status = summaryStepStatus,
                        sequence = 3,
                    ),
                    events = base.snapshot.events + event(
                        AgentEventTypes.RECOVERY_SUMMARY,
                        RunEventMetadata.Reason("恢复总结事件已落库"),
                        5L,
                    ),
                ),
            )

            val assessment = AgentRunResumePolicy.assess(withRecoveryTail)

            assertEquals(AgentRunResumeKind.VERIFIED_TOOL_COMPLETION, assessment.kind)
            assertEquals(
                "step-3",
                checkNotNull(assessment.verifiedTool).recoverySummaryStepId,
            )
        }
    }

    @Test
    fun fullyVerifiedToolRejectsCompletedRecoverySummaryStepWithoutEvent() {
        val base = fullyVerifiedDetail(AgentStepStatus.COMPLETED)
        val invalid = base.copy(
            snapshot = base.snapshot.copy(
                steps = base.snapshot.steps + step(
                    type = AgentStepTypes.RECOVERY_SUMMARIZE,
                    status = AgentStepStatus.COMPLETED,
                    sequence = 3,
                ),
            ),
        )

        val assessment = AgentRunResumePolicy.assess(invalid)

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertTrue(assessment.reason.contains("步骤已完成但总结事件缺失"))
    }

    @Test
    fun fullyVerifiedToolRejectsRecoverySummaryWithoutTypedReason() {
        val base = fullyVerifiedDetail(AgentStepStatus.COMPLETED)
        val invalid = base.copy(
            snapshot = base.snapshot.copy(
                steps = base.snapshot.steps + step(
                    type = AgentStepTypes.RECOVERY_SUMMARIZE,
                    status = AgentStepStatus.RUNNING,
                    sequence = 3,
                ),
                events = base.snapshot.events + RunEventRecord(
                    id = "event-5",
                    runId = "run-1",
                    type = AgentEventTypes.RECOVERY_SUMMARY,
                    message = "损坏的恢复总结",
                    createdAt = 5L,
                    metadata = null,
                ),
            ),
        )

        val assessment = AgentRunResumePolicy.assess(invalid)

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertTrue(assessment.reason.contains("Reason 元数据"))
    }

    @Test
    fun fullyVerifiedToolRejectsForeignVerificationStepStatusEvent() {
        val base = fullyVerifiedDetail(AgentStepStatus.COMPLETED)
        val invalid = base.copy(
            snapshot = base.snapshot.copy(
                events = base.snapshot.events + event(
                    AgentEventTypes.STEP_STATUS,
                    RunEventMetadata.StepStatus(
                        stepId = "step-foreign",
                        sequence = 2,
                        fromStatus = AgentStepStatus.RUNNING,
                        toStatus = AgentStepStatus.COMPLETED,
                    ),
                    5L,
                ),
            ),
        )

        val assessment = AgentRunResumePolicy.assess(invalid)

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertTrue(assessment.reason.contains("状态事件身份不一致"))
    }

    @Test
    fun fullyVerifiedToolRejectsRecoveryMarkerForAnotherBoundary() {
        val base = fullyVerifiedDetail(AgentStepStatus.COMPLETED)
        val invalid = base.copy(
            snapshot = base.snapshot.copy(
                events = base.snapshot.events + event(
                    "run.recovered",
                    RunEventMetadata.Recovery(
                        fromStatus = AgentRunStatus.VERIFYING,
                        toStatus = AgentRunStatus.VERIFYING,
                        reason = "边界漂移",
                        resumeKind = AgentRunResumeKind.VERIFIED_TOOL_COMPLETION,
                        recoveryBoundaryKey = "${AgentRunResumeKind.VERIFIED_TOOL_COMPLETION.name}:step-foreign",
                    ),
                    5L,
                ),
            ),
        )

        val assessment = AgentRunResumePolicy.assess(invalid)

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertTrue(assessment.reason.contains("marker"))
    }

    @Test
    fun fullyVerifiedToolRejectsBusinessEventAfterRecoverySummary() {
        val base = fullyVerifiedDetail(AgentStepStatus.COMPLETED)
        val invalid = base.copy(
            snapshot = base.snapshot.copy(
                steps = base.snapshot.steps + step(
                    type = AgentStepTypes.RECOVERY_SUMMARIZE,
                    status = AgentStepStatus.RUNNING,
                    sequence = 3,
                ),
                events = base.snapshot.events + listOf(
                    event(
                        AgentEventTypes.RECOVERY_SUMMARY,
                        RunEventMetadata.Reason("恢复总结事件已落库"),
                        5L,
                    ),
                    event(
                        AgentEventTypes.LLM_REQUEST_COMPLETED,
                        RunEventMetadata.Reason("不应恢复旧模型请求"),
                        6L,
                    ),
                ),
            ),
        )

        val assessment = AgentRunResumePolicy.assess(invalid)

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
    }

    @Test
    fun fullyVerifiedToolRejectsBusinessStepAfterRecoveryVerification() {
        val base = fullyVerifiedDetail(AgentStepStatus.COMPLETED)
        val invalid = base.copy(
            snapshot = base.snapshot.copy(
                steps = base.snapshot.steps + step(
                    type = AgentStepTypes.LLM_PLAN,
                    status = AgentStepStatus.RUNNING,
                    sequence = 3,
                ),
            ),
        )

        val assessment = AgentRunResumePolicy.assess(invalid)

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
    }

    @Test
    fun fullyVerifiedToolWithFailedVerificationStepRequiresRestart() {
        val assessment = AgentRunResumePolicy.assess(fullyVerifiedDetail(AgentStepStatus.FAILED))

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertTrue(assessment.reason.contains("不可恢复终态"))
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
        assertEquals(
            AgentRunRestartDispositionCode.COMMITTED_VERIFICATION_UNAVAILABLE,
            checkNotNull(assessment.restartDisposition).code,
        )
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
    fun completeV20FailedResultWithPostResultBudgetSettlesOriginalRunFailureInPlace() {
        val fixture = persistedFailureFixture()
        val assessment = AgentRunResumePolicy.assess(fixture.detail)

        assertEquals(AgentRunResumeKind.PERSISTED_TOOL_FAILURE_SETTLEMENT, assessment.kind)
        val recovery = checkNotNull(assessment.persistedToolFailure)
        assertEquals(fixture.call, recovery.toolCall)
        assertEquals("step-1", recovery.executionStepId)
        assertEquals("工具执行失败：网络请求失败", recovery.failureReason)
    }

    @Test
    fun failedResultWithoutPostResultBudgetRemainsFailClosed() {
        val assessment = AgentRunResumePolicy.assess(
            persistedFailureFixture(includePostResultBudget = false).detail,
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.EXECUTION_BUDGET_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
    }

    @Test
    fun legacyEventOnlyFailedResultRemainsFailClosed() {
        val assessment = AgentRunResumePolicy.assess(
            persistedFailureFixture(includeLedger = false).detail,
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
        assertTrue(assessment.reason.contains("独立 Tool Ledger"))
    }

    @Test
    fun businessEventAfterFailedResultBudgetRemainsFailClosed() {
        val assessment = AgentRunResumePolicy.assess(
            persistedFailureFixture(
                extraTrailingEvents = listOf(
                    event("business.tail", RunEventMetadata.Reason("不可达业务尾部"), 5L),
                ),
            ).detail,
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
    }

    @Test
    fun verifiedPrefixAndFailedChainTailSettleOnlyTheFailure() {
        val fixture = persistedFailureFixture(includeVerifiedPrefix = true)
        val assessment = AgentRunResumePolicy.assess(fixture.detail)

        assertEquals(AgentRunResumeKind.PERSISTED_TOOL_FAILURE_SETTLEMENT, assessment.kind)
        val recovery = checkNotNull(assessment.persistedToolFailure)
        assertEquals(fixture.call, recovery.toolCall)
        assertEquals("step-3", recovery.executionStepId)
    }

    @Test
    fun failedResultWithDriftedExecutionStepSequenceRemainsFailClosed() {
        val fixture = persistedFailureFixture()
        val drifted = fixture.detail.copy(
            snapshot = fixture.detail.snapshot.copy(
                steps = fixture.detail.snapshot.steps.map { it.copy(sequence = 9) },
            ),
        )

        val assessment = AgentRunResumePolicy.assess(drifted)

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
    }

    @Test
    fun failedResultWithMismatchedTypedStepCreatedIdentityRemainsFailClosed() {
        val fixture = persistedFailureFixture()
        val drifted = fixture.detail.copy(
            snapshot = fixture.detail.snapshot.copy(
                events = fixture.detail.snapshot.events.map { event ->
                    val metadata = event.metadata as? RunEventMetadata.StepCreated
                    if (metadata?.stepId != "step-1") event else event.copy(
                        metadata = metadata.copy(stepId = "step-forged"),
                    )
                },
            ),
        )

        val assessment = AgentRunResumePolicy.assess(drifted)

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
    }

    @Test
    fun verifiedPrefixWithMismatchedTypedStepStatusIdentityRemainsFailClosed() {
        val fixture = persistedFailureFixture(includeVerifiedPrefix = true)
        val drifted = fixture.detail.copy(
            snapshot = fixture.detail.snapshot.copy(
                events = fixture.detail.snapshot.events.map { event ->
                    val metadata = event.metadata as? RunEventMetadata.StepStatus
                    if (metadata?.stepId != "step-2") event else event.copy(
                        metadata = metadata.copy(sequence = 99),
                    )
                },
            ),
        )

        val assessment = AgentRunResumePolicy.assess(drifted)

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
    }

    @Test
    fun completeV20FailedVerificationWithRunningStepSettlesOriginalRunFailureInPlace() {
        val fixture = persistedVerificationFailureFixture()

        val assessment = AgentRunResumePolicy.assess(fixture.detail)

        assertEquals(
            AgentRunResumeKind.PERSISTED_TOOL_VERIFICATION_FAILURE_SETTLEMENT,
            assessment.kind,
        )
        val recovery = checkNotNull(assessment.persistedToolVerificationFailure)
        assertEquals(fixture.call, recovery.toolCall)
        assertEquals("step-2", recovery.verificationStepId)
        assertEquals("工具验证失败：Executor 回读结果不一致", recovery.failureReason)
    }

    @Test
    fun failedVerificationWithoutExecutionBudgetRemainsFailClosed() {
        val assessment = AgentRunResumePolicy.assess(
            persistedVerificationFailureFixture(
                includeInitialBudget = false,
                includePostResultBudget = false,
            ).detail,
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.EXECUTION_BUDGET_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
        assertTrue(assessment.reason.contains("执行预算快照"))
    }

    @Test
    fun failedVerificationWithoutPostResultBudgetRemainsFailClosed() {
        val assessment = AgentRunResumePolicy.assess(
            persistedVerificationFailureFixture(includePostResultBudget = false).detail,
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.EXECUTION_BUDGET_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
        assertTrue(assessment.reason.contains("后续执行预算快照"))
    }

    @Test
    fun legacyEventOnlyFailedVerificationRemainsFailClosed() {
        val assessment = AgentRunResumePolicy.assess(
            persistedVerificationFailureFixture(includeLedger = false).detail,
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
        assertTrue(assessment.reason.contains("独立 Tool Ledger"))
    }

    @Test
    fun failedVerificationWithoutTypedReasonRemainsFailClosed() {
        val assessment = AgentRunResumePolicy.assess(
            persistedVerificationFailureFixture(verificationReason = null).detail,
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
        assertTrue(assessment.reason.contains("失败原因"))
    }

    @Test
    fun businessEventAfterFailedVerificationRemainsFailClosed() {
        val assessment = AgentRunResumePolicy.assess(
            persistedVerificationFailureFixture(
                extraTrailingEvents = listOf(
                    event("business.tail", RunEventMetadata.Reason("不可达业务尾部"), 6L),
                ),
            ).detail,
        )

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(
            AgentRunRestartDispositionCode.EXECUTION_STEP_EVIDENCE_INVALID,
            checkNotNull(assessment.restartDisposition).code,
        )
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

    private data class PersistedFailureFixture(
        val call: ToolCall,
        val detail: AgentRunDetailRecord,
    )

    private data class PersistedVerificationFailureFixture(
        val call: ToolCall,
        val detail: AgentRunDetailRecord,
    )

    private fun persistedVerificationFailureFixture(
        includeInitialBudget: Boolean = true,
        includePostResultBudget: Boolean = true,
        includeLedger: Boolean = true,
        verificationReason: String? = "Executor 回读结果不一致",
        extraTrailingEvents: List<RunEventRecord> = emptyList(),
    ): PersistedVerificationFailureFixture {
        val call = ToolCall(
            id = "tool-call-v20-verification-failed-settlement",
            name = "notes.create",
            arguments = mapOf("title" to "验证失败边界", "content" to "不重复验证"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val result = RunEventMetadata.ToolResult(
            toolName = call.name,
            content = "笔记写入返回成功",
            durationMs = 12L,
            success = true,
            verified = false,
            toolCallId = call.id,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            executionReceipt = ToolExecutionReceipt(
                toolCallId = call.id,
                operationId = "note-verification-failed-settlement",
                idempotencyKey = call.id,
                status = ToolExecutionReceiptStatus.COMMITTED,
            ),
        )
        val executionStep = step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.COMPLETED, sequence = 1)
        val verificationStep = step(AgentStepTypes.TOOL_VERIFY, AgentStepStatus.RUNNING, sequence = 2)
        return PersistedVerificationFailureFixture(
            call = call,
            detail = detail(
                status = AgentRunStatus.VERIFYING,
                steps = listOf(executionStep, verificationStep),
                approvals = emptyList(),
                events = buildList {
                    if (includeInitialBudget) {
                        add(
                            event(
                                AgentEventTypes.EXECUTION_BUDGET_UPDATED,
                                RunEventMetadata.ExecutionBudget(totalTimeoutMs = 120_000L, consumedMs = 0L),
                                0L,
                            )
                        )
                    }
                    add(
                        event(
                            "tool.call.proposed",
                            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
                            1L,
                        )
                    )
                    add(
                        event(
                            "tool.call.validated",
                            RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
                            2L,
                        )
                    )
                    add(stepCreatedEvent(executionStep))
                    add(event("tool.result", result, 3L))
                    if (includePostResultBudget) {
                        add(
                            event(
                                AgentEventTypes.EXECUTION_BUDGET_UPDATED,
                                RunEventMetadata.ExecutionBudget(totalTimeoutMs = 120_000L, consumedMs = 12L),
                                4L,
                            )
                        )
                    }
                    add(stepCompletedEvent(executionStep))
                    add(stepCreatedEvent(verificationStep))
                    add(
                        event(
                            "tool.verify",
                            RunEventMetadata.ToolVerification(
                                toolName = call.name,
                                status = ToolVerificationStatus.FAILED,
                                toolCallId = call.id,
                                reason = verificationReason,
                            ),
                            5L,
                        )
                    )
                    addAll(extraTrailingEvents)
                },
                toolLedger = if (includeLedger) AgentToolLedgerRecord(
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
                            executorVerified = false,
                            verificationStatus = ToolVerificationStatus.FAILED,
                            verifiedEventId = "event-5",
                            memoryIdsUsed = emptyList(),
                            replaySafety = result.replaySafety,
                            executionReceipt = result.executionReceipt,
                            createdAt = 3L,
                            verifiedAt = 5L,
                        ),
                    ),
                ) else AgentToolLedgerRecord(),
            ),
        )
    }

    private fun persistedFailureFixture(
        includePostResultBudget: Boolean = true,
        includeLedger: Boolean = true,
        includeVerifiedPrefix: Boolean = false,
        extraTrailingEvents: List<RunEventRecord> = emptyList(),
    ): PersistedFailureFixture {
        val failedCall = ToolCall(
            id = "tool-call-v20-failed-settlement",
            name = "notes.create",
            arguments = mapOf("title" to "失败边界", "content" to "不会重放"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val failedReceipt = ToolExecutionReceipt(
            toolCallId = failedCall.id,
            operationId = "note-failed-settlement",
            idempotencyKey = failedCall.id,
            status = ToolExecutionReceiptStatus.UNKNOWN,
        )
        val failedResult = RunEventMetadata.ToolResult(
            toolName = failedCall.name,
            content = "网络请求失败",
            durationMs = 12L,
            success = false,
            verified = false,
            toolCallId = failedCall.id,
            replaySafety = ToolReplaySafety.RESTART_REQUIRED,
            executionReceipt = failedReceipt,
        )
        val firstCall = ToolCall(
            id = "tool-call-v20-failed-prefix",
            name = "app.current_time",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        val firstResult = RunEventMetadata.ToolResult(
            toolName = firstCall.name,
            content = "当前时间：2026-07-28 09:30:00",
            durationMs = 4L,
            success = true,
            verified = true,
            toolCallId = firstCall.id,
        )
        val events = buildList {
            add(
                event(
                    AgentEventTypes.EXECUTION_BUDGET_UPDATED,
                    RunEventMetadata.ExecutionBudget(totalTimeoutMs = 120_000L, consumedMs = 0L),
                    0L,
                ),
            )
            if (includeVerifiedPrefix) {
                add(event("tool.call.proposed", RunEventMetadata.ToolCall(firstCall.id, firstCall.name, firstCall.risk, firstCall.arguments), 1L))
                add(event("tool.call.validated", RunEventMetadata.ToolCall(firstCall.id, firstCall.name, firstCall.risk, firstCall.arguments), 2L))
                add(event("tool.result", firstResult, 3L))
                add(
                    event(
                        "tool.verify",
                        RunEventMetadata.ToolVerification(firstCall.name, ToolVerificationStatus.PASSED, firstCall.id),
                        4L,
                    ),
                )
            }
            val callOffset = if (includeVerifiedPrefix) 4L else 0L
            add(
                event(
                    "tool.call.proposed",
                    RunEventMetadata.ToolCall(failedCall.id, failedCall.name, failedCall.risk, failedCall.arguments),
                    callOffset + 1L,
                ),
            )
            add(
                event(
                    "tool.call.validated",
                    RunEventMetadata.ToolCall(failedCall.id, failedCall.name, failedCall.risk, failedCall.arguments),
                    callOffset + 2L,
                ),
            )
            add(event("tool.result", failedResult, callOffset + 3L))
            if (includePostResultBudget) {
                add(
                    event(
                        AgentEventTypes.EXECUTION_BUDGET_UPDATED,
                        RunEventMetadata.ExecutionBudget(totalTimeoutMs = 120_000L, consumedMs = 16L),
                        callOffset + 4L,
                    ),
                )
            }
            addAll(extraTrailingEvents)
        }
        val calls = buildList {
            if (includeVerifiedPrefix) {
                add(
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
                )
            }
            val callOffset = if (includeVerifiedPrefix) 4L else 0L
            add(
                AgentToolCallRecord(
                    id = failedCall.id,
                    runId = "run-1",
                    toolName = failedCall.name,
                    risk = failedCall.risk,
                    arguments = failedCall.arguments,
                    proposedEventId = "event-${callOffset + 1L}",
                    validatedEventId = "event-${callOffset + 2L}",
                    createdAt = callOffset + 1L,
                    validatedAt = callOffset + 2L,
                ),
            )
        }
        val results = buildList {
            if (includeVerifiedPrefix) {
                add(
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
                        replaySafety = ToolReplaySafety.RESTART_REQUIRED,
                        executionReceipt = null,
                        createdAt = 3L,
                        verifiedAt = 4L,
                    ),
                )
            }
            val callOffset = if (includeVerifiedPrefix) 4L else 0L
            add(
                AgentToolResultRecord(
                    toolCallId = failedCall.id,
                    runId = "run-1",
                    eventId = "event-${callOffset + 3L}",
                    toolName = failedCall.name,
                    content = failedResult.content,
                    success = false,
                    errorMessage = failedResult.content,
                    durationMs = failedResult.durationMs,
                    executorVerified = false,
                    verificationStatus = null,
                    verifiedEventId = null,
                    memoryIdsUsed = emptyList(),
                    replaySafety = ToolReplaySafety.RESTART_REQUIRED,
                    executionReceipt = failedReceipt,
                    createdAt = callOffset + 3L,
                    verifiedAt = null,
                ),
            )
        }
        val steps = if (includeVerifiedPrefix) {
            listOf(
                step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.COMPLETED, sequence = 1),
                step(AgentStepTypes.TOOL_VERIFY, AgentStepStatus.COMPLETED, sequence = 2),
                step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.RUNNING, sequence = 3),
            )
        } else {
            listOf(step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.RUNNING))
        }
        val stepEvents = buildList {
            steps.forEach { step ->
                add(stepCreatedEvent(step))
                if (step.status == AgentStepStatus.COMPLETED) {
                    add(stepCompletedEvent(step))
                }
            }
        }
        return PersistedFailureFixture(
            call = failedCall,
            detail = detail(
                status = AgentRunStatus.EXECUTING,
                steps = steps,
                approvals = emptyList(),
                events = stepEvents + events,
                toolLedger = if (includeLedger) {
                    AgentToolLedgerRecord(calls = calls, results = results)
                } else {
                    AgentToolLedgerRecord()
                },
            ),
        )
    }

    private data class ControlledReplayFixture(
        val definition: ToolDefinition,
        val profile: AgentProfileSnapshot,
        val detail: AgentRunDetailRecord,
    )

    private fun controlledReplayDefinition() = ToolDefinition(
        name = "notes.create",
        description = "创建笔记",
        risk = ToolRisk.REQUIRES_APPROVAL,
        inputSchema = listOf(
            ToolInputField("title", "标题", required = true),
            ToolInputField("content", "正文", required = true),
        ),
        replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
        notCommittedReplayPolicy = ToolNotCommittedReplayPolicy.CONTROLLED_SAME_CALL,
    )

    private fun settledControlledReplayDetail(
        fixture: ControlledReplayFixture,
        fromStatus: AgentRunStatus = AgentRunStatus.EXECUTING,
        toStatus: AgentRunStatus = AgentRunStatus.CANCELLED,
        extraTrailingEvents: List<RunEventRecord> = emptyList(),
    ): AgentRunDetailRecord {
        val settledWithoutFingerprint = fixture.detail.copy(
            snapshot = fixture.detail.snapshot.copy(
                run = fixture.detail.snapshot.run.copy(
                    status = AgentRunStatus.CANCELLED,
                    completedAt = 6L,
                ),
                events = fixture.detail.snapshot.events + event(
                    "run.recovered",
                    RunEventMetadata.Recovery(
                        fromStatus = fromStatus,
                        toStatus = toStatus,
                        reason = "应用重启后终止上次未完成 Agent 任务",
                        retryEvidenceCode = AgentTaskRetryEvidenceCode.NOT_COMMITTED,
                        resumeKind = AgentRunResumeKind.RESTART_REQUIRED,
                        restartDisposition = AgentRunRestartDisposition(
                            code = AgentRunRestartDispositionCode.NOT_COMMITTED_REPLAY_ELIGIBLE,
                            reason = "尚未进入工具执行边界",
                            evidenceBoundary = "原工具调用已通过受控同调用资格核验",
                            suggestedAction = "确认后创建关联新 Run",
                        ),
                    ),
                    5L,
                ) + RunEventRecord(
                    id = "event-6",
                    runId = "run-1",
                    type = "run.status",
                    message = AgentRunStatus.CANCELLED.name,
                    createdAt = 6L,
                    metadata = null,
                ) + extraTrailingEvents,
            ),
        )
        val fingerprint = AgentTaskRetryEvidenceFingerprint.calculate(settledWithoutFingerprint)
        return settledWithoutFingerprint.copy(
            snapshot = settledWithoutFingerprint.snapshot.copy(
                events = settledWithoutFingerprint.snapshot.events.map { event ->
                    val recovery = event.metadata as? RunEventMetadata.Recovery
                    if (recovery == null) event else event.copy(
                        metadata = recovery.copy(retryEvidenceFingerprint = fingerprint),
                    )
                },
            ),
        )
    }

    private fun assertIneligibleReason(
        assessment: AgentNotCommittedReplayQualificationAssessment,
        expectedReason: String,
    ) {
        assertTrue(assessment is AgentNotCommittedReplayQualificationAssessment.Ineligible)
        assertTrue(
            (assessment as AgentNotCommittedReplayQualificationAssessment.Ineligible)
                .reason.contains(expectedReason),
        )
    }

    private fun controlledReplayFixture(
        definition: ToolDefinition = controlledReplayDefinition(),
        proposedRecoveryContract: ToolDefinitionRecoverySnapshot? = ToolDefinitionRecoveryContract.snapshot(definition),
        validatedRecoveryContract: ToolDefinitionRecoverySnapshot? = ToolDefinitionRecoveryContract.snapshot(definition),
        decidedDefinitionFingerprint: String? = ToolDefinitionRecoveryContract.snapshot(definition).definitionFingerprint,
        decidedArguments: Map<String, String> = mapOf("title" to "资格", "content" to "尚未执行"),
        requestedStatus: ApprovalRequestStatus = ApprovalRequestStatus.PENDING,
        reverseApprovalEventOrder: Boolean = false,
        includeExecutionStep: Boolean = false,
    ): ControlledReplayFixture {
        val recoveryContract = ToolDefinitionRecoveryContract.snapshot(definition)
        val call = ToolCall(
            id = "tool-call-safe-replay",
            name = definition.name,
            arguments = mapOf("title" to "资格", "content" to "尚未执行"),
            risk = definition.risk,
        )
        val profile = AgentProfileSnapshot(
            id = "agent-notes",
            name = "笔记 Agent",
            avatar = "记",
            providerId = "provider-1",
            model = "gpt-test",
            apiMode = com.longdev.xiaoling.model.ApiMode.RESPONSES,
            systemPrompt = "",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf(definition.name),
            allowedSkillIds = listOf("local-notes"),
            memoryEnabled = false,
        )
        val approval = ApprovalRequestRecord(
            id = "approval-safe-replay",
            runId = "run-1",
            conversationId = "conversation-1",
            toolCallId = call.id,
            toolName = call.name,
            toolDescription = definition.description,
            risk = call.risk,
            arguments = call.arguments,
            status = ApprovalRequestStatus.APPROVED,
            decisionReason = "用户已批准",
            createdAt = 3L,
            expiresAt = APPROVAL_REQUEST_NO_EXPIRY_AT,
            decidedAt = 4L,
        )
        val steps = buildList {
            add(step("approval", AgentStepStatus.COMPLETED))
            if (includeExecutionStep) {
                add(step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.RUNNING, sequence = 2))
            }
        }
        val requestedEvent = event(
            "approval.requested",
            RunEventMetadata.ApprovalRequest(
                id = approval.id,
                toolName = approval.toolName,
                risk = approval.risk,
                arguments = approval.arguments,
                status = requestedStatus,
                expiresAt = approval.expiresAt,
                reason = null,
                definitionFingerprint = recoveryContract.definitionFingerprint,
            ),
            3L,
        )
        val decidedEvent = event(
            "approval.request_decided",
            RunEventMetadata.ApprovalRequest(
                id = approval.id,
                toolName = approval.toolName,
                risk = approval.risk,
                arguments = decidedArguments,
                status = ApprovalRequestStatus.APPROVED,
                expiresAt = approval.expiresAt,
                reason = approval.decisionReason,
                definitionFingerprint = decidedDefinitionFingerprint,
            ),
            4L,
        )
        return ControlledReplayFixture(
            definition = definition,
            profile = profile,
            detail = detail(
                status = AgentRunStatus.EXECUTING,
                steps = steps,
                approvals = listOf(approval),
                events = listOf(
                    event(
                        AgentEventTypes.PROFILE_SELECTED,
                        RunEventMetadata.AgentProfileSelection(profile),
                        0L,
                    ),
                    event(
                        "tool.call.proposed",
                        RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments, proposedRecoveryContract),
                        1L,
                    ),
                    event(
                        "tool.call.validated",
                        RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments, validatedRecoveryContract),
                        2L,
                    ),
                    *(if (reverseApprovalEventOrder) {
                        arrayOf(decidedEvent, requestedEvent)
                    } else {
                        arrayOf(requestedEvent, decidedEvent)
                    }),
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
                ),
            ),
        )
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

    private fun fullyVerifiedDetail(verificationStepStatus: AgentStepStatus): AgentRunDetailRecord {
        val call = ToolCall(
            id = "tool-call-verified",
            name = "app.current_time",
            arguments = emptyMap(),
            risk = ToolRisk.SAFE,
        )
        return detail(
            status = AgentRunStatus.VERIFYING,
            steps = listOf(
                step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.COMPLETED, 1),
                step(AgentStepTypes.TOOL_VERIFY, verificationStepStatus, 2),
            ),
            approvals = emptyList(),
            events = listOf(
                event("tool.call.proposed", RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments), 1L),
                event("tool.call.validated", RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments), 2L),
                event(
                    "tool.result",
                    RunEventMetadata.ToolResult(
                        toolName = call.name,
                        content = "当前时间：2026-07-21 08:30:00",
                        durationMs = 5L,
                        success = true,
                        verified = true,
                        toolCallId = call.id,
                    ),
                    3L,
                ),
                event(
                    "tool.verify",
                    RunEventMetadata.ToolVerification(call.name, ToolVerificationStatus.PASSED, call.id),
                    4L,
                ),
            ),
        )
    }

    private fun validPendingApprovalDetail(
        extraEvents: List<RunEventRecord> = emptyList(),
    ): AgentRunDetailRecord {
        val call = ToolCall(
            id = "tool-call-1",
            name = "notes.create",
            arguments = mapOf("title" to "待确认"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        return detail(
            status = AgentRunStatus.WAITING_APPROVAL,
            steps = listOf(step("approval", AgentStepStatus.RUNNING)),
            events = extraEvents + listOf(
                event("tool.call.proposed", RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments), 1L),
                event("tool.call.validated", RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments), 2L),
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
            ),
        )
    }

    private fun pendingApprovalAfterVerifiedPrefix(
        firstCall: ToolCall,
        pendingCall: ToolCall,
    ): AgentRunDetailRecord {
        val firstResult = RunEventMetadata.ToolResult(
            toolName = firstCall.name,
            content = "第一步已完成",
            durationMs = 5L,
            success = true,
            verified = true,
            toolCallId = firstCall.id,
        )
        val events = listOf(
            event("tool.call.proposed", RunEventMetadata.ToolCall(firstCall.id, firstCall.name, firstCall.risk, firstCall.arguments), 1L),
            event("tool.call.validated", RunEventMetadata.ToolCall(firstCall.id, firstCall.name, firstCall.risk, firstCall.arguments), 2L),
            event("tool.result", firstResult, 3L),
            event(
                "tool.verify",
                RunEventMetadata.ToolVerification(firstCall.name, ToolVerificationStatus.PASSED, firstCall.id),
                4L,
            ),
            event("tool.call.proposed", RunEventMetadata.ToolCall(pendingCall.id, pendingCall.name, pendingCall.risk, pendingCall.arguments), 5L),
            event("tool.call.validated", RunEventMetadata.ToolCall(pendingCall.id, pendingCall.name, pendingCall.risk, pendingCall.arguments), 6L),
        )
        return detail(
            status = AgentRunStatus.WAITING_APPROVAL,
            steps = listOf(
                step(AgentStepTypes.TOOL_EXECUTE, AgentStepStatus.COMPLETED, 1),
                step(AgentStepTypes.TOOL_VERIFY, AgentStepStatus.COMPLETED, 2),
                step("approval", AgentStepStatus.RUNNING, 3),
            ),
            approvals = listOf(
                ApprovalRequestRecord(
                    id = "approval-second",
                    runId = "run-1",
                    conversationId = "conversation-1",
                    toolCallId = pendingCall.id,
                    toolName = pendingCall.name,
                    toolDescription = "第二步",
                    risk = pendingCall.risk,
                    arguments = pendingCall.arguments,
                    status = ApprovalRequestStatus.PENDING,
                    decisionReason = null,
                    createdAt = 6L,
                    expiresAt = APPROVAL_REQUEST_NO_EXPIRY_AT,
                    decidedAt = null,
                ),
            ),
            events = events,
            toolLedger = AgentToolLedgerRecord(
                calls = listOf(
                    AgentToolCallRecord(firstCall.id, "run-1", firstCall.name, firstCall.risk, firstCall.arguments, "event-1", "event-2", 1L, 2L),
                    AgentToolCallRecord(pendingCall.id, "run-1", pendingCall.name, pendingCall.risk, pendingCall.arguments, "event-5", "event-6", 5L, 6L),
                ),
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
                        replaySafety = ToolReplaySafety.RESTART_REQUIRED,
                        executionReceipt = null,
                        createdAt = 3L,
                        verifiedAt = 4L,
                    ),
                ),
            ),
        )
    }

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

    private fun stepCreatedEvent(step: AgentStepRecord) = RunEventRecord(
        id = "step-created-${step.sequence}",
        runId = step.runId,
        type = AgentEventTypes.STEP_CREATED,
        message = AgentEventTypes.STEP_CREATED,
        createdAt = step.createdAt,
        metadata = RunEventMetadata.StepCreated(
            stepId = step.id,
            sequence = step.sequence,
            stepType = step.type,
            status = AgentStepStatus.RUNNING,
        ),
    )

    private fun stepCompletedEvent(step: AgentStepRecord) = RunEventRecord(
        id = "step-status-${step.sequence}",
        runId = step.runId,
        type = AgentEventTypes.STEP_STATUS,
        message = AgentEventTypes.STEP_STATUS,
        createdAt = step.createdAt + 1L,
        metadata = RunEventMetadata.StepStatus(
            stepId = step.id,
            sequence = step.sequence,
            fromStatus = AgentStepStatus.RUNNING,
            toStatus = AgentStepStatus.COMPLETED,
        ),
    )
}
