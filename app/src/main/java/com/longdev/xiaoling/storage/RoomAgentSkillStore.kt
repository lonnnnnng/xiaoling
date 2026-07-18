package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.AgentSkillDefinition
import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.AgentSkillSource
import com.longdev.xiaoling.agent.AgentSkillStore
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.data.AgentSkillEntity
import com.longdev.xiaoling.data.RoomJson
import com.longdev.xiaoling.data.XiaoLingDatabase

class RoomAgentSkillStore(
    context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
) : AgentSkillStore {
    override suspend fun synchronizeBuiltIns(definitions: List<AgentSkillDefinition>) {
        val dao = database.agentSkillDao()
        definitions.forEach { definition ->
            val current = dao.get(definition.id)
            require(current == null || current.source == AgentSkillSource.BUILT_IN.name) {
                "本地 Skill 与内置 Skill ID 冲突：${definition.id}"
            }
            if (current?.toRecord()?.definition == definition) return@forEach
            // long: 应用升级可刷新内置 Skill 文本和工具声明，但必须保留用户之前的启停决定，不能因为版本升级悄悄重新启用能力。
            dao.upsert(
                AgentSkillRecord(
                    definition = definition,
                    enabled = current?.enabled ?: true,
                    importedAt = current?.importedAt ?: 0L,
                    updatedAt = System.currentTimeMillis(),
                ).toEntity(),
            )
        }
    }

    override suspend fun list(): List<AgentSkillRecord> {
        return database.agentSkillDao().list().map { it.toRecord() }
    }

    override suspend fun upsert(record: AgentSkillRecord): AgentSkillRecord {
        database.agentSkillDao().upsert(record.toEntity())
        return record
    }

    override suspend fun setEnabled(skillId: String, enabled: Boolean): AgentSkillRecord? {
        val dao = database.agentSkillDao()
        if (dao.setEnabled(skillId, enabled, System.currentTimeMillis()) == 0) return null
        return dao.get(skillId)?.toRecord()
    }

    override suspend fun deleteLocal(skillId: String): Boolean {
        return database.agentSkillDao().deleteLocal(skillId) > 0
    }

    private fun AgentSkillRecord.toEntity() = AgentSkillEntity(
        id = definition.id,
        version = definition.version,
        name = definition.name,
        description = definition.description,
        instructions = definition.instructions,
        toolNamesJson = RoomJson.encodeStringList(definition.toolNames.toList()),
        keywordsJson = RoomJson.encodeStringList(definition.keywords.toList()),
        triggerExamplesJson = RoomJson.encodeStringList(definition.triggerExamples),
        requiredAndroidPermissionsJson = RoomJson.encodeStringList(definition.requiredAndroidPermissions.toList()),
        declaredRisk = definition.declaredRisk.name,
        failureRecovery = definition.failureRecovery,
        completionCriteria = definition.completionCriteria,
        source = definition.source.name,
        enabled = enabled,
        importedAt = importedAt,
        updatedAt = updatedAt,
    )

    private fun AgentSkillEntity.toRecord() = AgentSkillRecord(
        definition = AgentSkillDefinition(
            id = id,
            version = version,
            name = name,
            description = description,
            instructions = instructions,
            toolNames = RoomJson.decodeStringList(toolNamesJson).toCollection(linkedSetOf()),
            keywords = RoomJson.decodeStringList(keywordsJson).toCollection(linkedSetOf()),
            triggerExamples = RoomJson.decodeStringList(triggerExamplesJson),
            requiredAndroidPermissions = RoomJson.decodeStringList(requiredAndroidPermissionsJson).toCollection(linkedSetOf()),
            declaredRisk = ToolRisk.valueOf(declaredRisk),
            failureRecovery = failureRecovery,
            completionCriteria = completionCriteria,
            source = AgentSkillSource.valueOf(source),
        ),
        enabled = enabled,
        importedAt = importedAt,
        updatedAt = updatedAt,
    )
}
