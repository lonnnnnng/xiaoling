package com.longdev.xiaoling.agent

import com.longdev.xiaoling.knowledge.KnowledgeReference
import org.junit.Assert.assertEquals
import org.junit.Test

class RunEventMetadataCodecTest {
    @Test
    fun executionBudgetRoundTripsWithoutLosingAccumulatedTime() {
        val metadata = RunEventMetadata.ExecutionBudget(
            totalTimeoutMs = 120_000,
            consumedMs = 3_500,
        )

        assertEquals(
            metadata,
            RunEventMetadataCodec.decode(
                AgentEventTypes.EXECUTION_BUDGET_UPDATED,
                RunEventMetadataCodec.encode(metadata),
            ),
        )
    }

    @Test
    fun agentProfileSelectionRoundTripsWithoutDroppingCapabilitySnapshot() {
        val metadata = RunEventMetadata.AgentProfileSelection(
            AgentProfileSnapshot(
                id = "agent-profile-1",
                name = "日常助理",
                avatar = "日",
                providerId = "provider-1",
                model = "gpt-test",
                apiMode = com.longdev.xiaoling.model.ApiMode.RESPONSES,
                systemPrompt = "优先给出短答案",
                contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
                allowedToolNames = listOf("app.current_time", "notes.search"),
                allowedSkillIds = listOf("device-time"),
                memoryEnabled = false,
            ),
        )

        assertEquals(
            metadata,
            RunEventMetadataCodec.decode(
                AgentEventTypes.PROFILE_SELECTED,
                RunEventMetadataCodec.encode(metadata),
            ),
        )
    }

    @Test
    fun recoveryFailureGuidanceRoundTrips() {
        val metadata = RunEventMetadata.RecoveryFailure(
            toolName = "memory.remember",
            code = "MEMORY_EXPIRED",
            reason = "原长期记忆已过期",
            suggestedAction = "请先更新过期时间，再创建新 Run 重试。",
        )

        val restored = RunEventMetadataCodec.decode(
            AgentEventTypes.RECOVERY_FAILED,
            RunEventMetadataCodec.encode(metadata),
        )

        assertEquals(metadata, restored)
    }

    @Test
    fun recoveryEvidenceCodeRoundTripsAndLegacyEventRemainsReadable() {
        val metadata = RunEventMetadata.Recovery(
            fromStatus = AgentRunStatus.EXECUTING,
            toStatus = AgentRunStatus.CANCELLED,
            reason = "应用重启后终止上次未完成 Agent 任务",
            retryEvidenceCode = AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN,
            retryEvidenceFingerprint = "f".repeat(64),
            resumeKind = AgentRunResumeKind.RESTART_REQUIRED,
            restartDisposition = AgentRunRestartDisposition(
                code = AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
                reason = "工具账本与事件不一致",
                evidenceBoundary = "不能证明历史副作用边界",
                suggestedAction = "保留旧 Run 并创建关联新 Run",
            ),
        )

        assertEquals(
            metadata,
            RunEventMetadataCodec.decode("run.recovered", RunEventMetadataCodec.encode(metadata)),
        )
        assertEquals(
            null,
            (RunEventMetadataCodec.decode(
                "run.recovered",
                "{\"fromStatus\":\"THINKING\",\"toStatus\":\"CANCELLED\",\"reason\":\"legacy\"}",
            ) as RunEventMetadata.Recovery).retryEvidenceCode,
        )
    }

    @Test
    fun unknownRecoveryEvidenceCodeFailsClosedWithoutBreakingLegacyMissingField() {
        val unknown = RunEventMetadataCodec.decode(
            "run.recovered",
            "{\"fromStatus\":\"THINKING\",\"toStatus\":\"CANCELLED\",\"reason\":\"future\",\"retryEvidenceCode\":\"FUTURE_CODE\"}",
        ) as RunEventMetadata.Recovery
        assertEquals(AgentTaskRetryEvidenceCode.EVIDENCE_INCOMPLETE, unknown.retryEvidenceCode)

        val legacy = RunEventMetadataCodec.decode(
            "run.recovered",
            "{\"fromStatus\":\"THINKING\",\"toStatus\":\"CANCELLED\",\"reason\":\"legacy\"}",
        ) as RunEventMetadata.Recovery
        assertEquals(null, legacy.retryEvidenceCode)
        assertEquals(null, legacy.retryEvidenceFingerprint)
        assertEquals(null, legacy.resumeKind)
        assertEquals(null, legacy.restartDisposition)
    }

    @Test
    fun unknownResumeDispositionFailsClosedWithoutBreakingEventDecoding() {
        val unknown = RunEventMetadataCodec.decode(
            "run.recovered",
            "{\"fromStatus\":\"EXECUTING\",\"toStatus\":\"CANCELLED\",\"reason\":\"future\",\"resumeKind\":\"FUTURE_KIND\",\"restartDispositionCode\":\"FUTURE_CODE\",\"policyReason\":\"future reason\",\"evidenceBoundary\":\"future boundary\",\"suggestedAction\":\"future action\"}",
        ) as RunEventMetadata.Recovery

        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, unknown.resumeKind)
        assertEquals(
            AgentRunRestartDispositionCode.RECOVERY_EVIDENCE_INVALID,
            unknown.restartDisposition?.code,
        )
    }

    @Test
    fun recoverySummaryReasonRoundTrips() {
        val metadata = RunEventMetadata.Reason("验证阶段恢复不恢复旧模型协程")

        val restored = RunEventMetadataCodec.decode(
            AgentEventTypes.RECOVERY_SUMMARY,
            RunEventMetadataCodec.encode(metadata),
        )

        assertEquals(metadata, restored)
    }

    @Test
    fun llmRequestTelemetryRoundTripsWithoutInventingMissingUsage() {
        val metadata = RunEventMetadata.LlmRequest(
            phase = AgentLlmPhase.PLAN,
            model = "gpt-test",
            latencyMs = 1_250L,
            firstByteLatencyMs = 320L,
            promptBytes = 4_096,
            inputTokens = 120L,
            outputTokens = 30L,
            totalTokens = 150L,
        )

        val decoded = RunEventMetadataCodec.decode(
            type = "llm.request.completed",
            raw = RunEventMetadataCodec.encode(metadata),
        )

        assertEquals(metadata, decoded)
    }

    @Test
    fun toolResultMemoryIdsRoundTrip() {
        val metadata = RunEventMetadata.ToolResult(
            toolName = "memory.search",
            content = "长期记忆：...",
            durationMs = 12,
            success = true,
            verified = null,
            memoryIdsUsed = listOf("memory-2", "memory-1"),
        )

        assertEquals(
            metadata,
            RunEventMetadataCodec.decode(
                type = "tool.result",
                raw = RunEventMetadataCodec.encode(metadata),
            ),
        )
    }

    @Test
    fun toolResultKnowledgeReferencesRoundTripWithoutFlatteningIdentity() {
        val metadata = RunEventMetadata.ToolResult(
            toolName = "knowledge.search",
            content = "本地知识检索结果：...",
            durationMs = 18,
            success = true,
            verified = null,
            knowledgeReferences = listOf(
                KnowledgeReference(
                    retrievalId = "knowledge-retrieval-1",
                    documentId = "document-1",
                    documentName = "路线图.md",
                    documentRevision = 4,
                    chunkId = "chunk-1-r4-2",
                    chunkSequence = 2,
                    startOffset = 120,
                    endOffset = 360,
                ),
            ),
        )

        assertEquals(
            metadata,
            RunEventMetadataCodec.decode(
                type = "tool.result",
                raw = RunEventMetadataCodec.encode(metadata),
            ),
        )
    }

    @Test
    fun toolExecutionReceiptRoundTripsWithToolCallIdentity() {
        val metadata = RunEventMetadata.ToolResult(
            toolName = "notes.create",
            content = "已创建笔记",
            durationMs = 12,
            success = true,
            verified = true,
            toolCallId = "tool-call-1",
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            executionReceipt = ToolExecutionReceipt(
                toolCallId = "tool-call-1",
                operationId = "note-1",
                idempotencyKey = "run-1:step-1",
                status = ToolExecutionReceiptStatus.COMMITTED,
            ),
        )

        assertEquals(
            metadata,
            RunEventMetadataCodec.decode(
                type = "tool.result",
                raw = RunEventMetadataCodec.encode(metadata),
            ),
        )
    }

    @Test
    fun toolVerificationRoundTripsWithOptionalToolCallIdentity() {
        val metadata = RunEventMetadata.ToolVerification(
            toolName = "notes.create",
            status = ToolVerificationStatus.PASSED,
            toolCallId = "tool-call-verify-1",
        )

        assertEquals(
            metadata,
            RunEventMetadataCodec.decode("tool.verify", RunEventMetadataCodec.encode(metadata)),
        )
        assertEquals(
            RunEventMetadata.ToolVerification("notes.create", ToolVerificationStatus.PASSED),
            RunEventMetadataCodec.decode(
                "tool.verify",
                """{"toolName":"notes.create","status":"PASSED"}""",
            ),
        )
    }

    @Test
    fun legacyToolResultWithoutMemoryIdsRemainsReadable() {
        val metadata = RunEventMetadataCodec.decode(
            type = "tool.result",
            raw = """{"toolName":"memory.search","content":"旧结果","durationMs":3,"success":true,"verified":null}""",
        ) as RunEventMetadata.ToolResult

        assertEquals(emptyList<String>(), metadata.memoryIdsUsed)
        assertEquals(emptyList<KnowledgeReference>(), metadata.knowledgeReferences)
        assertEquals(null, metadata.toolCallId)
        assertEquals(null, metadata.executionReceipt)
        assertEquals(ToolReplaySafety.RESTART_REQUIRED, metadata.replaySafety)
    }

    @Test
    fun legacyStringifiedMemoryIdsRemainReadable() {
        val metadata = RunEventMetadataCodec.decode(
            type = "tool.result",
            raw = """{"toolName":"memory.search","content":"旧结果","durationMs":3,"success":true,"verified":true,"memoryIdsUsed":"[\"memory-1\",\"memory-2\"]"}""",
        ) as RunEventMetadata.ToolResult

        assertEquals(listOf("memory-1", "memory-2"), metadata.memoryIdsUsed)
    }

    @Test
    fun skillSelectionReasonRemainsReadableAfterRoomRoundTrip() {
        val metadata = RunEventMetadata.Reason("daily-review@2")

        assertEquals(
            metadata,
            RunEventMetadataCodec.decode(
                type = "skill.selected",
                raw = RunEventMetadataCodec.encode(metadata),
            ),
        )
    }
}
