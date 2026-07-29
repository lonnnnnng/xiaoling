package com.longdev.xiaoling.automation

import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.agent.VerifiedToolExecution
import com.longdev.xiaoling.device.DeviceSnapshotCodec

enum class WorkflowDeviceObservationDecisionStatus {
    REVIEWABLE,
    LIMITED,
}

enum class WorkflowDeviceObservationInsufficientReason {
    RUN_ID_MISMATCH,
    EXECUTION_FAILED,
    VERIFICATION_MISSING,
    MALFORMED_SNAPSHOT,
    SOURCE_MISSING,
    STORED_DECISION_MISMATCH,
}

data class WorkflowDeviceObservationEvidenceInput(
    val runId: String,
    val toolName: String,
    val content: String,
    val success: Boolean,
    val verified: Boolean,
    val durationMs: Long? = null,
)

data class WorkflowDeviceObservationDecision(
    val status: WorkflowDeviceObservationDecisionStatus,
    val packageName: String,
    val nodeCount: Int,
    val redactedNodeCount: Int,
    val truncated: Boolean,
    val capturedAt: Long,
    val ruleVersion: String = WorkflowDeviceObservationDecisionPolicy.RULE_VERSION,
)

sealed interface WorkflowDeviceObservationResolution {
    data object NotApplicable : WorkflowDeviceObservationResolution

    data class Decided(
        val decisions: List<WorkflowDeviceObservationDecision>,
    ) : WorkflowDeviceObservationResolution

    data class InsufficientEvidence(
        val reason: WorkflowDeviceObservationInsufficientReason,
        val message: String,
    ) : WorkflowDeviceObservationResolution
}

object WorkflowDeviceObservationDecisionPolicy {
    const val RULE_VERSION = "workflow-device-observation-v1"

    fun evaluate(
        expectedAgentRunId: String,
        results: List<WorkflowDeviceObservationEvidenceInput>,
    ): WorkflowDeviceObservationResolution {
        val deviceResults = results.filter { it.toolName == DEVICE_SNAPSHOT_TOOL_NAME }
        if (deviceResults.isEmpty()) return WorkflowDeviceObservationResolution.NotApplicable

        val decisions = buildList {
            deviceResults.forEach { result ->
                if (result.runId != expectedAgentRunId) {
                    return insufficient(
                        WorkflowDeviceObservationInsufficientReason.RUN_ID_MISMATCH,
                        "设备观察证据与当前 Agent Run 不匹配",
                    )
                }
                if (!result.success) {
                    return insufficient(
                        WorkflowDeviceObservationInsufficientReason.EXECUTION_FAILED,
                        "device.snapshot 没有成功执行",
                    )
                }
                if (!result.verified) {
                    return insufficient(
                        WorkflowDeviceObservationInsufficientReason.VERIFICATION_MISSING,
                        "device.snapshot 缺少通过验证的 Tool Ledger 结果",
                    )
                }
                val summary = DeviceSnapshotCodec.decodeSummary(result.content)
                    ?: return insufficient(
                        WorkflowDeviceObservationInsufficientReason.MALFORMED_SNAPSHOT,
                        "device.snapshot 结构不完整，不能形成本地判断",
                    )
                add(
                    WorkflowDeviceObservationDecision(
                        status = if (summary.truncated || summary.redactedNodeCount > 0) {
                            WorkflowDeviceObservationDecisionStatus.LIMITED
                        } else {
                            WorkflowDeviceObservationDecisionStatus.REVIEWABLE
                        },
                        packageName = summary.packageName,
                        nodeCount = summary.nodeCount,
                        redactedNodeCount = summary.redactedNodeCount,
                        truncated = summary.truncated,
                        capturedAt = summary.capturedAt,
                    ),
                )
            }
        }
        return WorkflowDeviceObservationResolution.Decided(decisions)
    }

    fun evaluate(context: VerifiedAgentContext): WorkflowDeviceObservationResolution {
        val executions = context.toolExecutions.ifEmpty {
            listOf(
                VerifiedToolExecution(
                    toolName = context.toolName,
                    arguments = context.arguments,
                    success = context.success,
                    verificationStatus = context.verificationStatus,
                    rawResult = context.rawResult,
                    memoryIdsUsed = context.memoryIdsUsed,
                    knowledgeReferences = context.knowledgeReferences,
                ),
            )
        }
        return evaluate(
            expectedAgentRunId = context.runId,
            results = executions.map { execution ->
                WorkflowDeviceObservationEvidenceInput(
                    runId = context.runId,
                    toolName = execution.toolName,
                    content = execution.rawResult,
                    success = execution.success,
                    verified = execution.verificationStatus == AgentVerificationStatus.VERIFIED,
                )
            },
        )
    }

    fun requireDecisions(
        resolution: WorkflowDeviceObservationResolution,
    ): List<WorkflowDeviceObservationDecision> = when (resolution) {
        WorkflowDeviceObservationResolution.NotApplicable -> emptyList()
        is WorkflowDeviceObservationResolution.Decided -> resolution.decisions
        is WorkflowDeviceObservationResolution.InsufficientEvidence -> {
            throw WorkflowDeviceObservationEvidenceException(resolution.reason, resolution.message)
        }
    }

    fun renderForPrompt(decisions: List<WorkflowDeviceObservationDecision>): String {
        require(decisions.isNotEmpty()) { "设备观察判定不能为空" }
        return decisions.mapIndexed { index, decision ->
            val label = when (decision.status) {
                WorkflowDeviceObservationDecisionStatus.REVIEWABLE -> "可复核"
                WorkflowDeviceObservationDecisionStatus.LIMITED -> "有限可复核"
            }
            buildString {
                append("本地设备观察判定 ${index + 1}（${decision.ruleVersion}）\n")
                append("结论：$label\n")
                append("已确认：采集时应用包名 ${decision.packageName}；返回节点 ${decision.nodeCount}；")
                append("脱敏节点 ${decision.redactedNodeCount}；")
                append(if (decision.truncated) "快照已截断" else "快照未截断")
                append("；采集时间 ${decision.capturedAt}\n")
                // long: 下游模型只能使用白名单摘要；本地判定主动排除节点正文、ref 和目标完成语义，避免把观察权限升级成动作或业务事实授权。
                append("限制：仅确认采集时的应用包名与快照摘要，不确认节点正文、用户目标完成或任何设备动作授权。")
            }
        }.joinToString("\n\n")
    }

    fun containsPotentialRawSnapshot(value: String): Boolean {
        val signatureCount = DEVICE_SNAPSHOT_TEXT_SIGNATURES.count { aliases ->
            aliases.any { alias -> value.containsJsonKey(alias) }
        }
        return value.containsJsonKey("nodes") && signatureCount >= 3
    }

    private fun insufficient(
        reason: WorkflowDeviceObservationInsufficientReason,
        message: String,
    ) = WorkflowDeviceObservationResolution.InsufficientEvidence(reason, message)

    private const val DEVICE_SNAPSHOT_TOOL_NAME = "device.snapshot"
    private val DEVICE_SNAPSHOT_TEXT_SIGNATURES = listOf(
        setOf("snapshot_id", "snapshotId"),
        setOf("package", "packageName"),
        setOf("window_id", "windowId"),
        setOf("window_generation", "windowGeneration"),
        setOf("captured_at", "capturedAt"),
        setOf("expires_at", "expiresAt"),
        setOf("redacted_node_count", "redactedNodeCount"),
        setOf("truncated"),
    )

    private fun String.containsJsonKey(key: String): Boolean {
        return contains("\"$key\"") || contains("\\\"$key\\\"")
    }
}

class WorkflowDeviceObservationEvidenceException(
    val reason: WorkflowDeviceObservationInsufficientReason,
    message: String,
) : IllegalStateException("设备观察证据不足（${reason.name}）：$message")
