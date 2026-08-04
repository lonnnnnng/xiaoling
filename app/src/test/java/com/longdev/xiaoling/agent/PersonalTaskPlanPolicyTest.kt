package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalTaskPlanPolicyTest {
    @Test
    fun `strict plan accepts one to eight ordered workflow goals`() {
        val plan = PersonalTaskPlanPolicy.parse(
            """{"name":"整理今天安排","target_app_package":"com.android.settings","steps":[{"goal":"打开系统设置"},{"goal":"查看当前页面"}]}""",
        )

        assertEquals("整理今天安排", plan.name)
        assertEquals("com.android.settings", plan.targetAppPackage)
        assertEquals(listOf("打开系统设置", "查看当前页面"), plan.steps.map(PersonalTaskPlanStep::goal))
        assertEquals(
            null,
            PersonalTaskPlanPolicy.parse(
                """{"name":"读取当前时间","target_app_package":"","steps":[{"goal":"读取当前时间"}]}""",
            ).targetAppPackage,
        )
    }

    @Test
    fun `strict plan rejects wrappers unknown fields and invalid step counts`() {
        val invalidPlans = listOf(
            """```json
                {"name":"计划","target_app_package":"","steps":[{"goal":"读取时间"}]}
                ```""".trimIndent(),
            """{"name":"计划","target_app_package":"","steps":[{"goal":"读取时间"}],"extra":true}""",
            """{"name":"计划","target_app_package":"","steps":[]}""",
            """{"name":"计划","target_app_package":"","steps":${List(9) { "{\"goal\":\"步骤 ${it + 1}\"}" }}}""",
            """{"name":"计划","target_app_package":"","steps":[{"goal":" "}]}""",
            """{"name":"计划","target_app_package":"","steps":[{"goal":"读取时间","tool":"app.current_time"}]}""",
            """{"target_app_package":"","steps":[{"goal":"读取时间"}]}""",
            """{"name":"计划","target_app_package":"","steps":"读取时间"}""",
            """{"name":"计划","steps":[{"goal":"读取时间"}]}""",
            """{"name":"计划","target_app_package":"com.example.unlisted","steps":[{"goal":"打开第三方应用"}]}""",
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
            allowedAppPackages = listOf("com.android.settings", "com.android.calculator2"),
        )

        assertEquals(listOf("system", "user"), messages.map { it.role })
        assertTrue(messages.first().content.contains("不能执行工具"))
        assertTrue(messages.last().content.contains("app.current_time, notes.create"))
        assertTrue(messages.last().content.contains("com.android.calculator2, com.android.settings"))
        assertTrue(messages.last().content.contains("查看当前时间并记到笔记"))
    }
}
