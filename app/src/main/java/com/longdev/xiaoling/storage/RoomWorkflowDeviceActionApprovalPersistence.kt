package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.ApprovalRequestRecord
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.ToolCall
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.WorkflowDeviceActionApprovalPersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomWorkflowDeviceActionApprovalPersistence(
    private val repository: RoomAgentRunRepository,
) : WorkflowDeviceActionApprovalPersistence {
    constructor(context: Context) : this(RoomAgentRunRepository(context.applicationContext))

    override suspend fun createApprovalRequest(
        conversationId: String,
        runId: String,
        toolCall: ToolCall,
        definition: ToolDefinition,
    ): ApprovalRequestRecord = withContext(Dispatchers.IO) {
        // long: 系统浮层只负责收集用户决定；请求身份先进入 Room，进程退出后仍能审计用户当时批准的 Run、ToolCall 与参数。
        repository.createApprovalRequest(conversationId, runId, toolCall, definition)
    }

    override suspend fun decideApprovalRequest(
        requestId: String,
        status: ApprovalRequestStatus,
        reason: String,
    ): ApprovalRequestRecord? = withContext(Dispatchers.IO) {
        repository.decideApprovalRequest(requestId, status, reason)
    }
}
