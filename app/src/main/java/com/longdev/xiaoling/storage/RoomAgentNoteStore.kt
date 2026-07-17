package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.AgentNoteRecord
import com.longdev.xiaoling.agent.AgentNoteStore
import com.longdev.xiaoling.data.AgentNoteEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import java.util.UUID

class RoomAgentNoteStore(
    context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
) : AgentNoteStore {
    override suspend fun list(limit: Int): List<AgentNoteRecord> {
        return database.agentNoteDao()
            .list(limit.coerceIn(1, 10))
            .map { it.toRecord() }
    }

    override suspend fun search(query: String, limit: Int): List<AgentNoteRecord> {
        val pattern = query.trim().takeIf { it.isNotBlank() }?.let { "%$it%" } ?: return emptyList()
        return database.agentNoteDao()
            .search(pattern = pattern, limit = limit.coerceIn(1, 10))
            .map { it.toRecord() }
    }

    override suspend fun create(title: String, content: String): AgentNoteRecord {
        val now = System.currentTimeMillis()
        val note = AgentNoteRecord(
            id = "note-${UUID.randomUUID()}",
            title = title,
            content = content,
            createdAt = now,
            updatedAt = now,
        )
        // long: notes.create 是本地可验证写入工具，落库后 Runtime 会立刻 get(id) 回读，避免仅凭写入动作成功就告诉用户笔记已创建。
        database.agentNoteDao().upsertNote(note.toEntity())
        return note
    }

    override suspend fun get(id: String): AgentNoteRecord? {
        return database.agentNoteDao().getNote(id)?.toRecord()
    }

    private fun AgentNoteRecord.toEntity() = AgentNoteEntity(
        id = id,
        title = title,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun AgentNoteEntity.toRecord() = AgentNoteRecord(
        id = id,
        title = title,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
