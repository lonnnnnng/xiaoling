package com.longdev.xiaoling.automation

enum class WorkflowDeviceActionDecisionStatus {
    VERIFIED,
}

enum class WorkflowDeviceActionInsufficientReason {
    RUN_ID_MISMATCH,
    EXECUTION_FAILED,
    EXECUTOR_VERIFICATION_MISSING,
    VERIFICATION_MISSING,
    MALFORMED_RESULT,
    SOURCE_MISSING,
    STORED_DECISION_MISMATCH,
}

data class WorkflowDeviceActionEvidenceInput(
    val runId: String,
    val toolName: String,
    val content: String,
    val success: Boolean,
    val executorVerified: Boolean?,
    val verified: Boolean,
)

data class WorkflowDeviceActionDecision(
    val status: WorkflowDeviceActionDecisionStatus,
    val action: String,
    val beforePackageName: String,
    val afterPackageName: String,
    val afterNodeCount: Int,
    val afterRedactedNodeCount: Int,
    val afterTruncated: Boolean,
    val afterObservedAt: Long,
    val resultRuleVersion: String = WorkflowDeviceActionResultCodec.RULE_VERSION,
    val safetyRuleVersion: String = WorkflowDeviceActionSafetyPolicy.RULE_VERSION,
    val ruleVersion: String = WorkflowDeviceActionDecisionPolicy.RULE_VERSION,
)

sealed interface WorkflowDeviceActionResolution {
    data object NotApplicable : WorkflowDeviceActionResolution

    data class Decided(
        val decisions: List<WorkflowDeviceActionDecision>,
    ) : WorkflowDeviceActionResolution

    data class InsufficientEvidence(
        val reason: WorkflowDeviceActionInsufficientReason,
        val message: String,
    ) : WorkflowDeviceActionResolution
}

object WorkflowDeviceActionDecisionPolicy {
    const val RULE_VERSION = "workflow-device-action-decision-v1"

    fun evaluate(
        expectedAgentRunId: String,
        results: List<WorkflowDeviceActionEvidenceInput>,
    ): WorkflowDeviceActionResolution {
        val actionResults = results.filter { it.toolName == DEVICE_TAP_REF_TOOL_NAME }
        if (actionResults.isEmpty()) return WorkflowDeviceActionResolution.NotApplicable

        val decisions = buildList {
            actionResults.forEach { result ->
                if (result.runId != expectedAgentRunId) {
                    return insufficient(
                        WorkflowDeviceActionInsufficientReason.RUN_ID_MISMATCH,
                        "设备动作证据与当前 Agent Run 不匹配",
                    )
                }
                if (!result.success) {
                    return insufficient(
                        WorkflowDeviceActionInsufficientReason.EXECUTION_FAILED,
                        "device.tap_ref 没有成功执行",
                    )
                }
                if (result.executorVerified != true) {
                    return insufficient(
                        WorkflowDeviceActionInsufficientReason.EXECUTOR_VERIFICATION_MISSING,
                        "device.tap_ref 缺少 Executor 回读验证事实",
                    )
                }
                if (!result.verified) {
                    return insufficient(
                        WorkflowDeviceActionInsufficientReason.VERIFICATION_MISSING,
                        "device.tap_ref 缺少通过验证的 Tool Ledger 结果",
                    )
                }
                val evidence = WorkflowDeviceActionResultCodec.decode(result.content)
                    ?.takeIf { it.verified }
                    ?: return insufficient(
                        WorkflowDeviceActionInsufficientReason.MALFORMED_RESULT,
                        "device.tap_ref 结果不是完整的白名单动作证据",
                    )
                add(
                    WorkflowDeviceActionDecision(
                        status = WorkflowDeviceActionDecisionStatus.VERIFIED,
                        action = evidence.action,
                        beforePackageName = evidence.beforePackageName,
                        afterPackageName = evidence.afterPackageName,
                        afterNodeCount = evidence.afterNodeCount,
                        afterRedactedNodeCount = evidence.afterRedactedNodeCount,
                        afterTruncated = evidence.afterTruncated,
                        afterObservedAt = evidence.afterObservedAt,
                        resultRuleVersion = evidence.ruleVersion,
                        safetyRuleVersion = evidence.safetyRuleVersion,
                    ),
                )
            }
        }
        return WorkflowDeviceActionResolution.Decided(decisions)
    }

    fun requireDecisions(resolution: WorkflowDeviceActionResolution): List<WorkflowDeviceActionDecision> =
        when (resolution) {
            WorkflowDeviceActionResolution.NotApplicable -> emptyList()
            is WorkflowDeviceActionResolution.Decided -> resolution.decisions
            is WorkflowDeviceActionResolution.InsufficientEvidence -> {
                throw WorkflowDeviceActionEvidenceException(resolution.reason, resolution.message)
            }
        }

    fun renderForPrompt(decisions: List<WorkflowDeviceActionDecision>): String {
        require(decisions.isNotEmpty()) { "设备动作判定不能为空" }
        return decisions.mapIndexed { index, decision ->
            buildString {
                append("本地设备动作判定 ${index + 1}（${decision.ruleVersion}）\n")
                append("结论：已执行并验证 ${decision.action}\n")
                append("已确认：动作前应用包名 ${decision.beforePackageName}；")
                append("动作后应用包名 ${decision.afterPackageName}；")
                append("后置节点 ${decision.afterNodeCount}；")
                append("脱敏节点 ${decision.afterRedactedNodeCount}；")
                append(if (decision.afterTruncated) "后置观察已截断" else "后置观察未截断")
                append("；后置观察时间 ${decision.afterObservedAt}\n")
                // long: 下游只能知道当前 tap_ref 已通过执行和验证；原节点、ref、snapshot 身份及更高层业务目标都不能从 Tool Ledger 复制进 Workflow。
                append("限制：仅确认当前设备动作和后置观察已验证，不确认用户最终业务目标；节点引用已经失效，后续动作必须重新观察和审批。")
            }
        }.joinToString("\n\n")
    }

    fun containsPotentialRawActionResult(value: String): Boolean {
        val signatureCount = RAW_RESULT_SIGNATURES.count { key ->
            value.contains("\"$key\"") || value.contains("\\\"$key\\\"")
        }
        return value.contains(WorkflowDeviceActionResultCodec.RULE_VERSION) || signatureCount >= 4
    }

    private fun insufficient(
        reason: WorkflowDeviceActionInsufficientReason,
        message: String,
    ) = WorkflowDeviceActionResolution.InsufficientEvidence(reason, message)

    private const val DEVICE_TAP_REF_TOOL_NAME = "device.tap_ref"
    private val RAW_RESULT_SIGNATURES = setOf(
        "action",
        "beforePackageName",
        "afterPackageName",
        "afterNodeCount",
        "afterRedactedNodeCount",
        "afterObservedAt",
        "verified",
    )
}

class WorkflowDeviceActionEvidenceException(
    val reason: WorkflowDeviceActionInsufficientReason,
    message: String,
) : IllegalStateException("设备动作证据不足（${reason.name}）：$message")
