package com.longdev.endpointtester.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.longdev.endpointtester.model.ApiMode
import com.longdev.endpointtester.model.EndpointConfig
import com.longdev.endpointtester.model.ProviderProfile
import com.longdev.endpointtester.network.ApiFailure
import com.longdev.endpointtester.network.EndpointUrlBuilder
import com.longdev.endpointtester.network.FailureKind
import com.longdev.endpointtester.network.OpenAiCompatibleClient
import com.longdev.endpointtester.storage.SecureConfigStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TesterUiState(
    val profiles: List<ProviderProfile> = emptyList(),
    val selectedProfileId: String = "",
    val profileName: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val prompt: String = SecureConfigStore.DEFAULT_PROMPT,
    val availableModels: List<String> = emptyList(),
    val enabledModels: List<String> = emptyList(),
    val loadingModels: Boolean = false,
    val testingModel: Boolean = false,
    val apiMode: ApiMode = ApiMode.CHAT_COMPLETIONS,
    val streamingEnabled: Boolean = false,
    val chatMessages: List<ChatMessage> = emptyList(),
    val manageDraft: ProviderEditDraft? = null,
    val result: OperationResult? = null,
)

data class ProviderEditDraft(
    val id: String?,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val upstreamModels: List<String>,
    val enabledModels: Set<String>,
    val loadingModels: Boolean = false,
)

data class ChatMessage(
    val role: String,
    val text: String,
    val footer: String? = null,
)

data class OperationResult(
    val success: Boolean,
    val title: String,
    val message: String,
    val endpoint: String? = null,
    val latencyMs: Long? = null,
    val firstTokenLatencyMs: Long? = null,
)

class EndpointTesterViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = SecureConfigStore(application)
    private val client = OpenAiCompatibleClient()

    var uiState by mutableStateOf(configStore.load().toUiState())
        private set

    fun selectProfile(profileId: String) {
        val profile = uiState.profiles.firstOrNull { it.id == profileId } ?: return
        uiState = uiState.fromProfile(profile, profileId)
        configStore.save(uiState.profiles, profileId)
    }

    fun updateModel(value: String) {
        if (value.isBlank()) return
        val allowed = uiState.enabledModels
        if (allowed.isNotEmpty() && value !in allowed) {
            showValidation("这个模型没有在管理页勾选")
            return
        }
        updateAndSaveSelectedProfile { copy(model = value, result = null) }
    }

    fun updatePrompt(value: String) {
        uiState = uiState.copy(prompt = value, result = null)
    }

    fun openNewProvider() {
        uiState = uiState.copy(
            manageDraft = ProviderEditDraft(
                id = null,
                name = "",
                baseUrl = "",
                apiKey = "",
                upstreamModels = emptyList(),
                enabledModels = emptySet(),
            ),
            result = null,
        )
    }

    fun openEditProvider(profileId: String) {
        val profile = uiState.profiles.firstOrNull { it.id == profileId } ?: return
        uiState = uiState.copy(
            manageDraft = ProviderEditDraft(
                id = profile.id,
                name = profile.name,
                baseUrl = profile.baseUrl,
                apiKey = profile.apiKey,
                upstreamModels = profile.availableModels,
                enabledModels = profile.enabledModels.toSet(),
            ),
            result = null,
        )
    }

    fun closeProviderEditor() {
        uiState = uiState.copy(manageDraft = null, result = null)
    }

    fun clearResult() {
        uiState = uiState.copy(result = null)
    }

    fun updateDraftName(value: String) = updateDraft { copy(name = value) }
    fun updateDraftBaseUrl(value: String) = updateDraft { copy(baseUrl = value) }
    fun updateDraftApiKey(value: String) = updateDraft { copy(apiKey = value) }
    fun updateApiMode(value: ApiMode) {
        uiState = uiState.copy(apiMode = value, result = null)
    }
    fun updateStreamingEnabled(value: Boolean) {
        uiState = uiState.copy(streamingEnabled = value, result = null)
    }

    fun importDraftFromQr(raw: String) {
        importDraftCredentials(raw, successTitle = "扫码导入成功", invalidMessage = "二维码内容必须是 baseUrl,apiKey")
    }

    fun importDraftFromClipboard(raw: String) {
        importDraftCredentials(raw, successTitle = "剪切板导入成功", invalidMessage = "剪切板内容必须是 baseUrl,apiKey")
    }

    private fun importDraftCredentials(raw: String, successTitle: String, invalidMessage: String) {
        val separator = raw.indexOf(',')
        if (separator <= 0 || separator == raw.lastIndex) {
            showValidation(invalidMessage)
            return
        }
        val baseUrl = raw.substring(0, separator).trim()
        val apiKey = raw.substring(separator + 1).trim()
        EndpointUrlBuilder.validate(baseUrl)?.let {
            showValidation(it)
            return
        }
        updateDraft {
            copy(
                baseUrl = baseUrl,
                apiKey = apiKey,
                upstreamModels = emptyList(),
                enabledModels = emptySet(),
            )
        }
        showSuccess(successTitle, baseUrl)
    }

    fun toggleDraftModel(model: String, enabled: Boolean) = updateDraft {
        copy(
            enabledModels = if (enabled) {
                enabledModels + model
            } else {
                enabledModels - model
            },
        )
    }

    fun fetchDraftModels() {
        val draft = uiState.manageDraft ?: return
        EndpointUrlBuilder.validate(draft.baseUrl)?.let {
            showValidation(it)
            return
        }

        val config = EndpointConfig(
            baseUrl = draft.baseUrl.trim(),
            apiKey = draft.apiKey.trim(),
            model = "",
        )
        updateDraft { copy(loadingModels = true) }
        viewModelScope.launch {
            runCatching { client.fetchModels(config) }
                .onSuccess { models ->
                    val previous = uiState.manageDraft?.enabledModels.orEmpty()
                    updateDraft {
                        copy(
                            loadingModels = false,
                            upstreamModels = models,
                            enabledModels = previous.intersect(models.toSet()).ifEmpty { models.toSet() },
                        )
                    }
                    showSuccess("获取上游模型成功", "获取到 ${models.size} 个模型")
                }
                .onFailure { showFailure(it, loadingModels = false) }
        }
    }

    fun saveDraftProvider() {
        val draft = uiState.manageDraft ?: return
        EndpointUrlBuilder.validate(draft.baseUrl)?.let {
            showValidation(it)
            return
        }

        val id = draft.id ?: "profile-${System.currentTimeMillis()}"
        val enabledModels = draft.upstreamModels.filter { it in draft.enabledModels }
        val displayName = draft.name.trim().ifBlank { draft.baseUrl.trim() }
        val oldProfile = uiState.profiles.firstOrNull { it.id == id }
        val selectedModel = oldProfile?.model
            ?.takeIf { it in enabledModels }
            ?: enabledModels.firstOrNull()
            ?: ""
        val savedProfile = ProviderProfile.blank(id).copy(
            name = displayName,
            baseUrl = draft.baseUrl.trim(),
            apiKey = draft.apiKey,
            model = selectedModel,
            availableModels = draft.upstreamModels.distinct(),
            enabledModels = enabledModels.distinct(),
        )

        val profiles = if (draft.id == null) {
            uiState.profiles + savedProfile
        } else {
            uiState.profiles.map { if (it.id == id) savedProfile else it }
        }
        uiState = uiState.copy(
            profiles = profiles,
            manageDraft = null,
            result = OperationResult(
                success = true,
                title = "已保存",
                message = "${savedProfile.name} · ${savedProfile.enabledModels.size} 个模型",
            ),
        ).fromProfile(savedProfile, id)
        configStore.save(profiles, id)
    }

    fun deleteProvider(profileId: String) {
        if (uiState.profiles.size <= 1) {
            showValidation("至少保留一个模型提供方")
            return
        }
        val profiles = uiState.profiles.filterNot { it.id == profileId }
        val nextId = if (uiState.selectedProfileId == profileId) profiles.first().id else uiState.selectedProfileId
        val nextProfile = profiles.first { it.id == nextId }
        uiState = uiState.copy(
            profiles = profiles,
            manageDraft = null,
            result = OperationResult(true, "已删除", "模型提供方已删除"),
        ).fromProfile(nextProfile, nextId)
        configStore.save(profiles, nextId)
    }

    fun testModel() {
        val config = validatedConfig() ?: return
        if (uiState.prompt.isBlank()) {
            showValidation("请输入消息")
            return
        }
        val userMessage = uiState.prompt.trim()
        uiState = uiState.copy(
            testingModel = true,
            result = null,
            prompt = "",
            chatMessages = uiState.chatMessages + ChatMessage("user", userMessage, nowTimeText()),
        )
        viewModelScope.launch {
            runCatching { client.testModel(config, userMessage) }
                .onSuccess { test ->
                    val timingFooter = if (config.streamingEnabled) {
                        "首字 ${test.firstTokenLatencyMs?.toSecondsText() ?: "-"} · 耗时 ${test.latencyMs.toSecondsText()}"
                    } else {
                        "耗时 ${test.latencyMs.toSecondsText()}"
                    }
                    uiState = uiState.copy(
                        testingModel = false,
                        chatMessages = uiState.chatMessages + ChatMessage("assistant", test.responseText, timingFooter),
                        result = null,
                    )
                    saveCurrentProfileSelection()
                }
                .onFailure { showFailure(it, testingModel = false) }
        }
    }

    private fun validatedConfig(): EndpointConfig? {
        EndpointUrlBuilder.validate(uiState.baseUrl)?.let {
            showValidation(it)
            return null
        }
        if (uiState.enabledModels.isEmpty()) {
            showValidation("请先在管理页勾选可测试模型")
            return null
        }
        if (uiState.model.isBlank()) {
            showValidation("请选择模型")
            return null
        }
        if (uiState.model !in uiState.enabledModels) {
            showValidation("这个模型没有在管理页勾选")
            return null
        }
        val profile = selectedProfile()
        return EndpointConfig(
            baseUrl = profile.baseUrl.trim(),
            apiKey = profile.apiKey.trim(),
            model = profile.model.trim(),
            apiMode = uiState.apiMode,
            streamingEnabled = uiState.streamingEnabled,
            maxTokens = ProviderProfile.FIXED_MAX_TOKENS,
        )
    }

    private fun selectedProfile(): ProviderProfile {
        val storedProfile = uiState.profiles.firstOrNull { it.id == uiState.selectedProfileId }
            ?: ProviderProfile.blank(uiState.selectedProfileId)
        return storedProfile.copy(
            name = uiState.profileName,
            baseUrl = uiState.baseUrl,
            apiKey = uiState.apiKey,
            model = uiState.model,
            availableModels = uiState.availableModels,
            enabledModels = uiState.enabledModels,
        )
    }

    private fun saveCurrentProfileSelection() {
        val profiles = uiState.profiles.map {
            if (it.id == uiState.selectedProfileId) it.copy(model = uiState.model) else it
        }
        uiState = uiState.copy(profiles = profiles)
        configStore.save(profiles, uiState.selectedProfileId)
    }

    private fun updateAndSaveSelectedProfile(block: TesterUiState.() -> TesterUiState) {
        uiState = uiState.block()
        saveCurrentProfileSelection()
    }

    private inline fun updateDraft(block: ProviderEditDraft.() -> ProviderEditDraft) {
        uiState.manageDraft?.let {
            uiState = uiState.copy(manageDraft = it.block(), result = null)
        }
    }

    private fun showFailure(error: Throwable, loadingModels: Boolean? = null, testingModel: Boolean? = null) {
        val failure = error as? ApiFailure
        val draft = uiState.manageDraft
        uiState = uiState.copy(
            loadingModels = loadingModels ?: uiState.loadingModels,
            testingModel = testingModel ?: uiState.testingModel,
            manageDraft = draft?.copy(loadingModels = loadingModels ?: draft.loadingModels),
            result = OperationResult(
                success = false,
                title = failure?.kind?.title ?: FailureKind.UNKNOWN.title,
                message = error.message ?: "未知错误",
            ),
        )
    }

    private fun showValidation(message: String) {
        uiState = uiState.copy(
            result = OperationResult(
                success = false,
                title = "配置不完整",
                message = message,
            ),
        )
    }

    private fun showSuccess(title: String, message: String) {
        uiState = uiState.copy(
            result = OperationResult(
                success = true,
                title = title,
                message = message,
            ),
        )
    }

    private fun Long.toSecondsText(): String = String.format(Locale.US, "%.2f s", this / 1000.0)

    private fun nowTimeText(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun TesterUiState.fromProfile(profile: ProviderProfile, selectedId: String) = copy(
        selectedProfileId = selectedId,
        profileName = profile.name,
        baseUrl = profile.baseUrl,
        apiKey = profile.apiKey,
        model = profile.model.takeIf { it in profile.enabledModels } ?: profile.enabledModels.firstOrNull().orEmpty(),
        availableModels = profile.availableModels,
        enabledModels = profile.enabledModels,
        result = result,
    )

    private fun com.longdev.endpointtester.storage.StoredProfiles.toUiState(): TesterUiState {
        val safeProfiles = profiles.ifEmpty { listOf(ProviderProfile.blank()) }
        val selected = safeProfiles.firstOrNull { it.id == selectedProfileId } ?: safeProfiles.first()
        return TesterUiState(
            profiles = safeProfiles,
            selectedProfileId = selected.id,
        ).fromProfile(selected, selected.id)
    }
}
