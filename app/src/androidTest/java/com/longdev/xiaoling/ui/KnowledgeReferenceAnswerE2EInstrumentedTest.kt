package com.longdev.xiaoling.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.agent.VerifiedAgentContextCodec
import com.longdev.xiaoling.model.MessageOrigin
import com.longdev.xiaoling.storage.ConversationRepository
import com.longdev.xiaoling.storage.RoomKnowledgeDocumentStore
import com.longdev.xiaoling.storage.RoomStateStore
import com.longdev.xiaoling.storage.StoredConversation
import com.longdev.xiaoling.storage.StoredConversationMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

class KnowledgeReferenceAnswerE2EInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val composeRule = createAndroidComposeRule<MainActivity>()
    private lateinit var knowledgeStore: RoomKnowledgeDocumentStore
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var documentId: String

    private val seedRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                seedAnswer()
                try {
                    base.evaluate()
                } finally {
                    cleanupAnswer()
                }
            }
        }
    }

    @get:Rule
    val ruleChain: TestRule = RuleChain.outerRule(seedRule).around(composeRule)

    @Test
    fun answerCitationTracksCurrentHistoricalAndDeletedDocumentAndNavigatesToDetail() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("知识引用 · 1").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("知识引用 · 1").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("当前有效").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(DOCUMENT_NAME_V1).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("文档详情 · $DOCUMENT_NAME_V1").fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking {
            knowledgeStore.replaceUtf8Document(
                documentId = documentId,
                displayName = DOCUMENT_NAME_V2,
                mimeType = "text/markdown",
                bytes = "新版本要求旧答案明确标记为历史引用。".toByteArray(),
            )
        }
        returnToConversation()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("历史版本").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("当前为 revision 2 · $DOCUMENT_NAME_V2").assertExists()

        composeRule.onNodeWithText(DOCUMENT_NAME_V1).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("文档详情 · $DOCUMENT_NAME_V2").fetchSemanticsNodes().isNotEmpty()
        }
        runBlocking { assertNotNull(knowledgeStore.getDocument(documentId)); knowledgeStore.delete(documentId) }
        returnToConversation()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("当前不可用").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("文档已删除").assertExists()
        composeRule.onNodeWithContentDescription("打开知识文档 $DOCUMENT_NAME_V1").assertDoesNotExist()
    }

    private fun returnToConversation() {
        composeRule.onNodeWithContentDescription("返回设置").performClick()
        composeRule.onNodeWithTag("bottom_tab_conversation").performClick()
    }

    private fun seedAnswer() = runBlocking {
        knowledgeStore = RoomKnowledgeDocumentStore(context)
        conversationRepository = ConversationRepository(context)
        RoomStateStore(context).markConversationsMigrated()
        val document = knowledgeStore.importUtf8Document(
            displayName = DOCUMENT_NAME_V1,
            mimeType = "text/markdown",
            bytes = "答案级引用必须展示当前状态并支持知识库跳转。".toByteArray(),
        )
        documentId = document.id
        val reference = knowledgeStore.search("答案级引用", 1).hits.single().let { hit ->
            com.longdev.xiaoling.knowledge.KnowledgeReference(
                retrievalId = "retrieval-answer-e2e",
                documentId = hit.documentId,
                documentName = hit.documentName,
                documentRevision = hit.documentRevision,
                chunkId = hit.chunkId,
                chunkSequence = hit.sequence,
                startOffset = hit.startOffset,
                endOffset = hit.endOffset,
            )
        }
        val now = System.currentTimeMillis()
        val verifiedContext = VerifiedAgentContext(
            runId = "run-answer-e2e",
            toolName = "knowledge.search",
            arguments = mapOf("query" to "答案级引用"),
            success = true,
            verificationStatus = AgentVerificationStatus.READABLE_ONLY,
            rawResult = "找到答案级引用验收资料",
            knowledgeReferences = listOf(reference),
        )
        val conversation = StoredConversation(
            id = CONVERSATION_ID,
            title = "引用 UI 验收",
            summary = "",
            summaryUntilMessageId = null,
            summaryUpdatedAt = null,
            summaryModel = null,
            messages = listOf(
                StoredConversationMessage(
                    id = "message-answer-e2e-user",
                    role = "user",
                    text = "/agent 验证答案引用",
                    createdAt = now,
                    origin = MessageOrigin.USER.name,
                    verifiedAgentContext = null,
                    meta = null,
                ),
                StoredConversationMessage(
                    id = "message-answer-e2e-agent",
                    role = "assistant",
                    text = "答案引用 UI 已连接结构化知识证据。",
                    createdAt = now + 1,
                    origin = MessageOrigin.AGENT_RESULT.name,
                    verifiedAgentContext = VerifiedAgentContextCodec.encode(verifiedContext),
                    meta = null,
                ),
            ),
            createdAt = now,
            updatedAt = now + 1,
        )
        conversationRepository.save(listOf(conversation), conversation.id)
    }

    private fun cleanupAnswer() = runBlocking {
        if (::knowledgeStore.isInitialized && ::documentId.isInitialized) {
            knowledgeStore.delete(documentId)
        }
        if (::conversationRepository.isInitialized) {
            val stored = conversationRepository.load()
            val remaining = stored.conversations.filterNot { it.id == CONVERSATION_ID }
            conversationRepository.save(
                conversations = remaining,
                selectedConversationId = remaining.firstOrNull()?.id.orEmpty(),
                deletedConversationIds = setOf(CONVERSATION_ID),
            )
        }
    }

    companion object {
        private const val CONVERSATION_ID = "conversation-answer-reference-e2e"
        private const val DOCUMENT_NAME_V1 = "答案引用验收.md"
        private const val DOCUMENT_NAME_V2 = "答案引用验收-v2.md"
    }
}
