package com.longdev.xiaoling.storage

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.agent.VerifiedAgentContextCodec
import com.longdev.xiaoling.agent.VerifiedToolExecution
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.model.DocumentAttachmentPolicy
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.ImageAttachmentPolicy
import com.longdev.xiaoling.model.MessageReasoningSource
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMessagePartStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var isolatedContext: Context
    private var database: XiaoLingDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        isolatedContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                context.getSharedPreferences("$TEST_PREFERENCES_PREFIX$name", mode)
        }
        context.deleteDatabase(DATABASE_NAME)
        clearTestPreferences()
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(DATABASE_NAME)
        clearTestPreferences()
    }

    @Test
    fun textAndToolPartsRoundTripAcrossDatabaseReopen() = runBlocking {
        val knowledgeReference = KnowledgeReference(
            retrievalId = "knowledge-retrieval-part",
            documentId = "document-part",
            documentName = "消息知识.md",
            documentRevision = 3,
            chunkId = "chunk-part-r3-0",
            chunkSequence = 0,
            startOffset = 0,
            endOffset = 32,
        )
        val parts = listOf(
            MessagePart.Text(id = "part-text", text = "Agent 任务已完成"),
            MessagePart.Tool(
                id = "part-tool",
                toolName = "memory.search",
                arguments = mapOf("query" to "偏好"),
                result = "找到 1 条记忆",
                success = true,
                verificationStatus = MessageToolVerificationStatus.VERIFIED,
                memoryIdsUsed = listOf("memory-1"),
                knowledgeReferences = listOf(knowledgeReference),
            ),
        )
        val stored = StoredConversationMessage(
            id = "message-parts",
            role = "assistant",
            text = "Agent 任务已完成",
            createdAt = 100L,
            origin = "AGENT_RESULT",
            verifiedAgentContext = VerifiedAgentContextCodec.encode(
                VerifiedAgentContext(
                    runId = "run-parts",
                    toolName = "memory.search",
                    arguments = mapOf("query" to "偏好"),
                    success = true,
                    verificationStatus = AgentVerificationStatus.VERIFIED,
                    rawResult = "找到 1 条记忆",
                    memoryIdsUsed = listOf("memory-1"),
                    knowledgeReferences = listOf(knowledgeReference),
                    toolExecutions = listOf(
                        VerifiedToolExecution(
                            toolName = "memory.search",
                            arguments = mapOf("query" to "偏好"),
                            success = true,
                            verificationStatus = AgentVerificationStatus.VERIFIED,
                            rawResult = "找到 1 条记忆",
                            memoryIdsUsed = listOf("memory-1"),
                            knowledgeReferences = listOf(knowledgeReference),
                        ),
                    ),
                ),
            ),
            meta = null,
            parts = parts,
        )

        openDatabase().let { first ->
            MessageRepository(first).replaceAll(listOf("conversation-parts" to stored))
            first.close()
            database = null
        }

        val restored = MessageRepository(openDatabase())
            .loadGroupedByConversation()
            .getValue("conversation-parts")
            .single()

        assertEquals("Agent 任务已完成", restored.text)
        assertEquals(parts, restored.parts)
    }

    @Test
    fun malformedKnowledgeReferenceJsonDoesNotBlockConversationLoad() = runBlocking {
        val stored = agentMessage(id = "message-malformed-knowledge", createdAt = 100L)
        val opened = openDatabase()
        MessageRepository(opened).replaceAll(listOf("conversation-malformed-knowledge" to stored))
        opened.openHelper.writableDatabase.execSQL(
            "UPDATE message_parts SET knowledgeReferencesJson = ? WHERE messageId = ? AND type = 'TOOL'",
            arrayOf("[{\"documentId\":\"broken\"}]", stored.id),
        )

        val restored = MessageRepository(opened)
            .loadGroupedByConversation()
            .getValue("conversation-malformed-knowledge")
            .single()

        val tool = restored.parts.filterIsInstance<MessagePart.Tool>().single()
        assertTrue(tool.knowledgeReferences.isEmpty())
    }

    @Test
    fun reasoningAndTextPartsRoundTripAcrossDatabaseReopen() = runBlocking {
        val parts = listOf(
            MessagePart.Reasoning(
                id = "part-reasoning-stable",
                text = "先核对输入，再组织答案。",
                source = MessageReasoningSource.PROVIDER_SUMMARY,
                providerItemId = "rs-room-1",
                summaryIndex = 2,
            ),
            MessagePart.Text(id = "part-reasoning-text", text = "最终答案"),
        )
        val stored = StoredConversationMessage(
            id = "message-reasoning-parts",
            role = "assistant",
            text = "最终答案",
            createdAt = 101L,
            origin = "ORDINARY_ASSISTANT",
            verifiedAgentContext = null,
            meta = null,
            parts = parts,
        )

        openDatabase().let { first ->
            MessageRepository(first).replaceAll(listOf("conversation-reasoning-parts" to stored))
            first.close()
            database = null
        }

        val restored = MessageRepository(openDatabase())
            .loadGroupedByConversation()
            .getValue("conversation-reasoning-parts")
            .single()

        assertEquals(parts, restored.parts)
    }

    @Test
    fun userImageAndTextPartsRoundTripAcrossDatabaseReopen() = runBlocking {
        val parts = listOf(
            MessagePart.Image(
                id = "part-image-stable",
                attachment = ImageAttachmentPolicy.create(
                    fileName = "receipt.png",
                    mimeType = "image/png",
                    data = byteArrayOf(
                        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3,
                    ),
                ),
            ),
            MessagePart.Text(id = "part-image-text", text = "识别图片中的金额"),
        )
        val stored = StoredConversationMessage(
            id = "message-image-parts",
            role = "user",
            text = "识别图片中的金额",
            createdAt = 102L,
            origin = "USER",
            verifiedAgentContext = null,
            meta = null,
            parts = parts,
        )

        openDatabase().let { first ->
            MessageRepository(first).replaceAll(listOf("conversation-image-parts" to stored))
            first.close()
            database = null
        }

        val restored = MessageRepository(openDatabase())
            .loadGroupedByConversation(setOf("conversation-image-parts"))
            .getValue("conversation-image-parts")
            .single()

        assertEquals(parts, restored.parts)
    }

    @Test
    fun userDocumentAndTextPartsRoundTripAcrossDatabaseReopen() = runBlocking {
        val parts = listOf(
            MessagePart.Document(
                id = "part-document-stable",
                attachment = DocumentAttachmentPolicy.create(
                    fileName = "notes.md",
                    mimeType = "text/markdown",
                    data = "Room document facts".toByteArray(),
                ),
            ),
            MessagePart.Text(id = "part-document-text", text = "总结文档"),
        )
        val stored = StoredConversationMessage(
            id = "message-document-parts",
            role = "user",
            text = "总结文档",
            createdAt = 103L,
            origin = "USER",
            verifiedAgentContext = null,
            meta = null,
            parts = parts,
        )

        openDatabase().let { first ->
            MessageRepository(first).replaceAll(listOf("conversation-document-parts" to stored))
            first.close()
            database = null
        }

        val restored = MessageRepository(openDatabase())
            .loadGroupedByConversation(setOf("conversation-document-parts"))
            .getValue("conversation-document-parts")
            .single()

        assertEquals(parts, restored.parts)
    }

    @Test
    fun onlySelectedConversationLoadsBinaryBlobAndLightweightSavePreservesOtherAttachment() = runBlocking {
        val repository = ConversationRepository(
            context = isolatedContext,
            database = openDatabase(),
            stateStore = RoomStateStore(isolatedContext).also { it.markConversationsMigrated() },
            legacyStore = ConversationStore(isolatedContext),
            messageRepository = MessageRepository(requireNotNull(database)),
        )
        val first = imageConversation("conversation-image-first", "message-image-first", 100L)
        val second = documentConversation("conversation-document-second", "message-document-second", 200L)
        repository.save(listOf(first, second), first.id)

        val firstLoad = repository.load()
        assertTrue(
            firstLoad.conversations.first { it.id == first.id }.messages.single().parts.any { it is MessagePart.Image },
        )
        assertTrue(
            firstLoad.conversations.first { it.id == second.id }.messages.single().parts.none { it is MessagePart.Document },
        )

        repository.save(firstLoad.conversations, second.id)
        val secondLoad = repository.load()
        assertTrue(
            secondLoad.conversations.first { it.id == first.id }.messages.single().parts.none { it is MessagePart.Image },
        )
        assertTrue(
            secondLoad.conversations.first { it.id == second.id }.messages.single().parts.any { it is MessagePart.Document },
        )
    }

    @Test
    fun foregroundStaleSnapshotKeepsBackgroundAppendedMessageAndParts() = runBlocking {
        val database = openDatabase()
        val stateStore = RoomStateStore(isolatedContext).also { it.markConversationsMigrated() }
        val repository = ConversationRepository(
            context = isolatedContext,
            database = database,
            stateStore = stateStore,
            legacyStore = ConversationStore(isolatedContext),
            messageRepository = MessageRepository(database),
        )
        val foregroundMessage = ordinaryMessage(id = "message-foreground", text = "前台消息", createdAt = 100L)
        val conversation = StoredConversation(
            id = "conversation-interleaved",
            title = "交错写入",
            summary = "",
            summaryUntilMessageId = null,
            summaryUpdatedAt = null,
            summaryModel = null,
            messages = listOf(foregroundMessage),
            createdAt = 100L,
            updatedAt = 100L,
        )
        repository.save(listOf(conversation), conversation.id)
        val staleForegroundSnapshot = repository.load()

        val backgroundMessage = ordinaryMessage(id = "message-background", text = "后台结果", createdAt = 200L)
        MessageRepository(database).insert(listOf(conversation.id to backgroundMessage))
        repository.save(staleForegroundSnapshot.conversations, staleForegroundSnapshot.selectedConversationId)

        val restoredMessages = repository.load().conversations.single().messages
        assertEquals(listOf("message-foreground", "message-background"), restoredMessages.map { it.id })
        assertTrue(restoredMessages.single { it.id == backgroundMessage.id }.parts.single() is MessagePart.Text)
    }

    @Test
    fun foregroundStaleSnapshotKeepsBackgroundConversationAndOnlyDeletesExplicitIds() = runBlocking {
        val database = openDatabase()
        val stateStore = RoomStateStore(isolatedContext).also { it.markConversationsMigrated() }
        val repository = ConversationRepository(
            context = isolatedContext,
            database = database,
            stateStore = stateStore,
            legacyStore = ConversationStore(isolatedContext),
            messageRepository = MessageRepository(database),
        )
        val foreground = conversation(id = "conversation-foreground", title = "前台", createdAt = 100L)
        val toDelete = conversation(id = "conversation-delete", title = "待删除", createdAt = 110L)
        repository.save(listOf(foreground, toDelete), foreground.id)
        val staleForegroundSnapshot = repository.load()

        val backgroundConversationId = "conversation-background"
        database.conversationDao().insertConversations(
            listOf(
                ConversationEntity(
                    id = backgroundConversationId,
                    title = "定时 · 后台任务",
                    summary = "",
                    summaryUntilMessageId = null,
                    summaryUpdatedAt = null,
                    summaryModel = null,
                    createdAt = 200L,
                    updatedAt = 200L,
                ),
            ),
        )
        MessageRepository(database).insert(
            listOf(backgroundConversationId to agentMessage(id = "message-background-agent", createdAt = 200L)),
        )

        repository.save(
            // long: 故意传入仍包含已删会话的陈旧快照，验证 Repository 以显式删除 ID 为准且不会在同一事务中重新插入。
            conversations = staleForegroundSnapshot.conversations,
            selectedConversationId = foreground.id,
            deletedConversationIds = setOf(toDelete.id),
        )

        val restored = repository.load().conversations.associateBy { it.id }
        assertEquals(setOf(foreground.id, backgroundConversationId), restored.keys)
        val backgroundParts = restored.getValue(backgroundConversationId).messages.single().parts
        assertEquals(2, backgroundParts.size)
        assertTrue(backgroundParts[0] is MessagePart.Text)
        assertTrue(backgroundParts[1] is MessagePart.Tool)
    }

    private fun conversation(id: String, title: String, createdAt: Long) = StoredConversation(
        id = id,
        title = title,
        summary = "",
        summaryUntilMessageId = null,
        summaryUpdatedAt = null,
        summaryModel = null,
        messages = listOf(ordinaryMessage(id = "message-$id", text = title, createdAt = createdAt)),
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun imageConversation(id: String, messageId: String, createdAt: Long): StoredConversation {
        val text = "识别 $id"
        return StoredConversation(
            id = id,
            title = id,
            summary = "",
            summaryUntilMessageId = null,
            summaryUpdatedAt = null,
            summaryModel = null,
            messages = listOf(
                StoredConversationMessage(
                    id = messageId,
                    role = "user",
                    text = text,
                    createdAt = createdAt,
                    origin = "USER",
                    verifiedAgentContext = null,
                    meta = null,
                    parts = listOf(
                        MessagePart.Image(
                            id = "$messageId-image-0",
                            attachment = ImageAttachmentPolicy.create(
                                fileName = "$messageId.png",
                                mimeType = "image/png",
                                data = byteArrayOf(
                                    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1,
                                ),
                            ),
                        ),
                        MessagePart.Text(id = "$messageId-text", text = text),
                    ),
                ),
            ),
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }

    private fun documentConversation(id: String, messageId: String, createdAt: Long): StoredConversation {
        val text = "总结 $id"
        return StoredConversation(
            id = id,
            title = id,
            summary = "",
            summaryUntilMessageId = null,
            summaryUpdatedAt = null,
            summaryModel = null,
            messages = listOf(
                StoredConversationMessage(
                    id = messageId,
                    role = "user",
                    text = text,
                    createdAt = createdAt,
                    origin = "USER",
                    verifiedAgentContext = null,
                    meta = null,
                    parts = listOf(
                        MessagePart.Document(
                            id = "$messageId-document-0",
                            attachment = DocumentAttachmentPolicy.create(
                                fileName = "$messageId.md",
                                mimeType = "text/markdown",
                                data = "document facts".toByteArray(),
                            ),
                        ),
                        MessagePart.Text(id = "$messageId-text", text = text),
                    ),
                ),
            ),
            createdAt = createdAt,
            updatedAt = createdAt,
        )
    }

    private fun agentMessage(id: String, createdAt: Long) = StoredConversationMessage(
        id = id,
        role = "assistant",
        text = "后台 Agent 已完成",
        createdAt = createdAt,
        origin = "AGENT_RESULT",
        verifiedAgentContext = VerifiedAgentContextCodec.encode(
            VerifiedAgentContext(
                runId = "run-background",
                toolName = "app.current_time",
                arguments = emptyMap(),
                success = true,
                verificationStatus = AgentVerificationStatus.READABLE_ONLY,
                rawResult = "2026-07-19 12:30:00 · Asia/Shanghai",
            ),
        ),
        meta = null,
    )

    private fun ordinaryMessage(id: String, text: String, createdAt: Long) = StoredConversationMessage(
        id = id,
        role = "assistant",
        text = text,
        createdAt = createdAt,
        origin = "ORDINARY_ASSISTANT",
        verifiedAgentContext = null,
        meta = null,
    )

    private fun clearTestPreferences() {
        listOf(ROOM_STATE_PREFERENCES, CONVERSATION_PREFERENCES).forEach { name ->
            context.getSharedPreferences("$TEST_PREFERENCES_PREFIX$name", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun openDatabase(): XiaoLingDatabase {
        return Room.databaseBuilder(context, XiaoLingDatabase::class.java, DATABASE_NAME)
            .addMigrations(*XiaoLingDatabase.migrations())
            .build()
            .also { database = it }
    }

    companion object {
        private const val DATABASE_NAME = "xiaoling-message-parts-test.db"
        private const val ROOM_STATE_PREFERENCES = "xiaoling_room_state"
        private const val CONVERSATION_PREFERENCES = "xiaoling_conversations"
        private const val TEST_PREFERENCES_PREFIX = "message_parts_test_"
    }
}
