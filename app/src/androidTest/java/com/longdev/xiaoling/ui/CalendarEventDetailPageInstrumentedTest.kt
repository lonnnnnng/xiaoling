package com.longdev.xiaoling.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.longdev.xiaoling.agent.CalendarEventDetailRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CalendarEventDetailPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun currentProviderProjectionShowsOnlyBoundedReadOnlyFields() {
        val events = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                CalendarEventDetailContent(
                    state = CalendarEventDetailLoadState.Content(
                        CalendarEventDetailRecord(
                            eventId = 197L,
                            title = "项目评审",
                            startAtMillis = 1_754_631_600_000L,
                            endAtMillis = 1_754_635_200_000L,
                            allDay = false,
                            timeZoneId = "Asia/Shanghai",
                            recurring = false,
                        ),
                    ),
                    onBack = { events += "back" },
                )
            }
        }

        composeRule.onNodeWithText("项目评审").assertExists()
        composeRule.onNodeWithText("calendar-197").assertExists()
        composeRule.onNodeWithText("Asia/Shanghai").assertExists()
        composeRule.onNodeWithText("以上内容来自当前系统 Calendar Provider 的只读回读，不包含地点、描述、参与人或账户信息。").assertExists()
        composeRule.onNodeWithContentDescription("返回设置").performClick()

        composeRule.runOnIdle { assertEquals(listOf("back"), events) }
    }

    @Test
    fun deletedOrUnavailableEventDoesNotRenderOldSummary() {
        composeRule.setContent {
            MaterialTheme {
                CalendarEventDetailContent(
                    state = CalendarEventDetailLoadState.Error("当前日程已不存在或已被删除"),
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("当前日程已不存在或已被删除").assertExists()
        composeRule.onNodeWithText("项目评审").assertDoesNotExist()
        composeRule.onNodeWithText("以上内容来自当前系统 Calendar Provider 的只读回读，不包含地点、描述、参与人或账户信息。").assertDoesNotExist()
    }
}
