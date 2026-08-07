package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.agent.VerifiedToolExecution
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskScheduleControlCompletionPresentationTest {
    @Test
    fun verifiedPauseShowsStableStateWithoutInternalIds() {
        val presentation = presentTaskScheduleControlCompletion(
            contextFor(
                toolName = "tasks.pause",
                rawResult = "任务“每日回顾”：周期计划已暂停，后续不会生成新的执行实例。每日。",
            ),
        )

        assertTrue(presentation?.text.orEmpty().startsWith("周期计划已暂停：每日回顾"))
        assertTrue(presentation?.text.orEmpty().contains("正在运行的实例和旧运行记录保持不变"))
        assertFalse(presentation?.text.orEmpty().contains("schedule-private"))
    }

    @Test
    fun verifiedResumeShowsOneFutureInstanceWithoutBackfill() {
        val presentation = presentTaskScheduleControlCompletion(
            contextFor(
                toolName = "tasks.resume",
                rawResult = "任务“每日回顾”：周期计划已恢复。每日。 下次：2026-08-08 09:00。schedule-private",
            ),
        )

        assertTrue(presentation?.text.orEmpty().startsWith("周期计划已恢复：每日回顾"))
        assertTrue(presentation?.text.orEmpty().contains("只安排当前时间之后的一个实例"))
        assertTrue(presentation?.text.orEmpty().contains("不补跑暂停期间的周期"))
        assertFalse(presentation?.text.orEmpty().contains("schedule-private"))
    }

    @Test
    fun idempotentStatesUseDistinctStableMessages() {
        val paused = presentTaskScheduleControlCompletion(
            contextFor(
                toolName = "tasks.pause",
                rawResult = "任务“每日回顾”：周期计划已经暂停，无需重复操作。每日。",
            ),
        )
        val resumed = presentTaskScheduleControlCompletion(
            contextFor(
                toolName = "tasks.resume",
                rawResult = "任务“每日回顾”：周期计划已经处于恢复状态，无需重复操作。每日。",
            ),
        )

        assertTrue(paused?.text.orEmpty().startsWith("周期计划已处于暂停状态："))
        assertTrue(resumed?.text.orEmpty().startsWith("周期计划已处于恢复状态："))
    }

    @Test
    fun unverifiedMismatchedOrAmbiguousControlFailsClosed() {
        assertNull(
            presentTaskScheduleControlCompletion(
                contextFor(
                    toolName = "tasks.pause",
                    rawResult = "任务“每日回顾”：周期计划已暂停，后续不会生成新的执行实例。每日。",
                    status = AgentVerificationStatus.READABLE_ONLY,
                ),
            ),
        )
        assertNull(
            presentTaskScheduleControlCompletion(
                contextFor(
                    toolName = "tasks.pause",
                    rawResult = "任务“每日回顾”：周期计划已恢复。每日。",
                ),
            ),
        )
        assertNull(
            presentTaskScheduleControlCompletion(
                contextFor(
                    toolName = "tasks.resume",
                    rawResult = "任务“其他任务”：周期计划已恢复。每日。",
                ),
            ),
        )
        val pause = contextFor(
            toolName = "tasks.pause",
            rawResult = "任务“每日回顾”：周期计划已暂停，后续不会生成新的执行实例。每日。",
        ).toolExecutions.single()
        val resume = contextFor(
            toolName = "tasks.resume",
            rawResult = "任务“每日回顾”：周期计划已恢复。每日。",
        ).toolExecutions.single()
        assertNull(
            presentTaskScheduleControlCompletion(
                contextFor(
                    toolName = "tasks.pause",
                    rawResult = pause.rawResult,
                ).copy(toolExecutions = listOf(pause, resume)),
            ),
        )
    }

    private fun contextFor(
        toolName: String,
        rawResult: String,
        status: AgentVerificationStatus = AgentVerificationStatus.VERIFIED,
    ): VerifiedAgentContext {
        val execution = VerifiedToolExecution(
            toolName = toolName,
            arguments = mapOf("name" to "每日回顾"),
            success = true,
            verificationStatus = status,
            rawResult = rawResult,
        )
        return VerifiedAgentContext(
            runId = "run-schedule-control",
            toolName = toolName,
            arguments = execution.arguments,
            success = true,
            verificationStatus = status,
            rawResult = rawResult,
            toolExecutions = listOf(execution),
        )
    }
}
