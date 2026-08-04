package com.longdev.xiaoling.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalTaskPlanContextUsagePresentationTest {
    @Test
    fun `context usage presents actual bytes and omitted source counts`() {
        assertEquals(
            "计划上下文：长期记忆 2 条 · 本地知识 1 个片段 · 占用 6.0KB\n" +
                "上下文精简：省略长期记忆 1 条 · 本地知识 2 个片段",
            presentPersonalTaskPlanContextUsage(
                memoryUsedCount = 2,
                memoryOmittedCount = 1,
                knowledgeUsedCount = 1,
                knowledgeOmittedCount = 2,
                contextBytes = 6_144,
            ),
        )
    }

    @Test
    fun `context usage omits optimization claim when nothing was removed`() {
        assertEquals(
            "计划上下文：长期记忆 1 条 · 本地知识 0 个片段 · 占用 128B",
            presentPersonalTaskPlanContextUsage(
                memoryUsedCount = 1,
                memoryOmittedCount = 0,
                knowledgeUsedCount = 0,
                knowledgeOmittedCount = 0,
                contextBytes = 128,
            ),
        )
    }
}
