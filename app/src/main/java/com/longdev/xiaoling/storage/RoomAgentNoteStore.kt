package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.withTransaction
import com.longdev.xiaoling.agent.AgentNoteIdempotencyConflictException
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

    override suspend fun create(title: String, content: String, idempotencyKey: String): AgentNoteRecord {
        require(idempotencyKey.isNotBlank()) { "笔记幂等键不能为空" }
        require(idempotencyKey.length <= 200) { "笔记幂等键不能超过 200 个字符" }
        return database.withTransaction {
            val dao = database.agentNoteDao()
            val existing = dao.getNoteByIdempotencyKey(idempotencyKey)
            if (existing != null) {
                return@withTransaction existing.requireSamePayload(title, content).toRecord()
            }
            val now = System.currentTimeMillis()
            val note = AgentNoteRecord(
                id = "note-${UUID.randomUUID()}",
                title = title,
                content = content,
                createdAt = now,
                updatedAt = now,
            )
            // long: 查询与插入位于同一 Room 事务；进程重建后的同键重放只能命中原记录，唯一索引同时阻止并发路径写出第二条笔记。
            dao.insertNote(note.toEntity(idempotencyKey))
            note
        }
    }

    override suspend fun get(id: String): AgentNoteRecord? {
        return database.agentNoteDao().getNote(id)?.toRecord()
    }

    private fun AgentNoteRecord.toEntity(idempotencyKey: String) = AgentNoteEntity(
        id = id,
        title = title,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt,
        idempotencyKey = idempotencyKey,
    )

    private fun AgentNoteEntity.toRecord() = AgentNoteRecord(
        id = id,
        title = title,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun AgentNoteEntity.requireSamePayload(
        title: String,
        content: String,
    ): AgentNoteEntity {
        if (this.title != title || this.content != content) {
            throw AgentNoteIdempotencyConflictException()
        }
        return this
    }
}
