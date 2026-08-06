package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.withTransaction
import com.longdev.xiaoling.agent.AgentNoteDeletedException
import com.longdev.xiaoling.agent.AgentNoteIdempotencyConflictException
import com.longdev.xiaoling.agent.AgentNoteManagementStore
import com.longdev.xiaoling.agent.AgentNoteRecord
import com.longdev.xiaoling.data.AgentNoteEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import java.util.UUID

class RoomAgentNoteStore(
    context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
) : AgentNoteManagementStore {
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
                if (existing.isDeletedTombstone()) throw AgentNoteDeletedException()
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

    override suspend fun delete(id: String): Boolean {
        require(id.isNotBlank()) { "笔记 ID 不能为空" }
        return database.withTransaction {
            val dao = database.agentNoteDao()
            if (dao.getNote(id) == null) return@withTransaction false
            // long: 用户删除只清空正文并保留原 ID/幂等键；历史 ToolCall 即使重放也只能命中 tombstone 并失败，不能恢复已撤回内容。
            val changed = dao.tombstoneNote(id = id, updatedAt = System.currentTimeMillis())
            check(changed == 1 && dao.getNote(id) == null) { "笔记删除后回读验证失败" }
            true
        }
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

    private fun AgentNoteEntity.isDeletedTombstone(): Boolean = title.isEmpty() && content.isEmpty()

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
