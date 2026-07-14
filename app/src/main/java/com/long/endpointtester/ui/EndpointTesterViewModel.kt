package com.longdev.endpointtester.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.longdev.endpointtester.model.EndpointConfig
import com.longdev.endpointtester.network.ApiFailure
import com.longdev.endpointtester.network.EndpointUrlBuilder
import com.longdev.endpointtester.network.FailureKind
import com.longdev.endpointtester.network.HeaderParser
import com.longdev.endpointtester.network.OpenAiCompatibleClient
import com.longdev.endpointtester.storage.SecureConfigStore
import com.longdev.endpointtester.storage.StoredConfig
import kotlinx.coroutines.launch

data class TesterUiState(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val customHeaders: String = "",
    val prompt: String = SecureConfigStore.DEFAULT_PROMPT,
    val rememberApiKey: Boolean = true,
    val showCustomHeaders: Boolean = false,
    val availableModels: List<String> = emptyList(),
    val loadingModels: Boolean = false,
    val testingModel: Boolean = false,
    val result: OperationResult? = null,
)

data class OperationResult(
    val success: Boolean,
    val title: String,
    val message: String,
    val endpoint: String? = null,
    val latencyMs: Long? = null,
)

class EndpointTesterViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = SecureConfigStore(application)
    private val client = OpenAiCompatibleClient()

    var uiState by mutableStateOf(configStore.load().toUiState())
        private set

    fun updateBaseUrl(value: String) = update { copy(baseUrl = value, result = null) }
    fun updateApiKey(value: String) = update { copy(apiKey = value, result = null) }
    fun updateModel(value: String) = update { copy(model = value, result = null) }
    fun updatePrompt(value: String) = update { copy(prompt = value, result = null) }
    fun updateCustomHeaders(value: String) = update { copy(customHeaders = value, result = null) }
    fun setShowCustomHeaders(value: Boolean) = update { copy(showCustomHeaders = value) }

    fun setRememberApiKey(value: Boolean) {
        update { copy(rememberApiKey = value) }
        saveCurrentConfig()
    }

    fun fetchModels() {
        val config = validatedConfig(requireModel = false) ?: return
        uiState = uiState.copy(loadingModels = true, result = null)
        viewModelScope.launch {
            runCatching { client.fetchModels(config) }
                .onSuccess { models ->
                    val selectedModel = uiState.model.ifBlank { models.first() }
                    uiState = uiState.copy(
                        loadingModels = false,
                        model = selectedModel,
                        availableModels = models,
                        result = OperationResult(
                            success = true,
                            title = "模型列表可用",
                            message = "获取到 ${models.size} 个模型",
                            endpoint = EndpointUrlBuilder.modelsUrl(config.baseUrl),
                        ),
                    )
                    saveCurrentConfig()
                }
                .onFailure { showFailure(it, loadingModels = false) }
        }
    }

    fun testModel() {
        val config = validatedConfig(requireModel = true) ?: return
        if (uiState.prompt.isBlank()) {
            showValidation("请输入测试消息")
            return
        }
        uiState = uiState.copy(testingModel = true, result = null)
        viewModelScope.launch {
            runCatching { client.testModel(config, uiState.prompt.trim()) }
                .onSuccess { test ->
                    uiState = uiState.copy(
                        testingModel = false,
                        result = OperationResult(
                            success = true,
                            title = "模型可用",
                            message = test.responseText,
                            endpoint = test.endpoint,
                            latencyMs = test.latencyMs,
                        ),
                    )
                    saveCurrentConfig()
                }
                .onFailure { showFailure(it, testingModel = false) }
        }
    }

    private fun validatedConfig(requireModel: Boolean): EndpointConfig? {
        EndpointUrlBuilder.validate(uiState.baseUrl)?.let {
            showValidation(it)
            return null
        }
        if (requireModel && uiState.model.isBlank()) {
            showValidation("请输入或选择模型名称")
            return null
        }
        val headers = runCatching { HeaderParser.parse(uiState.customHeaders) }
            .getOrElse {
                showValidation(it.message ?: "自定义 Header 格式错误")
                return null
            }
        return EndpointConfig(
            baseUrl = uiState.baseUrl.trim(),
            apiKey = uiState.apiKey.trim(),
            model = uiState.model.trim(),
            customHeaders = headers,
        )
    }

    private fun showFailure(error: Throwable, loadingModels: Boolean? = null, testingModel: Boolean? = null) {
        val failure = error as? ApiFailure
        uiState = uiState.copy(
            loadingModels = loadingModels ?: uiState.loadingModels,
            testingModel = testingModel ?: uiState.testingModel,
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

    private fun saveCurrentConfig() {
        configStore.save(
            StoredConfig(
                baseUrl = uiState.baseUrl.trim(),
                apiKey = uiState.apiKey,
                model = uiState.model.trim(),
                customHeaders = uiState.customHeaders,
                prompt = uiState.prompt,
                rememberApiKey = uiState.rememberApiKey,
            ),
        )
    }

    private inline fun update(block: TesterUiState.() -> TesterUiState) {
        uiState = uiState.block()
    }

    private fun StoredConfig.toUiState() = TesterUiState(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        customHeaders = customHeaders,
        prompt = prompt,
        rememberApiKey = rememberApiKey,
        showCustomHeaders = customHeaders.isNotBlank(),
    )
}
