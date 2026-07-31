package com.longdev.xiaoling.automation

import com.longdev.xiaoling.device.DeviceScrollDirection
import java.security.MessageDigest
import kotlin.math.abs

data class WorkflowSwipeTargetEvidence(
    val enabled: Boolean,
    val redacted: Boolean,
    val supportsSwipe: Boolean,
    val targetFingerprint: String,
)

data class WorkflowSwipeVisibleAnchor(
    val fingerprint: String,
    val centerX: Int,
    val centerY: Int,
)

data class WorkflowSwipeViewportEvidence(
    val packageName: String,
    val windowId: Int,
    val windowGeneration: Long,
    val targetFingerprint: String,
    val anchors: List<WorkflowSwipeVisibleAnchor>,
)

data class WorkflowSwipeExecutionEvidence(
    val target: WorkflowSwipeTargetEvidence?,
    val beforeViewport: WorkflowSwipeViewportEvidence,
)

data class WorkflowSwipeCompletionEvidence(
    val resultAgentRunId: String,
    val resultToolCallId: String,
    val resultToolName: String,
    val actionCompletedAt: Long,
    val observedAt: Long,
    val executorVerified: Boolean,
    val verificationPassed: Boolean,
    val afterObservationVerified: Boolean,
    val beforeViewport: WorkflowSwipeViewportEvidence,
    val afterViewport: WorkflowSwipeViewportEvidence,
)

data class WorkflowSwipeAuthorization(
    val ruleVersion: String,
    val workflowRunId: String,
    val workflowStepId: String,
    val agentRunId: String,
    val toolCallId: String,
    val toolName: String,
    val direction: DeviceScrollDirection,
    val beforeViewportFingerprint: String,
)

enum class WorkflowSwipeSafetyFailure {
    ARGUMENTS_INVALID,
    TARGET_MISSING,
    TARGET_NOT_AVAILABLE,
    TARGET_REDACTED,
    TARGET_ACTION_UNAVAILABLE,
    VIEWPORT_INVALID,
    AUTHORIZATION_MISSING,
    AUTHORIZATION_MISMATCH,
    RESULT_MISMATCH,
    POST_VERIFICATION_MISSING,
    WINDOW_MISMATCH,
    CONTENT_UNCHANGED,
    DIRECTION_NOT_VERIFIED,
}

sealed interface WorkflowSwipeSafetyDecision {
    data class Allowed(
        val authorization: WorkflowSwipeAuthorization,
    ) : WorkflowSwipeSafetyDecision

    data class Denied(
        val reason: WorkflowSwipeSafetyFailure,
        val message: String,
    ) : WorkflowSwipeSafetyDecision
}

class WorkflowSwipeSafetyPolicy {
    fun assessExecution(
        identity: WorkflowDeviceActionIdentity,
        evidence: WorkflowSwipeExecutionEvidence,
    ): WorkflowSwipeSafetyDecision {
        val direction = parseDirection(identity)
            ?: return denied(
                WorkflowSwipeSafetyFailure.ARGUMENTS_INVALID,
                "Workflow 滚动必须提供且只提供 snapshot_id、ref 与合法 direction",
            )
        val target = evidence.target
            ?: return denied(
                WorkflowSwipeSafetyFailure.TARGET_MISSING,
                "Workflow 滚动缺少当前节点的结构化证据",
            )
        if (!target.enabled || !isFingerprint(target.targetFingerprint)) {
            return denied(
                WorkflowSwipeSafetyFailure.TARGET_NOT_AVAILABLE,
                "Workflow 滚动只允许作用于当前启用且身份完整的节点",
            )
        }
        if (target.redacted) {
            return denied(
                WorkflowSwipeSafetyFailure.TARGET_REDACTED,
                "Workflow 滚动不得作用于已脱敏节点",
            )
        }
        if (!target.supportsSwipe) {
            return denied(
                WorkflowSwipeSafetyFailure.TARGET_ACTION_UNAVAILABLE,
                "当前节点未声明 SWIPE 动作能力",
            )
        }
        if (!isValidViewport(evidence.beforeViewport, target.targetFingerprint)) {
            return denied(
                WorkflowSwipeSafetyFailure.VIEWPORT_INVALID,
                "Workflow 滚动缺少可比较的当前窗口与匿名可见锚点",
            )
        }

        // long: 专属授权只冻结匿名目标、方向和动作前 viewport 摘要；节点正文与完整锚点列表继续只在当前执行链中参与验证。
        return WorkflowSwipeSafetyDecision.Allowed(
            WorkflowSwipeAuthorization(
                ruleVersion = RULE_VERSION,
                workflowRunId = identity.workflowRunId,
                workflowStepId = identity.workflowStepId,
                agentRunId = identity.agentRunId,
                toolCallId = identity.toolCallId,
                toolName = identity.toolName,
                direction = direction,
                beforeViewportFingerprint = fingerprintViewport(evidence.beforeViewport),
            ),
        )
    }

    fun assessCompletion(
        identity: WorkflowDeviceActionIdentity,
        authorization: WorkflowSwipeAuthorization?,
        evidence: WorkflowSwipeCompletionEvidence,
    ): WorkflowSwipeSafetyDecision {
        authorization
            ?: return denied(
                WorkflowSwipeSafetyFailure.AUTHORIZATION_MISSING,
                "Workflow 滚动结果缺少执行前专属授权",
            )
        if (!authorizationMatches(identity, authorization, evidence.beforeViewport)) {
            return denied(
                WorkflowSwipeSafetyFailure.AUTHORIZATION_MISMATCH,
                "Workflow 滚动授权与当前 Run、ToolCall、方向或动作前 viewport 不一致",
            )
        }
        if (
            evidence.resultAgentRunId != identity.agentRunId ||
            evidence.resultToolCallId != identity.toolCallId ||
            evidence.resultToolName != identity.toolName
        ) {
            return denied(
                WorkflowSwipeSafetyFailure.RESULT_MISMATCH,
                "Workflow 滚动结果不属于当前 Agent Run 与 ToolCall",
            )
        }
        if (
            !evidence.executorVerified ||
            !evidence.verificationPassed ||
            !evidence.afterObservationVerified ||
            evidence.actionCompletedAt < 0L ||
            evidence.observedAt < evidence.actionCompletedAt
        ) {
            return denied(
                WorkflowSwipeSafetyFailure.POST_VERIFICATION_MISSING,
                "Workflow 滚动缺少 Executor、typed 验证或动作后观察",
            )
        }
        val before = evidence.beforeViewport
        val after = evidence.afterViewport
        if (
            !isValidViewport(before, before.targetFingerprint) ||
            !isValidViewport(after, before.targetFingerprint) ||
            before.packageName != after.packageName ||
            before.windowId != after.windowId ||
            after.windowGeneration <= before.windowGeneration ||
            before.targetFingerprint != after.targetFingerprint
        ) {
            return denied(
                WorkflowSwipeSafetyFailure.WINDOW_MISMATCH,
                "动作后观察必须属于同一应用、同一 window 与同一滚动目标",
            )
        }
        val beforeContent = before.anchors.mapTo(linkedSetOf()) { it.fingerprint }
        val afterContent = after.anchors.mapTo(linkedSetOf()) { it.fingerprint }
        if (beforeContent == afterContent) {
            return denied(
                WorkflowSwipeSafetyFailure.CONTENT_UNCHANGED,
                "动作前后可见匿名内容集合没有变化，不能证明发生滚动",
            )
        }
        if (!isDirectionVerified(authorization.direction, before.anchors, after.anchors)) {
            return denied(
                WorkflowSwipeSafetyFailure.DIRECTION_NOT_VERIFIED,
                "共同可见锚点没有按请求方向产生稳定主位移",
            )
        }
        return WorkflowSwipeSafetyDecision.Allowed(authorization)
    }

    private fun parseDirection(identity: WorkflowDeviceActionIdentity): DeviceScrollDirection? {
        if (
            identity.workflowRunId.isBlank() ||
            identity.workflowStepId.isBlank() ||
            identity.agentRunId.isBlank() ||
            identity.toolCallId.isBlank() ||
            identity.toolName != DEVICE_SWIPE_TOOL_NAME ||
            identity.arguments.keys != REQUIRED_ARGUMENT_NAMES ||
            identity.arguments[SNAPSHOT_ARGUMENT_NAME].isNullOrBlank() ||
            identity.arguments[REFERENCE_ARGUMENT_NAME].isNullOrBlank()
        ) {
            return null
        }
        return when (identity.arguments[DIRECTION_ARGUMENT_NAME]) {
            "up" -> DeviceScrollDirection.UP
            "down" -> DeviceScrollDirection.DOWN
            "left" -> DeviceScrollDirection.LEFT
            "right" -> DeviceScrollDirection.RIGHT
            else -> null
        }
    }

    private fun isValidViewport(
        viewport: WorkflowSwipeViewportEvidence,
        expectedTargetFingerprint: String,
    ): Boolean {
        if (
            viewport.packageName.isBlank() ||
            viewport.windowId < 0 ||
            viewport.windowGeneration < 0L ||
            viewport.targetFingerprint != expectedTargetFingerprint ||
            viewport.anchors.size < MIN_VISIBLE_ANCHORS
        ) {
            return false
        }
        val anchorFingerprints = viewport.anchors.map { it.fingerprint }
        return anchorFingerprints.distinct().size == anchorFingerprints.size &&
            anchorFingerprints.all(::isFingerprint)
    }

    private fun authorizationMatches(
        identity: WorkflowDeviceActionIdentity,
        authorization: WorkflowSwipeAuthorization,
        beforeViewport: WorkflowSwipeViewportEvidence,
    ): Boolean {
        val direction = parseDirection(identity) ?: return false
        return authorization.ruleVersion == RULE_VERSION &&
            authorization.workflowRunId == identity.workflowRunId &&
            authorization.workflowStepId == identity.workflowStepId &&
            authorization.agentRunId == identity.agentRunId &&
            authorization.toolCallId == identity.toolCallId &&
            authorization.toolName == identity.toolName &&
            authorization.direction == direction &&
            authorization.beforeViewportFingerprint == fingerprintViewport(beforeViewport)
    }

    private fun isDirectionVerified(
        direction: DeviceScrollDirection,
        beforeAnchors: List<WorkflowSwipeVisibleAnchor>,
        afterAnchors: List<WorkflowSwipeVisibleAnchor>,
    ): Boolean {
        val beforeByFingerprint = beforeAnchors.associateBy { it.fingerprint }
        val commonMovements = afterAnchors.mapNotNull { after ->
            val before = beforeByFingerprint[after.fingerprint] ?: return@mapNotNull null
            (after.centerX - before.centerX) to (after.centerY - before.centerY)
        }
        if (commonMovements.isEmpty()) return false

        var directionObserved = false
        for ((deltaX, deltaY) in commonMovements) {
            if (abs(deltaX) < MIN_DIRECTIONAL_DISPLACEMENT_PX && abs(deltaY) < MIN_DIRECTIONAL_DISPLACEMENT_PX) {
                continue
            }
            val (primary, cross) = when (direction) {
                DeviceScrollDirection.UP -> -deltaY to deltaX
                DeviceScrollDirection.DOWN -> deltaY to deltaX
                DeviceScrollDirection.LEFT -> -deltaX to deltaY
                DeviceScrollDirection.RIGHT -> deltaX to deltaY
            }
            if (primary < MIN_DIRECTIONAL_DISPLACEMENT_PX || primary <= abs(cross)) {
                return false
            }
            directionObserved = true
        }
        return directionObserved
    }

    private fun fingerprintViewport(viewport: WorkflowSwipeViewportEvidence): String {
        val canonical = buildString {
            append(viewport.packageName)
            append('|')
            append(viewport.windowId)
            append('|')
            append(viewport.windowGeneration)
            append('|')
            append(viewport.targetFingerprint)
            viewport.anchors.sortedBy { it.fingerprint }.forEach { anchor ->
                append('|')
                append(anchor.fingerprint)
                append(':')
                append(anchor.centerX)
                append(':')
                append(anchor.centerY)
            }
        }
        return sha256(canonical)
    }

    private fun isFingerprint(value: String): Boolean = FINGERPRINT_REGEX.matches(value)

    private fun sha256(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun denied(
        reason: WorkflowSwipeSafetyFailure,
        message: String,
    ): WorkflowSwipeSafetyDecision.Denied = WorkflowSwipeSafetyDecision.Denied(reason, message)

    companion object {
        const val RULE_VERSION = "workflow-swipe-safety-v1"
        private const val DEVICE_SWIPE_TOOL_NAME = "device.swipe"
        private const val SNAPSHOT_ARGUMENT_NAME = "snapshot_id"
        private const val REFERENCE_ARGUMENT_NAME = "ref"
        private const val DIRECTION_ARGUMENT_NAME = "direction"
        private const val MIN_VISIBLE_ANCHORS = 2
        private const val MIN_DIRECTIONAL_DISPLACEMENT_PX = 8
        private val REQUIRED_ARGUMENT_NAMES = setOf(
            SNAPSHOT_ARGUMENT_NAME,
            REFERENCE_ARGUMENT_NAME,
            DIRECTION_ARGUMENT_NAME,
        )
        private val FINGERPRINT_REGEX = Regex("[0-9a-f]{64}")
    }
}
