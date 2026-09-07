package com.longdev.xiaoling.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.longdev.xiaoling.agent.AgentNotificationRecord
import com.longdev.xiaoling.ui.theme.XiaoLingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NotificationDetailPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun readableNotificationShowsExplicitPersonalTaskAction() {
        var selectedId: String? = null
        val notification = notification()
        composeRule.setContent {
            XiaoLingTheme {
                NotificationDetailContent(
                    state = NotificationDetailLoadState.Content(notification),
                    onBack = {},
                    onCreatePersonalTask = { selectedId = it },
                )
            }
        }

        composeRule.onNodeWithText("转为任务").performClick()
        composeRule.runOnIdle { assertEquals(notification.id, selectedId) }
    }

    @Test
    fun readableNotificationShowsExplicitNoteAction() {
        var selectedId: String? = null
        val notification = notification()
        composeRule.setContent {
            XiaoLingTheme {
                NotificationDetailContent(
                    state = NotificationDetailLoadState.Content(notification),
                    onBack = {},
                    onCreateNote = { selectedId = it },
                )
            }
        }

        composeRule.onNodeWithText("保存为笔记").performClick()
        composeRule.runOnIdle { assertEquals(notification.id, selectedId) }
    }

    @Test
    fun hiddenNotificationDoesNotExposePersonalTaskAction() {
        composeRule.setContent {
            XiaoLingTheme {
                NotificationDetailContent(
                    state = NotificationDetailLoadState.Content(notification(contentHidden = true)),
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("转为任务").assertDoesNotExist()
    }

    @Test
    fun emptyNotificationDoesNotExposePersonalTaskAction() {
        composeRule.setContent {
            XiaoLingTheme {
                NotificationDetailContent(
                    state = NotificationDetailLoadState.Content(notification(title = null, content = null)),
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText("转为任务").assertDoesNotExist()
    }

    @Test
    fun taskDraftActionIsDisabledWhileCurrentReadIsBeingConverted() {
        composeRule.setContent {
            XiaoLingTheme {
                NotificationDetailContent(
                    state = NotificationDetailLoadState.Content(notification()),
                    onBack = {},
                    taskDraftInProgress = true,
                )
            }
        }

        composeRule.onNodeWithText("正在准备草稿").assertIsNotEnabled()
    }

    private fun notification(
        title: String? = "待办提醒",
        content: String? = "查看本周任务",
        contentHidden: Boolean = false,
    ): AgentNotificationRecord = AgentNotificationRecord(
        id = "notification-${"a".repeat(64)}",
        appName = "示例应用",
        packageName = "com.example.app",
        postedAt = 1_723_888_000_000L,
        title = title,
        content = content,
        contentHidden = contentHidden,
    )
}
