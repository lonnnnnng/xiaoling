package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.withTransaction
import com.longdev.xiaoling.agent.AgentNoteDeletedException
import com.longdev.xiaoling.agent.AgentNoteIdempotencyConflictException
import com.longdev.xiaoling.agent.AgentNoteManagementStore
import com.longdev.xiaoling.agent.AgentNoteRecord
import com.longdev.xiaoling.agent.AgentNoteUpdateIdempotencyConflictException
import com.longdev.xiaoling.agent.AgentNoteUpdateRequest
import com.longdev.xiaoling.agent.AgentNoteUpdateResult
import com.longdev.xiaoling.agent.AgentNoteUpdateVerification
import com.longdev.xiaoling.agent.AgentNoteUpdateVerificationFailure
import com.longdev.xiaoling.data.AgentNoteEditOperationEntity
import com.longdev.xiaoling.data.AgentNoteEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import java.security.MessageDigest
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
                revision = 1L,
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

    override suspend fun update(
        request: AgentNoteUpdateRequest,
        idempotencyKey: String,
    ): AgentNoteUpdateResult {
        require(idempotencyKey.isNotBlank()) { "笔记编辑幂等键不能为空" }
        require(idempotencyKey.length <= 200) { "笔记编辑幂等键不能超过 200 个字符" }
        require(request.noteId.isNotBlank()) { "笔记 ID 不能为空" }
        require(request.title.isNotBlank()) { "笔记标题不能为空" }
        require(request.content.isNotBlank()) { "笔记正文不能为空" }
        require(request.expectedRevision > 0L) { "笔记预期版本必须大于 0" }
        val payloadHash = updatePayloadHash(request)
        return database.withTransaction {
            val dao = database.agentNoteDao()
            dao.getEditOperation(idempotencyKey)?.let { operation ->
                if (operation.payloadHash != payloadHash || operation.noteId != request.noteId) {
                    throw AgentNoteUpdateIdempotencyConflictException()
                }
                val current = dao.getNote(request.noteId)?.toRecord()
                    ?: return@withTransaction AgentNoteUpdateResult.NotFound
                return@withTransaction if (
                    current.revision == operation.resultRevision &&
                    noteResultHash(current) == operation.resultHash
                ) {
                    AgentNoteUpdateResult.Updated(current)
                } else {
                    AgentNoteUpdateResult.RevisionConflict(current)
                }
            }

            val current = dao.getNote(request.noteId)?.toRecord()
                ?: return@withTransaction AgentNoteUpdateResult.NotFound
            if (current.revision != request.expectedRevision) {
                return@withTransaction AgentNoteUpdateResult.RevisionConflict(current)
            }
            if (current.title == request.title && current.content == request.content) {
                return@withTransaction AgentNoteUpdateResult.Unchanged(current)
            }
            val updatedAt = maxOf(System.currentTimeMillis(), current.updatedAt + 1L)
            // long: 标题、正文和 revision 在同一条件 UPDATE 中提交；审批等待期间若用户或另一个 Agent 已编辑，受影响行数为 0，旧内容不能被静默覆盖。
            val changed = dao.updateNoteIfRevisionMatches(
                id = request.noteId,
                title = request.title,
                content = request.content,
                updatedAt = updatedAt,
                expectedRevision = request.expectedRevision,
            )
            if (changed != 1) {
                return@withTransaction dao.getNote(request.noteId)
                    ?.toRecord()
                    ?.let(AgentNoteUpdateResult::RevisionConflict)
                    ?: AgentNoteUpdateResult.NotFound
            }
            val updated = checkNotNull(dao.getNote(request.noteId)?.toRecord()) {
                "笔记编辑提交后回读失败"
            }
            check(updated.revision == request.expectedRevision + 1L) { "笔记编辑版本递增失败" }
            // long: 编辑 operation 与正文更新位于同一事务；恢复只能按 ToolCall、载荷和结果 revision 回读，不能再次执行 UPDATE。
            dao.insertEditOperation(
                AgentNoteEditOperationEntity(
                    idempotencyKey = idempotencyKey,
                    noteId = request.noteId,
                    expectedRevision = request.expectedRevision,
                    resultRevision = updated.revision,
                    payloadHash = payloadHash,
                    resultHash = noteResultHash(updated),
                    createdAt = updatedAt,
                ),
            )
            AgentNoteUpdateResult.Updated(updated)
        }
    }

    override suspend fun verifyUpdateOperation(
        idempotencyKey: String,
        noteId: String,
        request: AgentNoteUpdateRequest,
    ): AgentNoteUpdateVerification {
        val operation = database.agentNoteDao().getEditOperation(idempotencyKey)
            ?: return AgentNoteUpdateVerification.Failed(AgentNoteUpdateVerificationFailure.OPERATION_NOT_FOUND)
        if (operation.noteId != noteId) {
            return AgentNoteUpdateVerification.Failed(AgentNoteUpdateVerificationFailure.OPERATION_MISMATCH)
        }
        if (operation.payloadHash != updatePayloadHash(request)) {
            return AgentNoteUpdateVerification.Failed(AgentNoteUpdateVerificationFailure.PAYLOAD_MISMATCH)
        }
        val current = database.agentNoteDao().getNote(noteId)?.toRecord()
            ?: return AgentNoteUpdateVerification.Failed(AgentNoteUpdateVerificationFailure.NOTE_NOT_FOUND)
        if (current.revision != operation.resultRevision || noteResultHash(current) != operation.resultHash) {
            return AgentNoteUpdateVerification.Failed(AgentNoteUpdateVerificationFailure.NOTE_CHANGED)
        }
        return AgentNoteUpdateVerification.Verified(current)
    }

    private fun AgentNoteRecord.toEntity(idempotencyKey: String) = AgentNoteEntity(
        id = id,
        title = title,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt,
        idempotencyKey = idempotencyKey,
        revision = revision,
    )

    private fun AgentNoteEntity.toRecord() = AgentNoteRecord(
        id = id,
        title = title,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt,
        revision = revision,
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

    private fun updatePayloadHash(request: AgentNoteUpdateRequest): String = sha256Canonical(
        listOf(request.noteId, request.expectedRevision.toString(), request.title, request.content),
    )

    private fun noteResultHash(note: AgentNoteRecord): String = sha256Canonical(
        listOf(note.id, note.revision.toString(), note.title, note.content),
    )

    private fun sha256Canonical(fields: List<String>): String {
        val bytes = buildString {
            fields.forEach { field -> append(field.length).append(':').append(field).append('|') }
        }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
