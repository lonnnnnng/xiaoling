package com.longdev.xiaoling.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalTaskPlanCancellationTest {
    @Test
    fun `planning cancellation keeps the original goal retryable`() {
        val failure = personalTaskPlanCancellationFailure("整理今天的任务")

        assertEquals("整理今天的任务", failure.goal)
        assertEquals("计划生成已停止", failure.title)
        assertTrue(failure.message.contains("原始目标已保留"))
        assertTrue(failure.message.contains("重新生成"))
        assertEquals(PersonalTaskFailureAction.RETRY_PLAN, failure.action)
    }

    @Test
    fun `committed failure only offers workflow navigation`() {
        val failure = personalTaskCommittedFailure(
            goal = "整理今天的任务",
            title = "个人任务执行失败",
            message = "任务记录已保留",
        )

        assertEquals("整理今天的任务", failure.goal)
        assertEquals(PersonalTaskFailureAction.VIEW_WORKFLOW, failure.action)
    }
}
