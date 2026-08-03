package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalTaskPlanPolicyTest {
    @Test
    fun `strict plan accepts one to eight ordered workflow goals`() {
        val plan = PersonalTaskPlanPolicy.parse(
            """{"name":"整理今天安排","steps":[{"goal":"读取当前时间"},{"goal":"汇总今天的待办"}]}""",
        )

        assertEquals("整理今天安排", plan.name)
        assertEquals(listOf("读取当前时间", "汇总今天的待办"), plan.steps.map(PersonalTaskPlanStep::goal))
    }

    @Test
    fun `strict plan rejects wrappers unknown fields and invalid step counts`() {
        val invalidPlans = listOf(
            """```json
                {"name":"计划","steps":[{"goal":"读取时间"}]}
                ```""".trimIndent(),
            """{"name":"计划","steps":[{"goal":"读取时间"}],"extra":true}""",
            """{"name":"计划","steps":[]}""",
            """{"name":"计划","steps":${List(9) { "{\"goal\":\"步骤 ${it + 1}\"}" }}}""",
            """{"name":"计划","steps":[{"goal":" "}]}""",
            """{"name":"计划","steps":[{"goal":"读取时间","tool":"app.current_time"}]}""",
            """{"steps":[{"goal":"读取时间"}]}""",
            """{"name":"计划","steps":"读取时间"}""",
        )

        invalidPlans.forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                PersonalTaskPlanPolicy.parse(raw)
            }
        }
    }

    @Test
    fun `planning prompt freezes allowed tool boundary without authorizing execution`() {
        val messages = PersonalTaskPlanPolicy.requestMessages(
            goal = "查看当前时间并记到笔记",
            allowedToolNames = listOf("notes.create", "app.current_time"),
        )

        assertEquals(listOf("system", "user"), messages.map { it.role })
        assertTrue(messages.first().content.contains("不能执行工具"))
        assertTrue(messages.last().content.contains("app.current_time, notes.create"))
        assertTrue(messages.last().content.contains("查看当前时间并记到笔记"))
    }
}
