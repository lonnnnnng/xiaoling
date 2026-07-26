package com.longdev.xiaoling.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.longdev.xiaoling.model.DocumentAttachmentPolicy
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.ui.conversation.DocumentMessagePartContent
import com.longdev.xiaoling.ui.theme.XiaoLingTheme
import org.junit.Rule
import org.junit.Test

class DocumentMessagePartContentInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun validatedDocumentPartRendersFileAndBudgetMetadata() {
        val part = MessagePart.Document(
            id = "document-ui-test",
            attachment = DocumentAttachmentPolicy.create(
                fileName = "notes.md",
                mimeType = "text/markdown",
                data = "stage 27 notes".toByteArray(),
            ),
        )

        composeRule.setContent {
            XiaoLingTheme { DocumentMessagePartContent(part, Color.Black) }
        }

        composeRule.onNodeWithText("notes.md").assertExists()
        composeRule.onNodeWithText("text/markdown", substring = true).assertExists()
        composeRule.onNodeWithText("14 字符", substring = true).assertExists()
    }
}
