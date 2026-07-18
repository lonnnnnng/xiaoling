package com.longdev.xiaoling.storage

import android.content.Context
import android.util.AtomicFile
import com.longdev.xiaoling.agent.AgentMemoryRecord
import org.json.JSONObject
import java.io.File

internal class AgentMemoryDeleteUndoStore(
    context: Context,
    file: File = File(context.filesDir, FILE_NAME),
) {
    private val atomicFile = AtomicFile(file)

    fun save(memory: AgentMemoryRecord) {
        // long: 撤销快照必须先完整落盘再删除 Room 记录；AtomicFile 保留上一份完整文件，避免进程被杀时留下半段 JSON。
        val output = atomicFile.startWrite()
        try {
            output.write(memory.toJson().toString().toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    fun load(): AgentMemoryRecord? {
        if (!atomicFile.baseFile.exists()) return null
        // long: 快照只是撤销辅助数据，损坏或版本不兼容时应降级为不可撤销，不能阻断正式 Room 数据加载。
        return runCatching {
            atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { reader ->
                JSONObject(reader.readText()).toMemoryRecord()
            }
        }.getOrElse {
            atomicFile.delete()
            null
        }
    }

    fun clear(memoryId: String? = null) {
        if (memoryId != null && load()?.id != memoryId) return
        atomicFile.delete()
    }

    private fun AgentMemoryRecord.toJson() = JSONObject()
        .put("version", FORMAT_VERSION)
        .put("id", id)
        .put("content", content)
        .put("tags", tags)
        .put("type", type)
        .put("sourceConversationId", sourceConversationId ?: JSONObject.NULL)
        .put("sourceRunId", sourceRunId ?: JSONObject.NULL)
        .put("sourceSummary", sourceSummary)
        .put("confidence", confidence)
        .put("enabled", enabled)
        .put("createdAt", createdAt)
        .put("updatedAt", updatedAt)
        .put("pinned", pinned)
        .put("expiresAt", expiresAt ?: JSONObject.NULL)
        .put("lastReferencedAt", lastReferencedAt ?: JSONObject.NULL)

    private fun JSONObject.toMemoryRecord(): AgentMemoryRecord {
        require(getInt("version") == FORMAT_VERSION) { "不支持的记忆撤销快照版本" }
        return AgentMemoryRecord(
            id = getString("id"),
            content = getString("content"),
            tags = getString("tags"),
            type = getString("type"),
            sourceConversationId = nullableString("sourceConversationId"),
            sourceRunId = nullableString("sourceRunId"),
            sourceSummary = getString("sourceSummary"),
            confidence = getDouble("confidence"),
            enabled = getBoolean("enabled"),
            createdAt = getLong("createdAt"),
            updatedAt = getLong("updatedAt"),
            pinned = getBoolean("pinned"),
            expiresAt = nullableLong("expiresAt"),
            lastReferencedAt = nullableLong("lastReferencedAt"),
        )
    }

    private fun JSONObject.nullableString(key: String): String? {
        return if (isNull(key)) null else getString(key)
    }

    private fun JSONObject.nullableLong(key: String): Long? {
        return if (isNull(key)) null else getLong(key)
    }

    private companion object {
        const val FILE_NAME = "agent-memory-delete-undo.json"
        const val FORMAT_VERSION = 1
    }
}
