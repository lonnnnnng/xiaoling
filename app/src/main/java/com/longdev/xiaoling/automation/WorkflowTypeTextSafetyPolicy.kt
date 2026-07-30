package com.longdev.xiaoling.automation

import com.longdev.xiaoling.device.DeviceActionPolicy
import java.security.MessageDigest

data class WorkflowTypeTextTargetEvidence(
    val enabled: Boolean,
    val editable: Boolean,
    val redacted: Boolean,
    val supportsTypeText: Boolean,
)

data class WorkflowTypeTextExecutionEvidence(
    val target: WorkflowTypeTextTargetEvidence?,
)

data class WorkflowTypeTextCompletionEvidence(
    val resultAgentRunId: String,
    val resultToolCallId: String,
    val resultToolName: String,
    val actionCompletedAt: Long,
    val observedAt: Long,
    val executorVerified: Boolean,
    val verificationPassed: Boolean,
    val afterObservationVerified: Boolean,
    val readBackText: String?,
)

data class WorkflowTypeTextAuthorization(
    val ruleVersion: String,
    val workflowRunId: String,
    val workflowStepId: String,
    val agentRunId: String,
    val toolCallId: String,
    val toolName: String,
    val textFingerprint: String,
    val textLength: Int,
)

enum class WorkflowTypeTextSafetyFailure {
    ARGUMENTS_INVALID,
    TEXT_REJECTED,
    TARGET_MISSING,
    TARGET_NOT_EDITABLE,
    TARGET_REDACTED,
    TARGET_ACTION_UNAVAILABLE,
    AUTHORIZATION_MISSING,
    AUTHORIZATION_MISMATCH,
    RESULT_MISMATCH,
    POST_VERIFICATION_MISSING,
    READ_BACK_MISMATCH,
}

sealed interface WorkflowTypeTextSafetyDecision {
    data class Allowed(
        val authorization: WorkflowTypeTextAuthorization,
    ) : WorkflowTypeTextSafetyDecision

    data class Denied(
        val reason: WorkflowTypeTextSafetyFailure,
        val message: String,
    ) : WorkflowTypeTextSafetyDecision
}

class WorkflowTypeTextSafetyPolicy(
    private val deviceActionPolicy: DeviceActionPolicy = DeviceActionPolicy(),
) {
    fun assessExecution(
        identity: WorkflowDeviceActionIdentity,
        evidence: WorkflowTypeTextExecutionEvidence,
    ): WorkflowTypeTextSafetyDecision {
        if (!hasValidIdentityAndArguments(identity)) {
            return denied(
                WorkflowTypeTextSafetyFailure.ARGUMENTS_INVALID,
                "Workflow 文本输入必须提供且只提供 snapshot_id、ref 与 text",
            )
        }
        val text = identity.arguments.getValue(TEXT_ARGUMENT_NAME)
        deviceActionPolicy.validateTextInput(text)?.let { reason ->
            return denied(
                WorkflowTypeTextSafetyFailure.TEXT_REJECTED,
                reason,
            )
        }
        val target = evidence.target
            ?: return denied(
                WorkflowTypeTextSafetyFailure.TARGET_MISSING,
                "Workflow 文本输入缺少当前节点的结构化证据",
            )
        if (!target.enabled || !target.editable) {
            return denied(
                WorkflowTypeTextSafetyFailure.TARGET_NOT_EDITABLE,
                "Workflow 文本输入只允许作用于当前启用且可编辑的节点",
            )
        }
        if (target.redacted) {
            return denied(
                WorkflowTypeTextSafetyFailure.TARGET_REDACTED,
                "Workflow 文本输入不得作用于已脱敏节点",
            )
        }
        if (!target.supportsTypeText) {
            return denied(
                WorkflowTypeTextSafetyFailure.TARGET_ACTION_UNAVAILABLE,
                "当前节点未声明 TYPE_TEXT 动作能力",
            )
        }

        // long: 输入授权只保存不可逆指纹和长度；原文、snapshot 与 ref 继续由当前 ToolCall 临时持有，避免授权对象成为敏感数据副本。
        return WorkflowTypeTextSafetyDecision.Allowed(
            WorkflowTypeTextAuthorization(
                ruleVersion = RULE_VERSION,
                workflowRunId = identity.workflowRunId,
                workflowStepId = identity.workflowStepId,
                agentRunId = identity.agentRunId,
                toolCallId = identity.toolCallId,
                toolName = identity.toolName,
                textFingerprint = fingerprint(text),
                textLength = text.length,
            ),
        )
    }

    fun assessCompletion(
        identity: WorkflowDeviceActionIdentity,
        authorization: WorkflowTypeTextAuthorization?,
        evidence: WorkflowTypeTextCompletionEvidence,
    ): WorkflowTypeTextSafetyDecision {
        authorization
            ?: return denied(
                WorkflowTypeTextSafetyFailure.AUTHORIZATION_MISSING,
                "Workflow 文本输入结果缺少执行前专属授权",
            )
        if (!authorizationMatches(identity, authorization)) {
            return denied(
                WorkflowTypeTextSafetyFailure.AUTHORIZATION_MISMATCH,
                "Workflow 文本输入授权与当前 Run、ToolCall 或文本指纹不一致",
            )
        }
        if (
            evidence.resultAgentRunId != identity.agentRunId ||
            evidence.resultToolCallId != identity.toolCallId ||
            evidence.resultToolName != identity.toolName
        ) {
            return denied(
                WorkflowTypeTextSafetyFailure.RESULT_MISMATCH,
                "Workflow 文本输入结果不属于当前 Agent Run 与 ToolCall",
            )
        }
        if (
            !evidence.executorVerified ||
            !evidence.verificationPassed ||
            !evidence.afterObservationVerified ||
            evidence.actionCompletedAt < 0L ||
            evidence.observedAt < evidence.actionCompletedAt ||
            evidence.readBackText == null
        ) {
            return denied(
                WorkflowTypeTextSafetyFailure.POST_VERIFICATION_MISSING,
                "Workflow 文本输入缺少 Executor、typed 验证或动作后观察回读",
            )
        }
        val expectedText = identity.arguments.getValue(TEXT_ARGUMENT_NAME)
        if (evidence.readBackText != expectedText) {
            return denied(
                WorkflowTypeTextSafetyFailure.READ_BACK_MISMATCH,
                "动作后节点文本与本次获批输入不完全一致",
            )
        }
        return WorkflowTypeTextSafetyDecision.Allowed(authorization)
    }

    private fun hasValidIdentityAndArguments(identity: WorkflowDeviceActionIdentity): Boolean {
        return identity.workflowRunId.isNotBlank() &&
            identity.workflowStepId.isNotBlank() &&
            identity.agentRunId.isNotBlank() &&
            identity.toolCallId.isNotBlank() &&
            identity.toolName == DEVICE_TYPE_TEXT_TOOL_NAME &&
            identity.arguments.keys == REQUIRED_ARGUMENT_NAMES &&
            !identity.arguments[SNAPSHOT_ARGUMENT_NAME].isNullOrBlank() &&
            !identity.arguments[REFERENCE_ARGUMENT_NAME].isNullOrBlank()
    }

    private fun authorizationMatches(
        identity: WorkflowDeviceActionIdentity,
        authorization: WorkflowTypeTextAuthorization,
    ): Boolean {
        if (!hasValidIdentityAndArguments(identity)) return false
        val text = identity.arguments.getValue(TEXT_ARGUMENT_NAME)
        return authorization.ruleVersion == RULE_VERSION &&
            authorization.workflowRunId == identity.workflowRunId &&
            authorization.workflowStepId == identity.workflowStepId &&
            authorization.agentRunId == identity.agentRunId &&
            authorization.toolCallId == identity.toolCallId &&
            authorization.toolName == identity.toolName &&
            authorization.textLength == text.length &&
            authorization.textFingerprint == fingerprint(text)
    }

    private fun fingerprint(text: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun denied(
        reason: WorkflowTypeTextSafetyFailure,
        message: String,
    ): WorkflowTypeTextSafetyDecision.Denied {
        return WorkflowTypeTextSafetyDecision.Denied(reason, message)
    }

    companion object {
        const val RULE_VERSION = "workflow-type-text-safety-v1"
        private const val DEVICE_TYPE_TEXT_TOOL_NAME = "device.type_text"
        private const val SNAPSHOT_ARGUMENT_NAME = "snapshot_id"
        private const val REFERENCE_ARGUMENT_NAME = "ref"
        private const val TEXT_ARGUMENT_NAME = "text"
        private val REQUIRED_ARGUMENT_NAMES = setOf(
            SNAPSHOT_ARGUMENT_NAME,
            REFERENCE_ARGUMENT_NAME,
            TEXT_ARGUMENT_NAME,
        )
    }
}
