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
    fun multiStepDefinitionAcceptsOrderedAgentGoals() {
        WorkflowDefinitionPolicy.validate(
            name = "会话回顾",
            steps = listOf(
                WorkflowStepDefinitionInput("读取当前时间"),
                WorkflowStepDefinitionInput("列出最近会话"),
                WorkflowStepDefinitionInput("根据前两步结果生成回顾"),
            ),
        )
    }

    @Test
    fun multiStepDefinitionRejectsEmptyTooManyAndBlankSteps() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowDefinitionPolicy.validate("空工作流", emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowDefinitionPolicy.validate(
                "步骤过多",
                List(WorkflowDefinitionPolicy.MAX_STEPS + 1) { WorkflowStepDefinitionInput("步骤 ${it + 1}") },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkflowDefinitionPolicy.validate(
                "空步骤",
                listOf(WorkflowStepDefinitionInput("读取时间"), WorkflowStepDefinitionInput(" ")),
            )
        }
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
        assertEquals(
            WorkflowRunStatus.BLOCKED,
            WorkflowAgentRunStatusPolicy.terminalStatus(AgentRunStatus.BLOCKED),
        )
        assertNull(WorkflowAgentRunStatusPolicy.terminalStatus(AgentRunStatus.WAITING_APPROVAL))
    }
}
