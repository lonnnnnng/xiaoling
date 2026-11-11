package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AgentRunStopCoordinatorTest {
    @Test fun activeRunKeepsRuntimeResponsibleForCancellationBudget() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val run = ledger.createRun("conversation", "message", "等待模型")
        val requested = mutableListOf<String>()
        val coordinator = AgentRunStopCoordinator(
            loadRun = { AgentRunDetailRecord(ledger.snapshot(it), emptyList()) },
            linkedScheduledTaskId = { null },
            stopScheduledTask = { error("普通 Run 不属于后台任务") },
            requestCancel = { id, _ -> requested += id; ledger.requestCancelForTest(id); true },
            isExecuting = { true },
            cancelInactiveRun = { _, _ -> error("活跃 Runtime 必须保存取消预算后自行结算") },
            settleCancelledWorkflow = { _, _ -> error("尚未终态不能提前关闭工作流") },
        )
        val detail = coordinator.stop(run.id, "用户停止")!!
        assertEquals(listOf(run.id), requested)
        assertNotNull(detail.snapshot.run.cancelRequestedAt)
        assertEquals(AgentRunStatus.QUEUED, detail.snapshot.run.status)
    }

    @Test fun backgroundRunRoutesToItsPersistedTaskBeforeCancellingAgent() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val run = ledger.createRun("conversation", "message", "后台执行")
        val tasks = mutableListOf<String>()
        val coordinator = AgentRunStopCoordinator(
            loadRun = { AgentRunDetailRecord(ledger.snapshot(it), emptyList()) },
            linkedScheduledTaskId = { "task-linked" },
            stopScheduledTask = { tasks += it; ledger.updateRunStatus(run.id, AgentRunStatus.CANCELLED) },
            requestCancel = { _, _ -> error("后台任务必须先写 Task 停止栅栏") },
            isExecuting = { true },
            cancelInactiveRun = { _, _ -> error("后台停止使用原兜底流程") },
            settleCancelledWorkflow = { _, _ -> error("后台联合结算不能降为前台结算") },
        )
        assertEquals(AgentRunStatus.CANCELLED, coordinator.stop(run.id, "停止")!!.snapshot.run.status)
        assertEquals(listOf("task-linked"), tasks)
        coordinator.stop(run.id, "重复停止")
        assertEquals(1, tasks.size)
    }
}
