package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.AgentContextPolicy
import com.longdev.xiaoling.agent.AgentProfilePolicy
import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.agent.AgentProfileStore
import com.longdev.xiaoling.agent.StoredAgentProfiles
import com.longdev.xiaoling.data.AgentProfileEntity
import com.longdev.xiaoling.data.RoomJson
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.ApiMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomAgentProfileStore(
    context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
    private val stateStore: RoomStateStore = RoomStateStore(context),
) : AgentProfileStore {
    override suspend fun loadOrCreateDefault(defaultProfile: AgentProfileRecord): StoredAgentProfiles =
        withContext(Dispatchers.IO) {
            AgentProfilePolicy.validateForStorage(defaultProfile)
            val dao = database.agentProfileDao()
            var profiles = dao.list().map { it.toRecord() }
            if (profiles.isEmpty()) {
                // long: v21 迁移只建空表，默认 Agent 必须在 Provider 和 Skill 已加载后创建，才能冻结当前真实模型与能力白名单，而不是在 SQL 中伪造配置。
                dao.upsert(defaultProfile.toEntity())
                profiles = listOf(defaultProfile)
            }
            val selectedId = stateStore.selectedAgentProfileId()
                ?.takeIf { candidate -> profiles.any { it.id == candidate } }
                ?: profiles.first().id
            stateStore.saveSelectedAgentProfileId(selectedId)
            StoredAgentProfiles(profiles = profiles, selectedProfileId = selectedId)
        }

    override suspend fun list(): List<AgentProfileRecord> = withContext(Dispatchers.IO) {
        database.agentProfileDao().list().map { it.toRecord() }
    }

    override suspend fun upsert(profile: AgentProfileRecord): AgentProfileRecord = withContext(Dispatchers.IO) {
        AgentProfilePolicy.validateForStorage(profile)
        database.agentProfileDao().upsert(profile.toEntity())
        profile
    }

    override suspend fun delete(profileId: String): Boolean = withContext(Dispatchers.IO) {
        database.agentProfileDao().delete(profileId) > 0
    }

    override suspend fun select(profileId: String): Boolean = withContext(Dispatchers.IO) {
        if (database.agentProfileDao().get(profileId) == null) return@withContext false
        stateStore.saveSelectedAgentProfileId(profileId)
        true
    }

    private fun AgentProfileRecord.toEntity() = AgentProfileEntity(
        id = id,
        name = name,
        avatar = avatar,
        providerId = providerId,
        model = model,
        apiMode = apiMode.name,
        systemPrompt = systemPrompt,
        contextPolicy = contextPolicy.name,
        allowedToolNamesJson = RoomJson.encodeStringList(allowedToolNames.distinct().sorted()),
        allowedSkillIdsJson = RoomJson.encodeStringList(allowedSkillIds.distinct().sorted()),
        memoryEnabled = memoryEnabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun AgentProfileEntity.toRecord() = AgentProfileRecord(
        id = id,
        name = name,
        avatar = avatar,
        providerId = providerId,
        model = model,
        apiMode = ApiMode.valueOf(apiMode),
        systemPrompt = systemPrompt,
        contextPolicy = AgentContextPolicy.valueOf(contextPolicy),
        allowedToolNames = RoomJson.decodeStringList(allowedToolNamesJson),
        allowedSkillIds = RoomJson.decodeStringList(allowedSkillIdsJson),
        memoryEnabled = memoryEnabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
