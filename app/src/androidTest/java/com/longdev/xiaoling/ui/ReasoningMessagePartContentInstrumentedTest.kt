package com.longdev.xiaoling.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageReasoningSource
import com.longdev.xiaoling.ui.theme.XiaoLingTheme
import org.junit.Rule
import org.junit.Test

class ReasoningMessagePartContentInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun providerReasoningSummaryIsCollapsedByDefaultAndCanBeExpandedAndCollapsed() {
        composeRule.setContent {
            XiaoLingTheme {
                ReasoningMessagePartContent(
                    part = MessagePart.Reasoning(
                        id = "reasoning-ui-test",
                        text = "先核对事实，再回答。",
                        source = MessageReasoningSource.PROVIDER_SUMMARY,
                        providerItemId = "rs-ui-test",
                    ),
                    contentColor = Color.Black,
                )
            }
        }

        composeRule.onNodeWithText("推理摘要").assertExists()
        composeRule.onNodeWithText("供应商提供").assertExists()
        composeRule.onNodeWithText("先核对事实，再回答。", useUnmergedTree = true).assertDoesNotExist()

        composeRule.onNodeWithContentDescription("展开推理摘要").performClick()
        // long: Markdown 会先显示 loading 占位再异步生成语义节点；等待正文出现后再断言，避免把解析时序误判为展开失败。
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("先核对事实，再回答。", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("先核对事实，再回答。", useUnmergedTree = true).assertExists()

        composeRule.onNodeWithContentDescription("收起推理摘要").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("先核对事实，再回答。", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.onNodeWithText("先核对事实，再回答。", useUnmergedTree = true).assertDoesNotExist()
    }
}
