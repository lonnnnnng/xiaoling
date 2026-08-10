package com.longdev.xiaoling.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.foundation.layout.Column
import com.longdev.xiaoling.share.SharedDraftPayload
import com.longdev.xiaoling.ui.conversation.SharedDraftPendingNotice
import com.longdev.xiaoling.ui.conversation.SharedDraftSourceLabel
import com.longdev.xiaoling.ui.theme.XiaoLingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SharedDraftNoticeInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sharedDraftShowsSourceAndConflictActions() {
        var opened = 0
        var discarded = 0
        var converted = 0
        var memoryConverted = 0
        var calendarConverted = 0
        var allDayCalendarConverted = 0
        var taskConverted = 0
        val payload = SharedDraftPayload(
            text = "待处理文本",
            imageUri = null,
        )

        composeRule.setContent {
            XiaoLingTheme {
                Column {
                    SharedDraftPendingNotice(
                        payload = payload,
                        enabled = true,
                        onOpen = { opened += 1 },
                        onDiscard = { discarded += 1 },
                    )
                    SharedDraftSourceLabel(
                        noteActionEnabled = true,
                        memoryActionEnabled = true,
                        calendarActionEnabled = true,
                        allDayCalendarActionEnabled = true,
                        taskActionEnabled = true,
                        onCreateAgentNoteDraft = { converted += 1 },
                        onCreateAgentMemoryDraft = { memoryConverted += 1 },
                        onCreateAgentCalendarEventDraft = { calendarConverted += 1 },
                        onCreateAgentAllDayCalendarEventDraft = { allDayCalendarConverted += 1 },
                        onCreatePersonalTaskDraft = { taskConverted += 1 },
                    )
                }
            }
        }

        composeRule.onNodeWithText("来自外部应用的分享").assertIsDisplayed()
        composeRule.onNodeWithText("打开分享").performClick()
        composeRule.onNodeWithContentDescription("忽略分享").performClick()
        composeRule.onNodeWithText("转为任务").performClick()
        composeRule.onNodeWithText("保存为笔记").performClick()
        composeRule.onNodeWithText("保存为记忆").performClick()
        composeRule.onNodeWithText("创建日程").performClick()
        composeRule.onNodeWithText("创建全天日程").performClick()
        composeRule.runOnIdle {
            assertEquals(1, opened)
            assertEquals(1, discarded)
            assertEquals(1, converted)
            assertEquals(1, memoryConverted)
            assertEquals(1, calendarConverted)
            assertEquals(1, allDayCalendarConverted)
            assertEquals(1, taskConverted)
        }
        composeRule.onNodeWithText("已从外部分享导入").assertIsDisplayed()
    }
}
