package com.longdev.xiaoling.agent

import org.junit.Assert.*
import org.junit.Test

class MemoryMaintenancePolicyTest {
    @Test fun originalUserStatementsRemainSuggestionsAndTaskCommandsAreIgnored() {
        assertNull(MemoryMaintenancePolicy.suggest("查看今日任务", "conversation", "run"))
        val draft = requireNotNull(MemoryMaintenancePolicy.suggest("/agent 我喜欢中文回答", "conversation", "run"))
        assertEquals("我喜欢中文回答", draft.content)
        assertEquals(AgentMemoryCandidateStatus.PENDING, draft.status)
        assertEquals("run", draft.source.runId)
    }

    @Test fun sensitiveOriginalIsNotCopiedToSuggestionOrSourceSummary() {
        val draft = requireNotNull(MemoryMaintenancePolicy.suggest("请记住 api_key=sk-fictional123456789012345", "conversation", "run"))
        assertEquals(AgentMemoryCandidateStatus.BLOCKED_SENSITIVE, draft.status)
        assertEquals("", draft.content)
        assertEquals("", draft.normalizedContent)
        assertFalse(draft.source.summary.contains("fictional"))
    }

    @Test fun utf8BudgetRejectsOverLimitWithoutTruncatingStatement() {
        assertNull(MemoryMaintenancePolicy.suggest("a".repeat(8192), "c", "r"))
        assertTrue(runCatching { MemoryMaintenancePolicy.suggest("中".repeat(3000), "c", "r") }.isFailure)
    }

    @Test fun lengthFramingSeparatesAmbiguousSourceValues() {
        assertNotEquals(MemoryMaintenancePolicy.hash("ab", "c"), MemoryMaintenancePolicy.hash("a", "bc"))
        assertEquals(MemoryMaintenancePolicy.hash("固定"), MemoryMaintenancePolicy.hash("固定"))
    }
}
