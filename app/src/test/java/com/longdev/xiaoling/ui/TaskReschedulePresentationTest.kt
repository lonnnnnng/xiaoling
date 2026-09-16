package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentTaskRescheduleRequest
import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.agent.VerifiedToolExecution
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import org.junit.Assert.*
import org.junit.Test

class TaskReschedulePresentationTest {
    private val arguments = mapOf(
        "name" to "喝水提醒", "expected_planned_at" to "2026-09-08T09:30:00+08:00",
        "planned_at" to "2026-09-08T10:00:00+08:00", "time_zone" to "Asia/Shanghai",
        "expected_schedule_token" to "one-time-v1-${"a".repeat(64)}",
    )
    private val result = AgentTaskRescheduleRequest.parse(arguments)!!.resultText()
    private val execution = VerifiedToolExecution("tasks.reschedule", arguments, true, AgentVerificationStatus.VERIFIED, result)
    private val context = VerifiedAgentContext("run", execution.toolName, arguments, true, execution.verificationStatus, result, toolExecutions = listOf(execution))

    @Test fun verifiedRescheduleShowsTimesAndRefreshesCurrentWorkflow() {
        assertEquals(result, presentTaskScheduleControlCompletion(context)?.text)
        assertTrue(shouldRefreshWorkflowsAfterTaskScheduleControl(context))
    }

    @Test fun approvalShowsAllFourReadableFieldsWithoutInternalFingerprint() {
        val text = taskRescheduleApprovalText("tasks.reschedule", arguments)!!
        assertEquals(4, text.lines().size)
        assertTrue(text.contains("原时间：${arguments.getValue("expected_planned_at")}"))
        assertTrue(text.contains("新时间：${arguments.getValue("planned_at")}"))
        assertTrue(text.contains("时区：Asia/Shanghai"))
        assertFalse(text.contains("one-time-v1"))
        assertNull(taskRescheduleApprovalText("tasks.pause", arguments))
    }

    @Test fun unverifiedOrMultipleMutationsCannotBecomeTrustedCompletion() {
        assertNull(presentTaskScheduleControlCompletion(context.copy(toolExecutions = listOf(execution.copy(verificationStatus = AgentVerificationStatus.READABLE_ONLY)))))
        assertNull(presentTaskScheduleControlCompletion(context.copy(toolExecutions = listOf(execution, execution))))
        assertNull(presentTaskScheduleControlCompletion(context.copy(toolExecutions = listOf(execution.copy(rawResult = result + "模型补充")))))
    }

    @Test fun navigationRequiresExactApprovedTimeAndTypedVerification() {
        val part = MessagePart.Tool(id = "part", toolName = execution.toolName, arguments = arguments, result = result, success = true, verificationStatus = MessageToolVerificationStatus.VERIFIED, memoryIdsUsed = emptyList())
        assertEquals("喝水提醒", part.inspectedTaskNameForNavigation())
        assertNull(part.copy(arguments = arguments + ("planned_at" to "2026-09-08T11:00:00+08:00")).inspectedTaskNameForNavigation())
        assertNull(part.copy(verificationStatus = MessageToolVerificationStatus.READABLE_ONLY).inspectedTaskNameForNavigation())
        assertNull(part.copy(success = false).inspectedTaskNameForNavigation())
    }
}
