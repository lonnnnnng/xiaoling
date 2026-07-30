package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.AutoApprovalGate
import com.longdev.xiaoling.agent.ToolCall
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.agent.WorkflowDeviceActionApprovalGate
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.device.DeviceActionApprovalOverlayDecision
import com.longdev.xiaoling.device.DeviceActionApprovalOverlayDecisionKind
import com.longdev.xiaoling.device.DeviceActionApprovalOverlayRequester
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomWorkflowDeviceActionApprovalPersistenceInstrumentedTest {
    private lateinit var database: XiaoLingDatabase
    private lateinit var repository: RoomAgentRunRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomAgentRunRepository(context, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun typeTextApprovalPersistsFingerprintAndLengthWithoutInputText() = runBlocking {
        val run = repository.createRun(
            conversationId = "conversation-workflow-type-text-approval",
            userMessageId = "message-workflow-type-text-approval",
            goal = "在当前输入框输入普通文本",
        )
        var overlaySummary: String? = null
        val gate = WorkflowDeviceActionApprovalGate(
            conversationId = run.conversationId,
            userIntent = "在当前输入框输入安全文本",
            fallback = AutoApprovalGate(),
            persistence = RoomWorkflowDeviceActionApprovalPersistence(repository),
            overlayRequester = DeviceActionApprovalOverlayRequester { request ->
                overlaySummary = request.actionSummary
                DeviceActionApprovalOverlayDecision(
                    DeviceActionApprovalOverlayDecisionKind.APPROVED,
                    "用户已在设备动作审批浮层批准",
                )
            },
        )

        val decision = gate.requestApproval(
            runId = run.id,
            toolCall = ToolCall(
                id = "tool-call-workflow-type-text-approval",
                name = "device.type_text",
                arguments = mapOf(
                    "snapshot_id" to "snapshot-approval",
                    "ref" to "r1",
                    "text" to INPUT_TEXT,
                ),
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
            definition = ToolDefinition(
                name = "device.type_text",
                description = "向当前快照中的可编辑节点输入普通文本",
                risk = ToolRisk.REQUIRES_APPROVAL,
            ),
        )

        val detail = checkNotNull(repository.runDetail(run.id))
        val approval = detail.approvals.single()
        assertTrue(decision.approved)
        assertEquals(ApprovalRequestStatus.APPROVED, approval.status)
        assertEquals(
            mapOf(
                "snapshot_id" to "snapshot-approval",
                "ref" to "r1",
                "text_sha256" to "436fe0a3fa0af22183e6584a91e42c2921bf3e096a4dca139f866a8b8296d752",
                "text_length" to "18",
            ),
            approval.arguments,
        )
        assertEquals("输入 18 个字符，内容不展示", overlaySummary)
        // long: 审批记录与 requested/decided 事件都由 Repository 对外投影；这些可持久事实中任一处出现原文，都会让历史审计成为输入内容副本。
        assertFalse(approval.toString().contains(INPUT_TEXT))
        assertFalse(detail.snapshot.events.toString().contains(INPUT_TEXT))
    }

    private companion object {
        const val INPUT_TEXT = "Workflow safe text"
    }
}
