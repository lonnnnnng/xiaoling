package com.longdev.xiaoling.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityUserNotice
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityUserState
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeReferenceAvailability
import com.longdev.xiaoling.knowledge.KnowledgeReferenceIssue
import com.longdev.xiaoling.knowledge.KnowledgeReferenceStatus
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceUserNotice
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
        val openedReference = AtomicReference<KnowledgeReference?>(null)

        composeRule.setContent {
            XiaoLingTheme {
                KnowledgeReferencesContent(
                    messageId = "message-reference-ui",
                    references = listOf(currentReference, historicalReference),
                    statuses = statuses,
                    contentColor = Color.Black,
                    onOpenDocument = openedReference::set,
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
        assertEquals(null, openedReference.get())

        composeRule.onNodeWithContentDescription("打开知识原文 当前手册.md").assertExists()
        composeRule.onNodeWithTag("knowledge-reference-document-current-ui").assertTextContains("当前手册.md")
        composeRule.onNodeWithTag("knowledge-reference-document-current-ui").performClick()
        assertEquals(currentReference, openedReference.get())
    }

    @Test
    fun relevanceNoticeRemainsVisibleWhenNoReferenceIsRetained() {
        composeRule.setContent {
            XiaoLingTheme {
                KnowledgeReferencesContent(
                    messageId = "message-no-reliable-knowledge",
                    references = emptyList(),
                    statuses = emptyMap(),
                    relevanceNotice = KnowledgeRelevanceUserNotice(
                        title = "未找到足够可靠的本地知识",
                        detail = "语义候选相关性不足，且没有关键词命中。",
                    ),
                    contentColor = Color.Black,
                    onOpenDocument = {},
                )
            }
        }

        composeRule.onNodeWithText("未找到足够可靠的本地知识").assertExists()
        composeRule.onNodeWithText("语义候选相关性不足，且没有关键词命中。").assertExists()
        composeRule.onNodeWithText("知识引用 · 0").assertDoesNotExist()
    }

    @Test
    fun answerabilityShadowNoticeCoexistsWithRetainedReference() {
        val reference = reference(
            retrievalId = "retrieval-answerability-shadow-ui",
            documentId = "document-answerability-shadow-ui",
            documentName = "答案依据.md",
            revision = 1,
            chunkSequence = 0,
        )
        composeRule.setContent {
            XiaoLingTheme {
                KnowledgeReferencesContent(
                    messageId = "message-answerability-shadow-ui",
                    references = listOf(reference),
                    statuses = emptyMap(),
                    answerabilityNotice = KnowledgeAnswerabilityUserNotice(
                        state = KnowledgeAnswerabilityUserState.PARTIALLY_ANSWERED,
                        title = "本地知识仅覆盖部分问题",
                        detail = "候选文档只回答了部分要点；当前仍保留原引用，不据此删除答案。",
                    ),
                    contentColor = Color.Black,
                    onOpenDocument = {},
                )
            }
        }

        composeRule.onNodeWithTag("knowledge-answerability-notice").assertExists()
        composeRule.onNodeWithText("本地知识仅覆盖部分问题").assertExists()
        composeRule.onNodeWithText("知识引用 · 1").assertExists()
        composeRule.onNodeWithContentDescription("展开知识引用").performClick()
        composeRule.onNodeWithText("答案依据.md").assertExists()
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
