package com.longdev.xiaoling.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentExecutionBudgetTest {
    @Test
    fun multipleModelAndToolSegmentsConsumeOneRunBudget() = runTest {
        val budget = AgentExecutionBudget(
            totalTimeoutMs = 100,
            monotonicClock = MonotonicClock { testScheduler.currentTime },
        )

        budget.run("模型规划 1", 100) { delay(25) }
        budget.run("工具执行 1", 100) { delay(35) }
        budget.run("模型规划 2", 100) { delay(30) }
        val failure = runCatching {
            budget.run("模型总结", 100) { delay(20) }
        }.exceptionOrNull()

        assertTrue(failure is AgentTimeoutException)
        assertEquals("Agent Run 超时：100ms", failure?.message)
        assertEquals(100L, testScheduler.currentTime)
    }

    @Test
    fun stepTimeoutWinsWhileRunBudgetStillHasMoreTime() = runTest {
        val budget = AgentExecutionBudget(
            totalTimeoutMs = 100,
            monotonicClock = MonotonicClock { testScheduler.currentTime },
        )

        val failure = runCatching {
            budget.run("模型规划", 40) { delay(50) }
        }.exceptionOrNull()

        assertTrue(failure is AgentTimeoutException)
        assertEquals("模型规划 超时：40ms", failure?.message)
        assertEquals(40L, testScheduler.currentTime)
    }

    @Test
    fun runTimeoutWinsWhenRemainingBudgetEqualsStepTimeout() = runTest {
        val budget = AgentExecutionBudget(
            totalTimeoutMs = 100,
            monotonicClock = MonotonicClock { testScheduler.currentTime },
        )
        budget.run("模型规划", 100) { delay(60) }

        val failure = runCatching {
            budget.run("工具执行", 40) { delay(50) }
        }.exceptionOrNull()

        assertTrue(failure is AgentTimeoutException)
        assertEquals("Agent Run 超时：100ms", failure?.message)
        assertEquals(100L, testScheduler.currentTime)
    }
}
