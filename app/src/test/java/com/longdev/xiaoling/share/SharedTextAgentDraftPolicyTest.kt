package com.longdev.xiaoling.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTextAgentDraftPolicyTest {
    @Test
    fun sharedTextBecomesExplicitAgentNoteDraftWithoutChangingBody() {
        val sharedText = "第一行\n第二行"

        assertEquals(
            "/agent 使用 notes.create 将以下分享文本保存为一条本机笔记。请根据正文生成简洁标题，并完整保留正文内容：\n\n第一行\n第二行",
            SharedTextAgentDraftPolicy.createNoteDraft(sharedText),
        )
    }

    @Test
    fun blankSharedTextCannotCreateAgentDraft() {
        assertNull(SharedTextAgentDraftPolicy.createNoteDraft(" \n "))
        assertNull(SharedTextAgentDraftPolicy.createMemoryDraft(" \n "))
        assertNull(SharedTextAgentDraftPolicy.createCalendarEventDraft(" \n "))
        assertNull(SharedTextAgentDraftPolicy.createAllDayCalendarEventDraft(" \n "))
    }

    @Test
    fun sharedTextBecomesExplicitAgentMemoryDraftWithoutChangingBody() {
        val sharedText = "我偏好紧凑界面\n回答请先给结论"

        assertEquals(
            "/agent 使用 memory.remember 将以下分享文本保存为一条长期记忆。请完整保留正文，不补充或推断未提供的事实；只在用户批准后写入，并选择最合适的记忆类型与少量标签：\n\n我偏好紧凑界面\n回答请先给结论",
            SharedTextAgentDraftPolicy.createMemoryDraft(sharedText),
        )
    }

    @Test
    fun explicitCalendarFieldsBecomeEditableAgentDraftWithoutGuessing() {
        val sharedText = """
            标题：项目评审
            开始：2026-08-12T09:00:00+08:00
            结束：2026-08-12T09:30:00+08:00
            时区：Asia/Shanghai
        """.trimIndent()

        assertEquals(
            """
                /agent 使用 calendar.create_event 创建一条一次性非全天系统日程。只能使用以下四个明确参数，不得补充、改写或推断；发送后仍需逐次审批，审批通过后必须由当前 Calendar Provider 回读验证：
                title：项目评审
                start_at：2026-08-12T09:00:00+08:00
                end_at：2026-08-12T09:30:00+08:00
                time_zone：Asia/Shanghai
            """.trimIndent(),
            SharedTextAgentDraftPolicy.createCalendarEventDraft(sharedText),
        )
    }

    @Test
    fun calendarDraftRequiresEveryFieldExactlyOnce() {
        val fields = listOf(
            "标题：项目评审",
            "开始：2026-08-12T09:00:00+08:00",
            "结束：2026-08-12T09:30:00+08:00",
            "时区：Asia/Shanghai",
        )

        fields.indices.forEach { omittedIndex ->
            assertNull(
                "缺少任一字段都不得生成日程草稿",
                SharedTextAgentDraftPolicy.createCalendarEventDraft(
                    fields.filterIndexed { index, _ -> index != omittedIndex }.joinToString("\n"),
                ),
            )
        }
        assertNull(
            SharedTextAgentDraftPolicy.createCalendarEventDraft(
                (fields + "标题：重复标题").joinToString("\n"),
            ),
        )
    }

    @Test
    fun calendarDraftRejectsInvalidTimeOrderAndZoneOffset() {
        assertNull(
            SharedTextAgentDraftPolicy.createCalendarEventDraft(
                """
                    title: project review
                    start_at: 2026-08-12T09:30:00+08:00
                    end_at: 2026-08-12T09:00:00+08:00
                    time_zone: Asia/Shanghai
                """.trimIndent(),
            ),
        )
        assertNull(
            SharedTextAgentDraftPolicy.createCalendarEventDraft(
                """
                    标题：项目评审
                    开始：2026-08-12T09:00:00Z
                    结束：2026-08-12T09:30:00Z
                    时区：Asia/Shanghai
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun explicitAllDayCalendarFieldsBecomeEditableAgentDraft() {
        val sharedText = """
            标题：团队纪念日
            日期：2026-08-15
        """.trimIndent()

        assertEquals(
            """
                /agent 使用 calendar.create_all_day_event 创建一条一次性单日全天系统日程。只能使用以下两个明确参数，不得补充、改写或推断；发送后仍需逐次审批，审批通过后必须由当前 Calendar Provider 回读验证：
                title：团队纪念日
                date：2026-08-15
            """.trimIndent(),
            SharedTextAgentDraftPolicy.createAllDayCalendarEventDraft(sharedText),
        )
    }

    @Test
    fun allDayCalendarDraftRequiresTitleAndCanonicalDateExactlyOnce() {
        assertNull(SharedTextAgentDraftPolicy.createAllDayCalendarEventDraft("标题：团队纪念日"))
        assertNull(SharedTextAgentDraftPolicy.createAllDayCalendarEventDraft("日期：2026-08-15"))
        assertNull(
            SharedTextAgentDraftPolicy.createAllDayCalendarEventDraft(
                "标题：团队纪念日\n日期：2026-08-15\n日期：2026-08-16",
            ),
        )
        assertNull(
            SharedTextAgentDraftPolicy.createAllDayCalendarEventDraft(
                "标题：团队纪念日\n日期：2026-8-15",
            ),
        )
    }

    @Test
    fun allDayCalendarDraftRejectsTimedEventFieldsInsteadOfDroppingThem() {
        assertNull(
            SharedTextAgentDraftPolicy.createAllDayCalendarEventDraft(
                """
                    标题：项目评审
                    日期：2026-08-15
                    开始：2026-08-15T09:00:00+08:00
                    结束：2026-08-15T09:30:00+08:00
                    时区：Asia/Shanghai
                """.trimIndent(),
            ),
        )
    }
}
