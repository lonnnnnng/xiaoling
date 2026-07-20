package com.longdev.xiaoling.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeReferenceAvailability
import com.longdev.xiaoling.knowledge.KnowledgeReferenceIssue
import com.longdev.xiaoling.knowledge.KnowledgeReferenceStatus
import com.longdev.xiaoling.ui.theme.XiaoLingTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class KnowledgeReferencesContentInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun referencesAreCollapsedThenExposeStatusLocationAndDocumentNavigation() {
        val currentReference = reference(
            retrievalId = "retrieval-current-ui",
            documentId = "document-current-ui",
            documentName = "当前手册.md",
            revision = 2,
            chunkSequence = 1,
        )
        val historicalReference = reference(
            retrievalId = "retrieval-history-ui",
            documentId = "document-history-ui",
            documentName = "历史规则.md",
            revision = 1,
            chunkSequence = 0,
        )
        val statuses = mapOf(
            currentReference to status(
                currentReference,
                KnowledgeReferenceAvailability.CURRENT,
                KnowledgeReferenceIssue.NONE,
                currentName = "当前手册.md",
                currentRevision = 2,
            ),
            historicalReference to status(
                historicalReference,
                KnowledgeReferenceAvailability.HISTORICAL,
                KnowledgeReferenceIssue.DOCUMENT_REPLACED,
                currentName = "历史规则-v2.md",
                currentRevision = 2,
            ),
        )
        val openedDocumentId = AtomicReference<String?>(null)

        composeRule.setContent {
            XiaoLingTheme {
                KnowledgeReferencesContent(
                    messageId = "message-reference-ui",
                    references = listOf(currentReference, historicalReference),
                    statuses = statuses,
                    contentColor = Color.Black,
                    onOpenDocument = openedDocumentId::set,
                )
            }
        }

        composeRule.onNodeWithText("知识引用 · 2").assertExists()
        composeRule.onNodeWithText("当前手册.md").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("展开知识引用").performClick()
        composeRule.onNodeWithText("当前手册.md").assertExists()
        composeRule.onNodeWithText("当前有效").assertExists()
        composeRule.onNodeWithText("revision 2 · chunk 1 · offset [20, 44)").assertExists()
        composeRule.onNodeWithText("历史规则.md").assertExists()
        composeRule.onNodeWithText("历史版本").assertExists()
        composeRule.onNodeWithText("当前为 revision 2 · 历史规则-v2.md").assertExists()
        assertEquals(null, openedDocumentId.get())

        composeRule.onNodeWithContentDescription("打开知识文档 当前手册.md").assertExists()
        composeRule.onNodeWithTag("knowledge-reference-document-current-ui").assertTextContains("当前手册.md")
        composeRule.onNodeWithTag("knowledge-reference-document-current-ui").performClick()
        assertEquals("document-current-ui", openedDocumentId.get())
    }

    private fun status(
        reference: KnowledgeReference,
        availability: KnowledgeReferenceAvailability,
        issue: KnowledgeReferenceIssue,
        currentName: String,
        currentRevision: Int,
    ) = KnowledgeReferenceStatus(
        reference = reference,
        availability = availability,
        issue = issue,
        currentDocumentName = currentName,
        currentDocumentRevision = currentRevision,
        currentDocumentEnabled = true,
    )

    private fun reference(
        retrievalId: String,
        documentId: String,
        documentName: String,
        revision: Int,
        chunkSequence: Int,
    ) = KnowledgeReference(
        retrievalId = retrievalId,
        documentId = documentId,
        documentName = documentName,
        documentRevision = revision,
        chunkId = "$documentId-r$revision-$chunkSequence",
        chunkSequence = chunkSequence,
        startOffset = 20,
        endOffset = 44,
    )
}
