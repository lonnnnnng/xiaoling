package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.withTransaction
import com.longdev.xiaoling.data.ApiKeyCipher
import com.longdev.xiaoling.data.ProviderEntity
import com.longdev.xiaoling.data.RoomJson
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.ProviderProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProviderRepository(
    context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
    private val stateStore: RoomStateStore = RoomStateStore(context),
    private val legacyStore: SecureConfigStore = SecureConfigStore(context),
    private val apiKeyCipher: ApiKeyCipher = ApiKeyCipher(),
) {
    suspend fun load(): StoredProfiles = withContext(Dispatchers.IO) {
        ensureMigrated()
        val profiles = database.providerDao().getAll()
            .map { it.toProfile() }
            .ifEmpty { listOf(ProviderProfile.blank()) }
        val selected = stateStore.selectedProviderId()
            ?.takeIf { id -> profiles.any { it.id == id } }
            ?: profiles.first().id
        StoredProfiles(profiles = profiles, selectedProfileId = selected)
    }

    suspend fun save(profiles: List<ProviderProfile>, selectedProfileId: String) = withContext(Dispatchers.IO) {
        ensureMigrated()
        val safeProfiles = profiles.ifEmpty { listOf(ProviderProfile.blank()) }
        database.withTransaction {
            database.providerDao().deleteAll()
            database.providerDao().insertAll(safeProfiles.map { it.toEntity() })
        }
        stateStore.saveSelectedProviderId(
            selectedProfileId.takeIf { id -> safeProfiles.any { it.id == id } } ?: safeProfiles.first().id,
        )
    }

    private suspend fun ensureMigrated() {
        if (stateStore.providersMigrated()) return
        val legacy = legacyStore.load()
        val profiles = legacy.profiles.ifEmpty { listOf(ProviderProfile.blank()) }
        database.withTransaction {
            if (database.providerDao().getAll().isEmpty()) {
                // long: 首次升级 Room 时只从旧偏好文件导入一次；之后即使用户删除 Provider，也不能反复从旧数据复活。
                database.providerDao().insertAll(profiles.map { it.toEntity() })
            }
        }
        stateStore.saveSelectedProviderId(
            legacy.selectedProfileId.takeIf { id -> profiles.any { it.id == id } } ?: profiles.first().id,
        )
        stateStore.markProvidersMigrated()
    }

    private fun ProviderProfile.toEntity(): ProviderEntity {
        val encrypted = apiKeyCipher.encrypt(apiKey)
        return ProviderEntity(
            id = id,
            name = name,
            baseUrl = baseUrl,
            apiKeyIv = encrypted.iv,
            apiKeyCiphertext = encrypted.ciphertext,
            model = model,
            availableModelsJson = RoomJson.encodeStringList(availableModels),
            enabledModelsJson = RoomJson.encodeStringList(enabledModels),
            lastSyncedAt = lastSyncedAt,
        )
    }

    private fun ProviderEntity.toProfile(): ProviderProfile {
        return ProviderProfile(
            id = id,
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKeyCipher.decrypt(apiKeyIv, apiKeyCiphertext),
            model = model,
            availableModels = RoomJson.decodeStringList(availableModelsJson),
            enabledModels = RoomJson.decodeStringList(enabledModelsJson),
            lastSyncedAt = lastSyncedAt,
        )
    }
}
