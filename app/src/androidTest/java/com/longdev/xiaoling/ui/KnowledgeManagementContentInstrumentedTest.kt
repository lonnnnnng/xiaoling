package com.longdev.xiaoling.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.longdev.xiaoling.knowledge.KnowledgeDocumentDetail
import com.longdev.xiaoling.knowledge.KnowledgeDocumentSummary
import com.longdev.xiaoling.knowledge.KnowledgeRetrievalRecord
import com.longdev.xiaoling.knowledge.KnowledgeSearchHit
import com.longdev.xiaoling.ui.theme.XiaoLingTheme
import org.junit.Rule
import org.junit.Test

class KnowledgeManagementContentInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun documentDetailAndSearchCitationAreVisible() {
        val summary = KnowledgeDocumentSummary(
            id = "knowledge-ui",
            displayName = "handbook.md",
            mimeType = "text/markdown",
            contentHash = "0123456789abcdef",
            revision = 2,
            parserVersion = 1,
            byteSize = 1_024,
            characterCount = 800,
            enabled = true,
            createdAt = 1L,
            updatedAt = 2L,
            chunkCount = 2,
        )
        val state = KnowledgeManagementUiState(
            documents = listOf(summary),
            selectedDocumentId = summary.id,
            selectedDocument = KnowledgeDocumentDetail(
                id = summary.id,
                displayName = summary.displayName,
                mimeType = summary.mimeType,
                contentHash = summary.contentHash,
                revision = summary.revision,
                parserVersion = summary.parserVersion,
                byteSize = summary.byteSize,
                characterCount = summary.characterCount,
                previewText = "知识库正文预览",
                previewTruncated = true,
                enabled = summary.enabled,
                createdAt = summary.createdAt,
                updatedAt = summary.updatedAt,
            ),
            searchQuery = "正文",
            searchHits = listOf(
                KnowledgeSearchHit(
                    chunkId = "chunk-ui",
                    documentId = summary.id,
                    documentRevision = 2,
                    documentName = summary.displayName,
                    sequence = 1,
                    startOffset = 20,
                    endOffset = 36,
                    text = "命中的正文片段",
                ),
            ),
            lastRetrieval = KnowledgeRetrievalRecord(
                id = "knowledge-retrieval-ui",
                query = "正文",
                chunkIds = listOf("chunk-ui"),
                documentIds = listOf(summary.id),
                sourceConversationId = null,
                sourceRunId = null,
                createdAt = 1_720_000_000_000L,
            ),
        )

        composeRule.setContent {
            XiaoLingTheme {
                KnowledgeManagementContent(
                    state = state,
                    onBack = {},
                    onImport = {},
                    onRefresh = {},
                    onSearchQueryChanged = {},
                    onSearch = {},
                    onSelectDocument = {},
                    onSetEnabled = { _, _ -> },
                    onReplace = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("知识库").assertExists()
        composeRule.onNodeWithText("文档详情 · handbook.md").assertExists()
        composeRule.onNodeWithText("revision 2 · parser 1 · 2 个分块").assertExists()
        composeRule.onNodeWithText("命中 1 个分块", substring = true).assertExists()
        composeRule.onNodeWithText("审计 knowledge-retrieval-ui", substring = true).assertExists()
        composeRule.onNodeWithText("query：正文", substring = true).assertExists()
        composeRule.onNodeWithText("offset 20..36", substring = true).assertExists()
        composeRule.onNodeWithText("知识库正文预览", substring = true).assertExists()
        composeRule.onNodeWithText("仅显示前 4,000 个字符", substring = true).assertExists()
    }
}
