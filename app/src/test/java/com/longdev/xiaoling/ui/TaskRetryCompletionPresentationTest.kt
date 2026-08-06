package com.longdev.xiaoling.ui

import com.longdev.xiaoling.automation.WorkflowRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRetryCompletionPresentationTest {
    @Test
    fun completedRetryReportsFinalStateWithoutInternalEvidence() {
        val presentation = presentTaskRetryCompletion(
            taskName = "每日回顾",
            status = WorkflowRunStatus.COMPLETED,
            reusedStepCount = 2,
        )

        assertEquals("assistant", presentation?.role)
        assertTrue(presentation?.text.orEmpty().contains("任务关联重试已完成：每日回顾"))
        assertTrue(presentation?.text.orEmpty().contains("已复用 2 个已完成步骤"))
        assertTrue(presentation?.text.orEmpty().contains("旧运行记录保持不变"))
        assertFalse(presentation?.text.orEmpty().contains("workflow-run-private"))
    }

    @Test
    fun failedAndCancelledRetryExplainRecoveryBoundary() {
        listOf(WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED).forEach { status ->
            val presentation = presentTaskRetryCompletion("每日回顾", status, reusedStepCount = 1)

            assertEquals("error", presentation?.role)
            assertTrue(presentation?.text.orEmpty().contains("不会恢复或重放旧执行栈"))
            assertTrue(presentation?.text.orEmpty().contains("任务中心"))
        }
    }

    @Test
    fun nonTerminalRetryDoesNotProduceFinalResult() {
        assertNull(presentTaskRetryCompletion("每日回顾", WorkflowRunStatus.QUEUED, 0))
        assertNull(presentTaskRetryCompletion("每日回顾", WorkflowRunStatus.RUNNING, 0))
    }

    @Test
    fun visibleTaskNameCannotInjectAdditionalLines() {
        val presentation = presentTaskRetryCompletion(
            taskName = "  每日\n回顾  ",
            status = WorkflowRunStatus.COMPLETED,
            reusedStepCount = 0,
        )

        assertTrue(presentation?.text.orEmpty().startsWith("任务关联重试已完成：每日 回顾\n"))
        assertEquals(1, presentation?.text.orEmpty().count { character -> character == '\n' })
    }
}
