package com.longdev.xiaoling.agent

import com.longdev.xiaoling.device.DeviceSnapshotCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * long: 直接 Agent 的目标级展示只认已经通过 Executor 验证的设备动作，不能把模型总结或历史 snapshot 当成新的完成证明。
 */
data class DirectAgentGoalVerificationDecision(
    val runId: String,
    val status: DirectAgentGoalVerificationStatus,
    val verifiedToolNames: List<String>,
    val failedToolNames: List<String>,
    val latestObservedPackageName: String?,
    val latestObservedAt: Long?,
    val ruleVersion: String = DirectAgentGoalVerificationPolicy.RULE_VERSION,
) {
    val totalActionCount: Int
        get() = verifiedToolNames.size + failedToolNames.size
}

enum class DirectAgentGoalVerificationStatus {
    VERIFIED,
    PARTIAL,
    INCOMPLETE,
}

data class DirectAgentObservationSummary(
    val packageName: String,
    val nodeCount: Int,
    val redactedNodeCount: Int,
    val truncated: Boolean,
    val capturedAt: Long,
)

object DirectAgentGoalVerificationPolicy {
    const val RULE_VERSION = "direct-agent-goal-verification-v1"

    private val ACTION_TOOL_NAMES = setOf(
        "device.open_app",
        "device.tap_ref",
        "device.type_text",
    )

    fun evaluate(context: VerifiedAgentContext): DirectAgentGoalVerificationDecision? {
        if (context.runId.isBlank()) return null
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
        val actions = executions.filter { it.toolName in ACTION_TOOL_NAMES }
        if (actions.isEmpty()) return null
        val verified = actions.filter { it.success && it.verificationStatus == AgentVerificationStatus.VERIFIED }
        val failed = actions.filterNot { it.success && it.verificationStatus == AgentVerificationStatus.VERIFIED }
        // long: 目标卡片只能引用设备动作自己的后置观察；后续普通工具即使返回同形 JSON，也不能改写设备任务的当前事实。
        val latestObservation = actions.asReversed()
            .asSequence()
            .mapNotNull { execution -> observationSummary(execution.rawResult) }
            .firstOrNull()
        val status = when {
            verified.isNotEmpty() && failed.isEmpty() -> DirectAgentGoalVerificationStatus.VERIFIED
            verified.isNotEmpty() -> DirectAgentGoalVerificationStatus.PARTIAL
            else -> DirectAgentGoalVerificationStatus.INCOMPLETE
        }
        return DirectAgentGoalVerificationDecision(
            runId = context.runId,
            status = status,
            verifiedToolNames = verified.map(VerifiedToolExecution::toolName),
            failedToolNames = failed.map(VerifiedToolExecution::toolName),
            latestObservedPackageName = latestObservation?.packageName,
            latestObservedAt = latestObservation?.capturedAt,
        )
    }

    /**
     * long: 动作结果可能包着完整 after_snapshot，也可能是 type_text 的隐私摘要；两种格式都只投影包名和计数，不穿透节点正文。
     */
    internal fun observationSummary(rawResult: String): DirectAgentObservationSummary? {
        if (rawResult.isBlank()) return null
        return runCatching {
            val json = Json.parseToJsonElement(rawResult).jsonObject
            val candidate = if (json.containsKey("snapshot_id")) json else json["after_snapshot"] as? JsonObject
            candidate?.let { snapshot ->
                DeviceSnapshotCodec.decodeSummary(snapshot.toString())?.let { summary ->
                    DirectAgentObservationSummary(
                        packageName = summary.packageName,
                        nodeCount = summary.nodeCount,
                        redactedNodeCount = summary.redactedNodeCount,
                        truncated = summary.truncated,
                        capturedAt = summary.capturedAt,
                    )
                } ?: partialSummary(snapshot)
            }
        }.getOrNull()
    }

    private fun partialSummary(json: JsonObject): DirectAgentObservationSummary? {
        val packageName = json["package"]?.toString()?.trim('"')?.takeIf(String::isNotBlank) ?: return null
        val capturedAt = json["captured_at"]?.toString()?.toLongOrNull() ?: return null
        val nodeCount = json["node_count"]?.toString()?.toIntOrNull() ?: return null
        val redactedNodeCount = json["redacted_node_count"]?.toString()?.toIntOrNull() ?: return null
        val truncated = json["truncated"]?.toString()?.toBooleanStrictOrNull() ?: return null
        return DirectAgentObservationSummary(
            packageName = packageName,
            nodeCount = nodeCount,
            redactedNodeCount = redactedNodeCount,
            truncated = truncated,
            capturedAt = capturedAt,
        )
    }
}
