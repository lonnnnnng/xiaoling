package com.longdev.xiaoling.agent

import com.longdev.xiaoling.device.DeviceActionApprovalOverlayDecisionKind
import com.longdev.xiaoling.device.DeviceActionApprovalOverlayRequest
import com.longdev.xiaoling.device.DeviceActionApprovalOverlayRequester
import com.longdev.xiaoling.device.DeviceActionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

interface WorkflowDeviceActionApprovalPersistence {
    suspend fun createApprovalRequest(
        conversationId: String,
        runId: String,
        toolCall: ToolCall,
        definition: ToolDefinition,
    ): ApprovalRequestRecord

    suspend fun decideApprovalRequest(
        requestId: String,
        status: ApprovalRequestStatus,
        reason: String,
    ): ApprovalRequestRecord?
}

class WorkflowDeviceActionApprovalGate(
    private val conversationId: String,
    private val userIntent: String,
    private val targetAppPackage: String? = null,
    private val fallback: ApprovalGate,
    private val persistence: WorkflowDeviceActionApprovalPersistence,
    private val overlayRequester: DeviceActionApprovalOverlayRequester,
) : ApprovalGate {
    override suspend fun requestApproval(
        runId: String,
        toolCall: ToolCall,
        definition: ToolDefinition,
    ): ApprovalDecision {
        if (toolCall.name !in WORKFLOW_OVERLAY_APPROVAL_TOOL_NAMES) {
            return fallback.requestApproval(runId, toolCall, definition)
        }
        val approvalInput = toolCall.toApprovalInput()
            ?: return ApprovalDecision(
                approved = false,
                reason = "Workflow 设备动作审批参数不完整",
            )

        var request: ApprovalRequestRecord? = null
        var decisionAttempted = false
        return try {
            request = persistence.createApprovalRequest(
                conversationId = conversationId,
                runId = runId,
                toolCall = approvalInput.persistedToolCall,
                definition = definition,
            )
            if (!request.matches(runId, approvalInput.persistedToolCall, definition)) {
                val reason = "持久化审批身份与当前 Workflow ToolCall 不一致"
                persistDecision(request, ApprovalRequestStatus.CANCELLED, reason)
                decisionAttempted = true
                return ApprovalDecision(approved = false, reason = reason)
            }

            val overlayDecision = overlayRequester.request(
                DeviceActionApprovalOverlayRequest(
                    approvalRequestId = request.id,
                    runId = runId,
                    toolCallId = toolCall.id,
                    toolName = toolCall.name,
                    userIntent = userIntent,
                    toolDescription = definition.description,
                    actionSummary = approvalInput.actionSummary,
                ),
            )
            val status = when (overlayDecision.kind) {
                DeviceActionApprovalOverlayDecisionKind.APPROVED -> ApprovalRequestStatus.APPROVED
                DeviceActionApprovalOverlayDecisionKind.DENIED -> ApprovalRequestStatus.DENIED
                DeviceActionApprovalOverlayDecisionKind.CANCELLED,
                DeviceActionApprovalOverlayDecisionKind.SERVICE_DISCONNECTED,
                DeviceActionApprovalOverlayDecisionKind.BUSY,
                DeviceActionApprovalOverlayDecisionKind.WINDOW_CHANGED,
                DeviceActionApprovalOverlayDecisionKind.OVERLAY_UNAVAILABLE,
                -> ApprovalRequestStatus.CANCELLED
            }
            val persisted = persistDecision(request, status, overlayDecision.reason)
            decisionAttempted = true
            if (persisted == null || !persisted.matchesDecision(request, status, overlayDecision.reason)) {
                return ApprovalDecision(
                    approved = false,
                    reason = "设备动作审批身份或持久化决定已经变化，拒绝执行",
                )
            }
            ApprovalDecision(
                approved = status == ApprovalRequestStatus.APPROVED && overlayDecision.approved,
                reason = overlayDecision.reason,
                // long: 只有浮层从稳定基线开始、持续监控窗口并安全移除后，Runtime 才能把本次决定作为非节点打开应用动作的窗口守护证据。
                windowGuarded = status == ApprovalRequestStatus.APPROVED && overlayDecision.approved,
            )
        } catch (cancelled: CancellationException) {
            request?.let {
                if (!decisionAttempted) persistDecision(it, ApprovalRequestStatus.CANCELLED, "设备动作审批等待已取消")
            }
            throw cancelled
        } catch (error: Exception) {
            val reason = "设备动作审批浮层不可用：${error.message ?: error::class.java.simpleName}"
            request?.let {
                if (!decisionAttempted) persistDecision(it, ApprovalRequestStatus.CANCELLED, reason)
            }
            ApprovalDecision(approved = false, reason = reason)
        }
    }

    private suspend fun persistDecision(
        request: ApprovalRequestRecord,
        status: ApprovalRequestStatus,
        reason: String,
    ): ApprovalRequestRecord? {
        // long: 用户在系统浮层做出决定后，Room 必须先落下同一请求的终态，Runtime 才能收到批准；取消也在不可取消区收敛，避免留下永久 PENDING。
        return withContext(NonCancellable + Dispatchers.IO) {
            persistence.decideApprovalRequest(request.id, status, reason)
        }
    }

    private fun ApprovalRequestRecord.matches(
        expectedRunId: String,
        toolCall: ToolCall,
        definition: ToolDefinition,
    ): Boolean {
        return id.isNotBlank() &&
            status == ApprovalRequestStatus.PENDING &&
            runId == expectedRunId &&
            conversationId == this@WorkflowDeviceActionApprovalGate.conversationId &&
            toolCallId == toolCall.id &&
            toolName == toolCall.name &&
            toolDescription == definition.description &&
            risk == definition.risk &&
            arguments == toolCall.arguments
    }

    private fun ApprovalRequestRecord.matchesDecision(
        request: ApprovalRequestRecord,
        expectedStatus: ApprovalRequestStatus,
        expectedReason: String,
    ): Boolean {
        return id == request.id &&
            runId == request.runId &&
            conversationId == request.conversationId &&
            toolCallId == request.toolCallId &&
            toolName == request.toolName &&
            toolDescription == request.toolDescription &&
            risk == request.risk &&
            arguments == request.arguments &&
            status == expectedStatus &&
            decisionReason == expectedReason &&
            decidedAt != null
    }

    private fun ToolCall.toApprovalInput(): WorkflowDeviceActionApprovalInput? {
        if (name == DEVICE_OPEN_APP_TOOL_NAME) {
            val packageName = arguments["package_name"]
                ?.takeIf { arguments.keys == setOf("package_name") }
                ?.takeIf { it in DeviceActionPolicy.DEFAULT_ALLOWED_PACKAGES }
                ?.takeIf { it == targetAppPackage }
                ?: return null
            // long: 浮层与 Room 都绑定模型实际请求的白名单包名，用户批准计算器不能被复用于设置或任意第三方应用。
            return WorkflowDeviceActionApprovalInput(
                persistedToolCall = this,
                actionSummary = "打开允许列表应用 $packageName",
            )
        }
        if (name == DEVICE_TAP_REF_TOOL_NAME) {
            return WorkflowDeviceActionApprovalInput(
                persistedToolCall = this,
                actionSummary = "点击当前页面节点",
            )
        }
        val projection = DeviceTypeTextAuditPolicy.project(this) ?: return null
        return WorkflowDeviceActionApprovalInput(
            persistedToolCall = projection.persistedToolCall,
            actionSummary = "输入 ${projection.textLength} 个字符，内容不展示",
        )
    }

    private data class WorkflowDeviceActionApprovalInput(
        val persistedToolCall: ToolCall,
        val actionSummary: String,
    )

    private companion object {
        val WORKFLOW_OVERLAY_APPROVAL_TOOL_NAMES = setOf(
            DEVICE_OPEN_APP_TOOL_NAME,
            DEVICE_TAP_REF_TOOL_NAME,
            DeviceTypeTextAuditPolicy.TOOL_NAME,
        )
    }
}
