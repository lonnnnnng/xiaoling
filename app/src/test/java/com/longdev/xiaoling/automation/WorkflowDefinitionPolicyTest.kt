package com.longdev.xiaoling.automation

import com.longdev.xiaoling.agent.AgentRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkflowDefinitionPolicyTest {
    @Test
    fun validDefinitionPasses() {
        WorkflowDefinitionPolicy.validate("每日回顾", "回顾最近会话并读取当前时间")
    }

    @Test
    fun blankNameAndOversizedGoalAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowDefinitionPolicy.validate(" ", "读取时间")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowDefinitionPolicy.validate("每日回顾", "x".repeat(WorkflowDefinitionPolicy.MAX_GOAL_LENGTH + 1))
        }
    }

    @Test
    fun agentTerminalStatusMappingIsSharedAndActiveStatusDoesNotSettle() {
        assertEquals(
            WorkflowRunStatus.COMPLETED,
            WorkflowAgentRunStatusPolicy.terminalStatus(AgentRunStatus.COMPLETED),
        )
        assertEquals(
            WorkflowRunStatus.FAILED,
            WorkflowAgentRunStatusPolicy.terminalStatus(AgentRunStatus.BUDGET_EXHAUSTED),
        )
        assertNull(WorkflowAgentRunStatusPolicy.terminalStatus(AgentRunStatus.WAITING_APPROVAL))
    }
}
