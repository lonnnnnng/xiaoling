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
                        agentActionEnabled = true,
                        onCreateAgentNoteDraft = { converted += 1 },
                    )
                }
            }
        }

        composeRule.onNodeWithText("来自外部应用的分享").assertIsDisplayed()
        composeRule.onNodeWithText("打开分享").performClick()
        composeRule.onNodeWithContentDescription("忽略分享").performClick()
        composeRule.onNodeWithText("保存为笔记").performClick()
        composeRule.runOnIdle {
            assertEquals(1, opened)
            assertEquals(1, discarded)
            assertEquals(1, converted)
        }
        composeRule.onNodeWithText("已从外部分享导入").assertIsDisplayed()
    }
}
