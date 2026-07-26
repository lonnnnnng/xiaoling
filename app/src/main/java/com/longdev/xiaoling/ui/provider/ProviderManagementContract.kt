package com.longdev.xiaoling.ui.provider

import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.ui.OperationResult

interface ProviderManagementActions {
    fun syncAllProviders()

    fun syncProviderModels(profileId: String)

    fun openEditProvider(profileId: String)

    fun deleteProvider(profileId: String)

    fun openNewProvider()

    fun closeProviderEditor()

    fun importDraftFromQr(raw: String)

    fun importDraftFromClipboard(raw: String)

    fun updateDraftName(value: String)

    fun updateDraftBaseUrl(value: String)

    fun updateDraftApiKey(value: String)

    fun fetchDraftModels()

    fun toggleDraftModel(model: String, enabled: Boolean)

    fun saveDraftProvider()
}

data class ProviderEditDraft(
    val id: String?,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val upstreamModels: List<String>,
    val enabledModels: Set<String>,
    val loadingModels: Boolean = false,
)

internal data class ProviderManagementUiState(
    val profiles: List<ProviderManagementItemUiState> = emptyList(),
    val syncingAllProfiles: Boolean = false,
    val draft: ProviderEditDraft? = null,
    val inlineResult: OperationResult? = null,
)

internal data class ProviderManagementItemUiState(
    val profile: ProviderProfile,
    val selected: Boolean,
    val syncing: Boolean,
    val syncResult: String?,
)

internal object ProviderManagementProjection {
    fun project(
        profiles: List<ProviderProfile>,
        selectedProfileId: String,
        syncingProfileIds: Set<String>,
        syncingAllProfiles: Boolean,
        batchSyncResults: Map<String, String>,
        draft: ProviderEditDraft?,
        result: OperationResult?,
    ): ProviderManagementUiState {
        // long: 列表项状态必须按稳定 Provider ID 绑定；同步期间列表重排或选择变化不能把进度和结果显示到其他提供方。
        return ProviderManagementUiState(
            profiles = profiles.map { profile ->
                ProviderManagementItemUiState(
                    profile = profile,
                    selected = profile.id == selectedProfileId,
                    syncing = syncingAllProfiles || profile.id in syncingProfileIds,
                    syncResult = batchSyncResults[profile.id],
                )
            },
            syncingAllProfiles = syncingAllProfiles,
            draft = draft,
            inlineResult = result?.takeIf { it.requestUrl != null || it.latencyMs != null },
        )
    }
}
