package com.longdev.xiaoling.automation

import com.longdev.xiaoling.agent.AgentExecutionOrigin
import com.longdev.xiaoling.agent.AgentInvocationSource
import com.longdev.xiaoling.device.DeviceActionPolicy

data class WorkflowDeviceActionIdentity(
    val workflowRunId: String,
    val workflowStepId: String,
    val agentRunId: String,
    val toolCallId: String,
    val toolName: String,
    val arguments: Map<String, String>,
)

data class WorkflowDeviceActionObservationEvidence(
    val agentRunId: String,
    val toolCallId: String,
    val toolName: String,
    val snapshotId: String,
    val capturedAt: Long,
    val expiresAt: Long,
    val windowGeneration: Long,
    val verified: Boolean,
)

data class WorkflowDeviceActionApprovalEvidence(
    val agentRunId: String,
    val toolCallId: String,
    val toolName: String,
    val arguments: Map<String, String>,
    val approved: Boolean,
    val decidedAt: Long,
    val decisionProcessSessionId: String,
)

enum class WorkflowDeviceActionApprovalMode {
    REQUIRE_APPROVAL,
    SAFE_NO_APPROVAL,
}

data class WorkflowDeviceActionExecutionEvidence(
    val identity: WorkflowDeviceActionIdentity,
    val userIntent: String,
    val invocationSource: AgentInvocationSource,
    val executionOrigin: AgentExecutionOrigin,
    val currentProcessSessionId: String,
    val observation: WorkflowDeviceActionObservationEvidence?,
    val approval: WorkflowDeviceActionApprovalEvidence?,
    val nowMillis: Long,
    val currentWindowGeneration: Long,
    val liveReferenceMatched: Boolean,
    val typeText: WorkflowTypeTextExecutionEvidence? = null,
    val swipe: WorkflowSwipeExecutionEvidence? = null,
)

data class WorkflowDeviceActionAuthorization(
    val ruleVersion: String,
    val identity: WorkflowDeviceActionIdentity,
    val observationToolCallId: String,
    val beforeSnapshotId: String,
    val beforeWindowGeneration: Long,
    val authorizedAt: Long,
    val processSessionId: String,
    val approvalMode: WorkflowDeviceActionApprovalMode,
    val typeTextAuthorization: WorkflowTypeTextAuthorization? = null,
    val swipeAuthorization: WorkflowSwipeAuthorization? = null,
)

data class WorkflowDeviceActionPostObservationEvidence(
    val agentRunId: String,
    val actionToolCallId: String,
    val snapshotId: String,
    val observedAt: Long,
    val windowGeneration: Long,
    val verified: Boolean,
)

data class WorkflowDeviceActionCompletionEvidence(
    val identity: WorkflowDeviceActionIdentity,
    val authorization: WorkflowDeviceActionAuthorization?,
    val resultAgentRunId: String,
    val resultToolCallId: String,
    val resultToolName: String,
    val success: Boolean,
    val executorVerified: Boolean,
    val verificationPassed: Boolean,
    val actionCompletedAt: Long,
    val afterPackageName: String? = null,
    val afterObservation: WorkflowDeviceActionPostObservationEvidence?,
    val cancelled: Boolean,
    val typeText: WorkflowTypeTextCompletionEvidence? = null,
    val swipe: WorkflowSwipeCompletionEvidence? = null,
)

enum class WorkflowDeviceActionSafetyFailure {
    ACTION_NOT_ENABLED,
    INVOCATION_SOURCE_DENIED,
    BACKGROUND_DENIED,
    IDENTITY_INVALID,
    ACTION_ARGUMENTS_INVALID,
    USER_INTENT_MISSING,
    OBSERVATION_MISSING,
    OBSERVATION_RUN_MISMATCH,
    OBSERVATION_INVALID,
    OBSERVATION_NOT_VERIFIED,
    OBSERVATION_EXPIRED,
    WINDOW_CHANGED,
    REFERENCE_MISMATCH,
    TYPE_TEXT_POLICY_DENIED,
    SWIPE_POLICY_DENIED,
    APPROVAL_MISSING,
    APPROVAL_NOT_APPROVED,
    APPROVAL_MISMATCH,
    APPROVAL_SESSION_MISMATCH,
    ACTION_CANCELLED,
    EXECUTION_AUTHORIZATION_MISSING,
    EXECUTION_AUTHORIZATION_MISMATCH,
    ACTION_RESULT_MISMATCH,
    ACTION_EXECUTION_FAILED,
    POST_ACTION_VERIFICATION_MISSING,
}

sealed interface WorkflowDeviceActionSafetyDecision {
    data class Allowed(
        val authorization: WorkflowDeviceActionAuthorization,
    ) : WorkflowDeviceActionSafetyDecision

    data class Denied(
        val reason: WorkflowDeviceActionSafetyFailure,
        val message: String,
    ) : WorkflowDeviceActionSafetyDecision
}

class WorkflowDeviceActionSafetyPolicy(
    enabledToolNames: Set<String> = emptySet(),
    private val typeTextSafetyPolicy: WorkflowTypeTextSafetyPolicy = WorkflowTypeTextSafetyPolicy(),
    private val swipeSafetyPolicy: WorkflowSwipeSafetyPolicy = WorkflowSwipeSafetyPolicy(),
) {
    private val enabledToolNames = enabledToolNames.toSet()

    init {
        val unknown = this.enabledToolNames - KNOWN_DEVICE_ACTION_TOOL_NAMES
        require(unknown.isEmpty()) {
            "Workflow 设备动作白名单包含未知工具：${unknown.sorted().joinToString()}"
        }
    }

    fun assessExecution(evidence: WorkflowDeviceActionExecutionEvidence): WorkflowDeviceActionSafetyDecision {
        // long: 生产默认不给 Workflow 任何设备动作；只有后续阶段显式列入白名单的工具才有资格继续核验意图、观察和审批证据。
        if (evidence.identity.toolName !in enabledToolNames) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.ACTION_NOT_ENABLED,
                "当前阶段未开放该 Workflow 设备动作",
            )
        }
        if (evidence.invocationSource != AgentInvocationSource.WORKFLOW) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.INVOCATION_SOURCE_DENIED,
                "有限设备动作只接受前台手动 Workflow 来源",
            )
        }
        if (evidence.executionOrigin != AgentExecutionOrigin.FOREGROUND) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.BACKGROUND_DENIED,
                "后台或定时 Workflow 不得执行设备动作",
            )
        }
        if (
            evidence.identity.workflowRunId.isBlank() ||
            evidence.identity.workflowStepId.isBlank() ||
            evidence.identity.agentRunId.isBlank() ||
            evidence.identity.toolCallId.isBlank() ||
            evidence.currentProcessSessionId.isBlank()
        ) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.IDENTITY_INVALID,
                "Workflow、Step、Agent Run、ToolCall 与当前进程会话身份必须完整",
            )
        }
        if (evidence.userIntent.isBlank()) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.USER_INTENT_MISSING,
                "Workflow 设备动作缺少用户明确编写的步骤意图",
            )
        }
        if (evidence.identity.toolName == DEVICE_OPEN_APP_TOOL_NAME) {
            val arguments = evidence.identity.arguments
            val packageName = arguments["package_name"]
            // long: 安全策略自身也冻结唯一白名单包名，不能依赖上游审批 Gate 或下游 Executor 替它补齐参数边界。
            if (
                arguments.keys != setOf("package_name") ||
                packageName !in DeviceActionPolicy.DEFAULT_ALLOWED_PACKAGES
            ) {
                return WorkflowDeviceActionSafetyDecision.Denied(
                    WorkflowDeviceActionSafetyFailure.ACTION_ARGUMENTS_INVALID,
                    "device.open_app 只能打开当前阶段允许列表中的单一应用",
                )
            }
        }
        if (evidence.identity.toolName in SAFE_NAVIGATION_TOOL_NAMES && evidence.identity.arguments.isNotEmpty()) {
            // long: 返回与桌面导航都没有目标、次数或坐标参数；拒绝所有额外字段，避免模型把一次 SAFE 动作扩张成可配置导航序列。
            val actionLabel = if (evidence.identity.toolName == DEVICE_HOME_TOOL_NAME) "返回桌面" else "返回"
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.ACTION_ARGUMENTS_INVALID,
                "${evidence.identity.toolName} 只能使用空参数执行一次$actionLabel",
            )
        }
        val observation = evidence.observation
            ?: return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.OBSERVATION_MISSING,
                "Workflow 设备动作缺少当前 Run 的 device.snapshot 证据",
            )
        if (observation.agentRunId != evidence.identity.agentRunId) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.OBSERVATION_RUN_MISMATCH,
                "device.snapshot 证据不属于当前 Agent Run",
            )
        }
        if (
            observation.toolName != DEVICE_SNAPSHOT_TOOL_NAME ||
            observation.toolCallId.isBlank() ||
            observation.toolCallId == evidence.identity.toolCallId ||
            observation.snapshotId.isBlank() ||
            observation.windowGeneration < 0L
        ) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.OBSERVATION_INVALID,
                "动作前观察必须是独立、身份完整的 device.snapshot ToolCall",
            )
        }
        if (!observation.verified) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.OBSERVATION_NOT_VERIFIED,
                "device.snapshot 缺少已通过的验证事实",
            )
        }
        if (
            observation.capturedAt < 0L ||
            observation.expiresAt <= observation.capturedAt ||
            observation.expiresAt - observation.capturedAt > MAX_OBSERVATION_LIFETIME_MILLIS ||
            evidence.nowMillis < observation.capturedAt ||
            evidence.nowMillis >= observation.expiresAt
        ) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.OBSERVATION_EXPIRED,
                "device.snapshot 已过期或时间证据不完整",
            )
        }
        if (evidence.currentWindowGeneration != observation.windowGeneration) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.WINDOW_CHANGED,
                "页面 window generation 已变化，必须重新观察",
            )
        }
        if (evidence.identity.toolName in REFERENCE_ACTION_TOOL_NAMES) {
            // long: 节点动作只能使用当前 Run 最新快照中的短期引用；历史输出、关联重试或进程重建都不得从文本恢复 ref。
            val arguments = evidence.identity.arguments
            if (
                arguments["snapshot_id"] != observation.snapshotId ||
                arguments["ref"].isNullOrBlank() ||
                !evidence.liveReferenceMatched
            ) {
                return WorkflowDeviceActionSafetyDecision.Denied(
                    WorkflowDeviceActionSafetyFailure.REFERENCE_MISMATCH,
                    "节点引用与当前 snapshot、路径或指纹不一致",
                )
            }
        }
        val typeTextAuthorization = if (evidence.identity.toolName == DEVICE_TYPE_TEXT_TOOL_NAME) {
            val typeTextEvidence = evidence.typeText
                ?: return WorkflowDeviceActionSafetyDecision.Denied(
                    WorkflowDeviceActionSafetyFailure.TYPE_TEXT_POLICY_DENIED,
                    "device.type_text 缺少专属节点与文本安全证据",
                )
            when (val decision = typeTextSafetyPolicy.assessExecution(evidence.identity, typeTextEvidence)) {
                is WorkflowTypeTextSafetyDecision.Allowed -> decision.authorization
                is WorkflowTypeTextSafetyDecision.Denied -> {
                    return WorkflowDeviceActionSafetyDecision.Denied(
                        WorkflowDeviceActionSafetyFailure.TYPE_TEXT_POLICY_DENIED,
                        decision.message,
                    )
                }
            }
        } else {
            null
        }
        val swipeAuthorization = if (evidence.identity.toolName == DEVICE_SWIPE_TOOL_NAME) {
            val swipeEvidence = evidence.swipe
                ?: return WorkflowDeviceActionSafetyDecision.Denied(
                    WorkflowDeviceActionSafetyFailure.SWIPE_POLICY_DENIED,
                    "device.swipe 缺少专属目标与动作前 viewport 证据",
                )
            when (val decision = swipeSafetyPolicy.assessExecution(evidence.identity, swipeEvidence)) {
                is WorkflowSwipeSafetyDecision.Allowed -> decision.authorization
                is WorkflowSwipeSafetyDecision.Denied -> {
                    return WorkflowDeviceActionSafetyDecision.Denied(
                        WorkflowDeviceActionSafetyFailure.SWIPE_POLICY_DENIED,
                        decision.message,
                    )
                }
            }
        } else {
            null
        }
        val approvalMode = approvalModeFor(evidence.identity.toolName)
        val authorizedAt = when (approvalMode) {
            WorkflowDeviceActionApprovalMode.SAFE_NO_APPROVAL -> evidence.nowMillis
            WorkflowDeviceActionApprovalMode.REQUIRE_APPROVAL -> {
                val approval = evidence.approval
                    ?: return WorkflowDeviceActionSafetyDecision.Denied(
                        WorkflowDeviceActionSafetyFailure.APPROVAL_MISSING,
                        "Workflow 设备动作缺少独立用户审批",
                    )
                if (!approval.approved) {
                    return WorkflowDeviceActionSafetyDecision.Denied(
                        WorkflowDeviceActionSafetyFailure.APPROVAL_NOT_APPROVED,
                        "当前设备动作未获得用户批准",
                    )
                }
                if (
                    approval.agentRunId != evidence.identity.agentRunId ||
                    approval.toolCallId != evidence.identity.toolCallId ||
                    approval.toolName != evidence.identity.toolName ||
                    approval.arguments != evidence.identity.arguments ||
                    approval.decidedAt < observation.capturedAt ||
                    approval.decidedAt > evidence.nowMillis
                ) {
                    return WorkflowDeviceActionSafetyDecision.Denied(
                        WorkflowDeviceActionSafetyFailure.APPROVAL_MISMATCH,
                        "审批与当前 Run、ToolCall、参数或观察时间不一致",
                    )
                }
                if (approval.decisionProcessSessionId != evidence.currentProcessSessionId) {
                    // long: 进程重建前已批准的动作不能自动续跑；恢复后必须重新观察并获得当前进程会话内的逐动作决定。
                    return WorkflowDeviceActionSafetyDecision.Denied(
                        WorkflowDeviceActionSafetyFailure.APPROVAL_SESSION_MISMATCH,
                        "审批来自旧进程会话，不得用于恢复或重试执行",
                    )
                }
                approval.decidedAt
            }
        }
        return WorkflowDeviceActionSafetyDecision.Allowed(
            WorkflowDeviceActionAuthorization(
                ruleVersion = RULE_VERSION,
                // long: type_text 的通用授权只绑定 snapshot 与 ref；文本原文改由专属不可逆指纹授权绑定，防止通用授权对象复制输入内容。
                identity = minimizedAuthorizationIdentity(evidence.identity),
                observationToolCallId = observation.toolCallId,
                beforeSnapshotId = observation.snapshotId,
                beforeWindowGeneration = observation.windowGeneration,
                // long: SAFE 系统导航与标准节点滚动都依赖用户步骤意图和同 Run 新鲜观察，不伪造审批时间；授权时间继续约束动作后证据晚于安全门禁。
                authorizedAt = authorizedAt,
                processSessionId = evidence.currentProcessSessionId,
                approvalMode = approvalMode,
                typeTextAuthorization = typeTextAuthorization,
                swipeAuthorization = swipeAuthorization,
            ),
        )
    }

    fun assessCompletion(evidence: WorkflowDeviceActionCompletionEvidence): WorkflowDeviceActionSafetyDecision {
        if (evidence.identity.toolName !in enabledToolNames) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.ACTION_NOT_ENABLED,
                "当前阶段未开放该 Workflow 设备动作",
            )
        }
        if (
            evidence.identity.workflowRunId.isBlank() ||
            evidence.identity.workflowStepId.isBlank() ||
            evidence.identity.agentRunId.isBlank() ||
            evidence.identity.toolCallId.isBlank()
        ) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.IDENTITY_INVALID,
                "Workflow、Step、Agent Run 与 ToolCall 身份必须完整",
            )
        }
        val authorization = evidence.authorization
            ?: return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.EXECUTION_AUTHORIZATION_MISSING,
                "设备动作结果缺少执行前安全门禁授权",
            )
        val expectedAuthorizationIdentity = minimizedAuthorizationIdentity(evidence.identity)
        if (
            authorization.ruleVersion != RULE_VERSION ||
            authorization.identity != expectedAuthorizationIdentity ||
            authorization.approvalMode != approvalModeFor(evidence.identity.toolName) ||
            (evidence.identity.toolName != DEVICE_TYPE_TEXT_TOOL_NAME && authorization.typeTextAuthorization != null) ||
            (evidence.identity.toolName != DEVICE_SWIPE_TOOL_NAME && authorization.swipeAuthorization != null)
        ) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.EXECUTION_AUTHORIZATION_MISMATCH,
                "执行前授权与当前规则版本或动作身份不一致",
            )
        }
        if (evidence.cancelled) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.ACTION_CANCELLED,
                "Workflow 已取消，迟到设备结果不得收敛为完成",
            )
        }
        if (
            evidence.resultAgentRunId != evidence.identity.agentRunId ||
            evidence.resultToolCallId != evidence.identity.toolCallId ||
            evidence.resultToolName != evidence.identity.toolName
        ) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.ACTION_RESULT_MISMATCH,
                "设备动作结果与当前 Agent Run 或 ToolCall 不一致",
            )
        }
        if (!evidence.success) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.ACTION_EXECUTION_FAILED,
                "设备动作没有成功执行",
            )
        }
        if (
            evidence.identity.toolName == DEVICE_OPEN_APP_TOOL_NAME &&
            evidence.afterPackageName != evidence.identity.arguments["package_name"]
        ) {
            // long: 打开应用只能以本次逐包审批的目标收敛；即使 Executor 声称已验证，后置包名错配也必须按结果身份不一致拒绝。
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.ACTION_RESULT_MISMATCH,
                "device.open_app 动作后包名与获批目标不一致",
            )
        }
        val afterObservation = evidence.afterObservation
        // long: Android 接收动作不等于用户目标完成；只有 Executor、typed 验证和后置 snapshot 同时成立才能进入 Workflow 完成投影。
        if (
            !evidence.executorVerified ||
            !evidence.verificationPassed ||
            evidence.actionCompletedAt <= authorization.authorizedAt ||
            afterObservation == null ||
            afterObservation.agentRunId != evidence.identity.agentRunId ||
            afterObservation.actionToolCallId != evidence.identity.toolCallId ||
            afterObservation.snapshotId.isBlank() ||
            afterObservation.observedAt < evidence.actionCompletedAt ||
            afterObservation.windowGeneration < 0L ||
            !afterObservation.verified
        ) {
            return WorkflowDeviceActionSafetyDecision.Denied(
                WorkflowDeviceActionSafetyFailure.POST_ACTION_VERIFICATION_MISSING,
                "设备动作缺少 Executor、typed 验证或后置 snapshot 证据",
            )
        }
        if (evidence.identity.toolName == DEVICE_TYPE_TEXT_TOOL_NAME) {
            val typeTextEvidence = evidence.typeText
                ?: return WorkflowDeviceActionSafetyDecision.Denied(
                    WorkflowDeviceActionSafetyFailure.TYPE_TEXT_POLICY_DENIED,
                    "device.type_text 缺少专属动作后精确回读证据",
                )
            when (
                val decision = typeTextSafetyPolicy.assessCompletion(
                    identity = evidence.identity,
                    authorization = authorization.typeTextAuthorization,
                    evidence = typeTextEvidence,
                )
            ) {
                is WorkflowTypeTextSafetyDecision.Allowed -> Unit
                is WorkflowTypeTextSafetyDecision.Denied -> {
                    return WorkflowDeviceActionSafetyDecision.Denied(
                        WorkflowDeviceActionSafetyFailure.TYPE_TEXT_POLICY_DENIED,
                        decision.message,
                    )
                }
            }
        }
        if (evidence.identity.toolName == DEVICE_SWIPE_TOOL_NAME) {
            val swipeEvidence = evidence.swipe
                ?: return WorkflowDeviceActionSafetyDecision.Denied(
                    WorkflowDeviceActionSafetyFailure.SWIPE_POLICY_DENIED,
                    "device.swipe 缺少专属同窗滚动后置证据",
                )
            when (
                val decision = swipeSafetyPolicy.assessCompletion(
                    identity = evidence.identity,
                    authorization = authorization.swipeAuthorization,
                    evidence = swipeEvidence,
                )
            ) {
                is WorkflowSwipeSafetyDecision.Allowed -> Unit
                is WorkflowSwipeSafetyDecision.Denied -> {
                    return WorkflowDeviceActionSafetyDecision.Denied(
                        WorkflowDeviceActionSafetyFailure.SWIPE_POLICY_DENIED,
                        decision.message,
                    )
                }
            }
        }
        return WorkflowDeviceActionSafetyDecision.Allowed(authorization)
    }

    private fun minimizedAuthorizationIdentity(identity: WorkflowDeviceActionIdentity): WorkflowDeviceActionIdentity {
        val authorizedArguments = if (identity.toolName == DEVICE_TYPE_TEXT_TOOL_NAME) {
            identity.arguments - "text"
        } else {
            identity.arguments
        }
        return identity.copy(arguments = authorizedArguments.toMap())
    }

    private fun approvalModeFor(toolName: String): WorkflowDeviceActionApprovalMode {
        return if (toolName in SAFE_NO_APPROVAL_TOOL_NAMES) {
            WorkflowDeviceActionApprovalMode.SAFE_NO_APPROVAL
        } else {
            WorkflowDeviceActionApprovalMode.REQUIRE_APPROVAL
        }
    }

    companion object {
        const val RULE_VERSION = "workflow-device-action-safety-v1"
        private const val DEVICE_SNAPSHOT_TOOL_NAME = "device.snapshot"
        private const val DEVICE_OPEN_APP_TOOL_NAME = "device.open_app"
        private const val DEVICE_BACK_TOOL_NAME = "device.back"
        private const val DEVICE_HOME_TOOL_NAME = "device.home"
        private const val DEVICE_TYPE_TEXT_TOOL_NAME = "device.type_text"
        private const val DEVICE_SWIPE_TOOL_NAME = "device.swipe"
        private const val MAX_OBSERVATION_LIFETIME_MILLIS = 30_000L
        private val KNOWN_DEVICE_ACTION_TOOL_NAMES = setOf(
            DEVICE_OPEN_APP_TOOL_NAME,
            "device.back",
            "device.home",
            "device.tap_ref",
            "device.type_text",
            "device.swipe",
        )
        private val SAFE_NO_APPROVAL_TOOL_NAMES = setOf(
            DEVICE_BACK_TOOL_NAME,
            DEVICE_HOME_TOOL_NAME,
            DEVICE_SWIPE_TOOL_NAME,
        )
        private val SAFE_NAVIGATION_TOOL_NAMES = setOf(DEVICE_BACK_TOOL_NAME, DEVICE_HOME_TOOL_NAME)
        private val REFERENCE_ACTION_TOOL_NAMES = setOf("device.tap_ref", "device.type_text", "device.swipe")
    }
}
