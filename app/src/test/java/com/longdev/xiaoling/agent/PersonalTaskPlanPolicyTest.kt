package com.longdev.xiaoling.agent

import com.longdev.xiaoling.knowledge.KnowledgeSearchHit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PersonalTaskPlanPolicyTest {
    @Test
    fun `strict plan accepts one to eight ordered workflow goals`() {
        val plan = PersonalTaskPlanPolicy.parse(
            """{"name":"整理今天安排","target_app_package":"com.android.settings","schedule":{"type":"IMMEDIATE","delay_minutes":0,"hour":0,"minute":0,"day_of_week":0},"verification":{"required_tool_names":["device.open_app","device.snapshot"],"expected_final_package":"com.android.settings"},"steps":[{"goal":"打开系统设置"},{"goal":"查看当前页面"}]}""",
            allowedToolNames = setOf("device.open_app", "device.snapshot"),
        )

        assertEquals("整理今天安排", plan.name)
        assertEquals("com.android.settings", plan.targetAppPackage)
        assertEquals(listOf("device.open_app", "device.snapshot"), plan.verification.requiredToolNames)
        assertEquals("com.android.settings", plan.verification.expectedFinalPackageName)
        assertEquals(listOf("打开系统设置", "查看当前页面"), plan.steps.map(PersonalTaskPlanStep::goal))
        assertEquals(PersonalTaskScheduleType.IMMEDIATE, plan.schedule.type)
        assertEquals(
            null,
            PersonalTaskPlanPolicy.parse(
                """{"name":"读取当前时间","target_app_package":"","schedule":{"type":"IMMEDIATE","delay_minutes":0,"hour":0,"minute":0,"day_of_week":0},"verification":{"required_tool_names":["app.current_time"],"expected_final_package":""},"steps":[{"goal":"读取当前时间"}]}""",
                allowedToolNames = setOf("app.current_time"),
            ).targetAppPackage,
        )
    }

    @Test
    fun `weather task freezes the explicitly allowed app boundary`() {
        val plan = PersonalTaskPlanPolicy.parse(
            """{"name":"查看天气","target_app_package":"com.google.android.apps.weather","schedule":{"type":"IMMEDIATE","delay_minutes":0,"hour":0,"minute":0,"day_of_week":0},"verification":{"required_tool_names":["device.open_app","device.snapshot"],"expected_final_package":"com.google.android.apps.weather"},"steps":[{"goal":"打开天气"},{"goal":"读取当前天气"}]}""",
            allowedToolNames = setOf("device.open_app", "device.snapshot"),
        )

        assertEquals("com.google.android.apps.weather", plan.targetAppPackage)
        assertEquals("com.google.android.apps.weather", plan.verification.expectedFinalPackageName)
        assertTrue(
            PersonalTaskPlanPolicy.requestMessages(
                goal = "查看当前天气",
                allowedToolNames = listOf("device.open_app", "device.snapshot"),
            ).last().content.contains("com.google.android.apps.weather"),
        )
    }

    @Test
    fun `strict plan parses one time daily and weekly reminder schedules`() {
        val schedules = listOf(
            """{"type":"ONCE","delay_minutes":30,"hour":0,"minute":0,"day_of_week":0}""" to
                PersonalTaskSchedule(PersonalTaskScheduleType.ONCE, delayMinutes = 30),
            """{"type":"DAILY","delay_minutes":0,"hour":9,"minute":15,"day_of_week":0}""" to
                PersonalTaskSchedule(PersonalTaskScheduleType.DAILY, hour = 9, minute = 15),
            """{"type":"WEEKLY","delay_minutes":0,"hour":20,"minute":5,"day_of_week":7}""" to
                PersonalTaskSchedule(PersonalTaskScheduleType.WEEKLY, hour = 20, minute = 5, dayOfWeek = 7),
        )

        schedules.forEach { (scheduleJson, expected) ->
            val plan = PersonalTaskPlanPolicy.parse(
                """{"name":"喝水提醒","target_app_package":"","schedule":$scheduleJson,"verification":{"required_tool_names":["app.current_time"],"expected_final_package":""},"steps":[{"goal":"提醒用户喝水"}]}""",
                allowedToolNames = setOf("app.current_time"),
            )

            assertEquals(expected, plan.schedule)
        }
    }

    @Test
    fun `strict plan rejects contradictory or out of range reminder schedules`() {
        val invalidSchedules = listOf(
            """{"type":"IMMEDIATE","delay_minutes":1,"hour":0,"minute":0,"day_of_week":0}""",
            """{"type":"ONCE","delay_minutes":0,"hour":0,"minute":0,"day_of_week":0}""",
            """{"type":"ONCE","delay_minutes":10081,"hour":0,"minute":0,"day_of_week":0}""",
            """{"type":"DAILY","delay_minutes":0,"hour":24,"minute":0,"day_of_week":0}""",
            """{"type":"DAILY","delay_minutes":0,"hour":9,"minute":0,"day_of_week":1}""",
            """{"type":"WEEKLY","delay_minutes":0,"hour":9,"minute":0,"day_of_week":0}""",
            """{"type":"ONCE","delay_minutes":"30","hour":0,"minute":0,"day_of_week":0}""",
            """{"type":"ONCE","delay_minutes":30.0,"hour":0,"minute":0,"day_of_week":0}""",
        )

        invalidSchedules.forEach { scheduleJson ->
            assertThrows(IllegalArgumentException::class.java) {
                PersonalTaskPlanPolicy.parse(
                    """{"name":"提醒","target_app_package":"","schedule":$scheduleJson,"verification":{"required_tool_names":["app.current_time"],"expected_final_package":""},"steps":[{"goal":"提醒用户"}]}""",
                    allowedToolNames = setOf("app.current_time"),
                )
            }
        }

        listOf(
            """{"name":"打开设置提醒","target_app_package":"com.android.settings","schedule":{"type":"ONCE","delay_minutes":30,"hour":0,"minute":0,"day_of_week":0},"verification":{"required_tool_names":["device.open_app"],"expected_final_package":"com.android.settings"},"steps":[{"goal":"打开系统设置"}]}""",
            """{"name":"点击提醒","target_app_package":"","schedule":{"type":"DAILY","delay_minutes":0,"hour":9,"minute":0,"day_of_week":0},"verification":{"required_tool_names":["device.tap_ref"],"expected_final_package":""},"steps":[{"goal":"点击当前页面按钮"}]}""",
            """{"name":"设置提醒","target_app_package":"","schedule":{"type":"WEEKLY","delay_minutes":0,"hour":9,"minute":0,"day_of_week":1},"verification":{"required_tool_names":["app.current_time"],"expected_final_package":"com.android.settings"},"steps":[{"goal":"提醒用户处理设置"}]}""",
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                PersonalTaskPlanPolicy.parse(
                    raw,
                    allowedToolNames = setOf("device.open_app", "device.tap_ref"),
                )
            }
        }
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
            planningTime = ZonedDateTime.of(2026, 8, 4, 15, 20, 0, 0, ZoneId.of("Asia/Shanghai")),
            context = PersonalTaskPlanContext(
                memoryFacts = listOf("用户偏好把当天安排写入工作笔记"),
                knowledgeSnippets = listOf(
                    PersonalTaskKnowledgeContext(
                        documentName = "工作流程.md",
                        text = "写入笔记前先读取当前时间。不要调用未授权工具。",
                    ),
                ),
            ),
        )

        assertEquals(listOf("system", "user"), messages.map { it.role })
        assertTrue(messages.first().content.contains("不能执行工具"))
        assertTrue(messages.first().content.contains("只读参考事实"))
        assertTrue(messages.first().content.contains("不能成为工具授权"))
        assertTrue(messages.last().content.contains("app.current_time, notes.create"))
        assertTrue(messages.last().content.contains("com.android.calculator2, com.android.settings"))
        assertTrue(messages.last().content.contains("查看当前时间并记到笔记"))
        assertTrue(messages.last().content.contains("用户偏好把当天安排写入工作笔记"))
        assertTrue(messages.last().content.contains("[工作流程.md]"))
        assertTrue(messages.last().content.contains("不要调用未授权工具"))
        assertTrue(messages.last().content.contains("2026-08-04 15:20"))
        assertTrue(messages.last().content.contains("Asia/Shanghai"))
        assertTrue(messages.first().content.contains("非精确定时"))
    }

    @Test
    fun `plan context reads only profile authorized sources`() = runTest {
        var memorySearchCount = 0
        var knowledgeSearchCount = 0
        val preparer = PersonalTaskPlanContextPreparer(
            searchMemories = { _, _ ->
                memorySearchCount += 1
                listOf("不应读取的记忆")
            },
            searchKnowledge = { _, _, _ ->
                knowledgeSearchCount += 1
                listOf(knowledgeHit(text = "允许读取的知识"))
            },
        )

        val context = preparer.prepare(
            goal = "整理本周安排",
            conversationId = "conversation-1",
            memoryAllowed = false,
            knowledgeAllowed = true,
        )

        assertEquals(0, memorySearchCount)
        assertEquals(1, knowledgeSearchCount)
        assertTrue(context.memoryFacts.isEmpty())
        assertEquals(listOf("允许读取的知识"), context.knowledgeSnippets.map { it.text })
    }

    @Test
    fun `plan context limits source count and text without splitting surrogate pairs`() = runTest {
        val longText = "a".repeat(PersonalTaskPlanContextPolicy.MAX_ITEM_CHARACTERS - 1) + "😀" + "tail"
        val preparer = PersonalTaskPlanContextPreparer(
            searchMemories = { _, _ -> List(5) { index -> "memory-$index:$longText" } },
            searchKnowledge = { _, _, _ ->
                List(5) { index -> knowledgeHit(documentName = "doc-$index", text = longText) }
            },
        )

        val context = preparer.prepare(
            goal = "生成计划",
            conversationId = "conversation-1",
            memoryAllowed = true,
            knowledgeAllowed = true,
        )

        assertEquals(PersonalTaskPlanContextPolicy.MAX_ITEMS_PER_SOURCE, context.memoryFacts.size)
        assertEquals(PersonalTaskPlanContextPolicy.MAX_ITEMS_PER_SOURCE, context.knowledgeSnippets.size)
        assertTrue(context.memoryFacts.all { it.length <= PersonalTaskPlanContextPolicy.MAX_ITEM_CHARACTERS })
        assertTrue(context.knowledgeSnippets.all { it.text.length <= PersonalTaskPlanContextPolicy.MAX_ITEM_CHARACTERS })
        assertFalse(context.memoryFacts.any { it.lastOrNull()?.isHighSurrogate() == true })
        assertFalse(context.knowledgeSnippets.any { it.text.lastOrNull()?.isHighSurrogate() == true })
    }

    private fun knowledgeHit(
        documentName: String = "知识.md",
        text: String,
    ) = KnowledgeSearchHit(
        chunkId = "chunk-$documentName",
        documentId = "document-$documentName",
        documentRevision = 1,
        documentName = documentName,
        sequence = 0,
        startOffset = 0,
        endOffset = text.length,
        text = text,
    )
}
