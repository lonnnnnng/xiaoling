package com.longdev.xiaoling.automation

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
}
