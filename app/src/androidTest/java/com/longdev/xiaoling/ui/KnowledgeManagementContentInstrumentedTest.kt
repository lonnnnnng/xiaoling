package com.longdev.xiaoling.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.longdev.xiaoling.knowledge.KnowledgeDocumentDetail
import com.longdev.xiaoling.knowledge.KnowledgeDocumentSummary
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingIndexSummary
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingStatus
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
            selectedEmbeddingIndexes = listOf(
                KnowledgeEmbeddingIndexSummary(
                    providerId = "provider-ui",
                    model = "text-embedding-ui",
                    documentRevision = 2,
                    dimensions = 1_536,
                    chunkCount = 2,
                    updatedAt = 3L,
                ),
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
                embeddingProviderId = "provider-ui",
                embeddingModel = "text-embedding-ui",
                embeddingStatus = KnowledgeEmbeddingStatus.USED,
                embeddingTopScore = 0.812345,
                embeddingSecondScore = 0.701234,
                embeddingScoreMargin = 0.111111,
                embeddingCandidateCount = 12,
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
                    onRebuildEmbeddings = {},
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
        composeRule.onNodeWithText("Embedding：语义融合 · provider-ui / text-embedding-ui").assertExists()
        composeRule.onNodeWithText("校准观测：12 个语义候选 · top1 0.8123 · top2 0.7012 · margin 0.1111").assertExists()
        composeRule.onNodeWithText("offset 20..36", substring = true).assertExists()
        composeRule.onNodeWithText("知识库正文预览", substring = true).assertExists()
        composeRule.onNodeWithText("仅显示前 4,000 个字符", substring = true).assertExists()
        composeRule.onNodeWithText("provider-ui · text-embedding-ui · 1536 维 · 2 个分块").assertExists()
        composeRule.onNodeWithContentDescription("重建 Embedding 索引").assertExists()
    }

    @Test
    fun missingEmbeddingIndexShowsLexicalFallback() {
        val summary = KnowledgeDocumentSummary(
            id = "knowledge-no-index",
            displayName = "legacy.txt",
            mimeType = "text/plain",
            contentHash = "legacy-hash",
            revision = 1,
            parserVersion = 1,
            byteSize = 4,
            characterCount = 4,
            enabled = true,
            createdAt = 1L,
            updatedAt = 1L,
            chunkCount = 1,
        )
        composeRule.setContent {
            XiaoLingTheme {
                KnowledgeManagementContent(
                    state = KnowledgeManagementUiState(
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
                            previewText = "正文",
                            previewTruncated = false,
                            enabled = true,
                            createdAt = 1L,
                            updatedAt = 1L,
                        ),
                    ),
                    onBack = {},
                    onImport = {},
                    onRefresh = {},
                    onSearchQueryChanged = {},
                    onSearch = {},
                    onSelectDocument = {},
                    onSetEnabled = { _, _ -> },
                    onRebuildEmbeddings = {},
                    onReplace = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("Embedding：尚未建立，检索将使用词法兜底").assertExists()
        composeRule.onNodeWithContentDescription("重建 Embedding 索引").assertExists()
    }

    @Test
    fun lexicalRetrievalDoesNotShowEmptyEmbeddingIdentity() {
        setRetrievalDiagnosticContent(
            status = KnowledgeEmbeddingStatus.LEXICAL_ONLY,
            providerId = " ",
            model = null,
        )

        composeRule.onNodeWithText("Embedding：仅词法").assertExists()
        composeRule.onNodeWithText("Embedding：仅词法 ·", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("校准观测：", substring = true).assertDoesNotExist()
    }

    @Test
    fun missingIndexRetrievalShowsFallbackReason() {
        setRetrievalDiagnosticContent(status = KnowledgeEmbeddingStatus.NO_INDEX)

        composeRule.onNodeWithText("Embedding：无可用索引，词法兜底").assertExists()
    }

    @Test
    fun unavailableProviderRetrievalShowsFallbackReason() {
        setRetrievalDiagnosticContent(status = KnowledgeEmbeddingStatus.PROVIDER_UNAVAILABLE)

        composeRule.onNodeWithText("Embedding：Provider 不可用，词法兜底").assertExists()
    }

    @Test
    fun dimensionMismatchRetrievalShowsFallbackReason() {
        setRetrievalDiagnosticContent(status = KnowledgeEmbeddingStatus.DIMENSION_MISMATCH)

        composeRule.onNodeWithText("Embedding：维度不匹配，词法兜底").assertExists()
    }

    private fun setRetrievalDiagnosticContent(
        status: KnowledgeEmbeddingStatus,
        providerId: String? = null,
        model: String? = null,
    ) {
        composeRule.setContent {
            XiaoLingTheme {
                KnowledgeManagementContent(
                    state = KnowledgeManagementUiState(
                        searchQuery = "诊断",
                        lastRetrieval = KnowledgeRetrievalRecord(
                            id = "knowledge-retrieval-diagnostic",
                            query = "诊断",
                            chunkIds = emptyList(),
                            documentIds = emptyList(),
                            sourceConversationId = null,
                            sourceRunId = null,
                            embeddingProviderId = providerId,
                            embeddingModel = model,
                            embeddingStatus = status,
                            createdAt = 1L,
                        ),
                    ),
                    onBack = {},
                    onImport = {},
                    onRefresh = {},
                    onSearchQueryChanged = {},
                    onSearch = {},
                    onSelectDocument = {},
                    onSetEnabled = { _, _ -> },
                    onRebuildEmbeddings = {},
                    onReplace = {},
                    onDelete = {},
                )
            }
        }

        composeRule.onNodeWithText("命中 0 个分块").assertExists()
        composeRule.onNodeWithText("审计 knowledge-retrieval-diagnostic", substring = true).assertExists()
    }
}
