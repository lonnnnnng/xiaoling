package com.longdev.xiaoling.ui.provider

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.longdev.xiaoling.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProviderManagementPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listRoutesProviderActionsWithoutConcreteViewModel() {
        val actions = FakeProviderManagementActions()
        var backCount = 0
        composeRule.setContent {
            MaterialTheme {
                ProviderManagementPage(
                    state = providerManagementState(),
                    actions = actions,
                    onBack = { backCount += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("批量同步").performClick()
        composeRule.onNodeWithContentDescription("同步模型").performClick()
        composeRule.onNodeWithContentDescription("编辑").performClick()
        composeRule.onNodeWithContentDescription("删除").performClick()
        composeRule.onNodeWithContentDescription("新增模型提供方").performClick()
        composeRule.onNodeWithContentDescription("返回设置").performClick()

        composeRule.runOnIdle {
            assertEquals(1, actions.syncAllCount)
            assertEquals(listOf("provider-1"), actions.syncedProviderIds)
            assertEquals(listOf("provider-1"), actions.editedProviderIds)
            assertEquals(listOf("provider-1"), actions.deletedProviderIds)
            assertEquals(1, actions.openNewCount)
            assertEquals(1, backCount)
        }
    }

    @Test
    fun editorRoutesFieldsPlatformInputsModelsSaveAndBack() {
        var scanCount = 0
        var clipboardImportCount = 0
        var backCount = 0
        val copiedValues = mutableListOf<String>()
        var draft by mutableStateOf(ProviderEditDraft(
            id = "provider-1",
            name = "主提供方",
            baseUrl = "https://example.com/v1",
            apiKey = "secret",
            upstreamModels = listOf("model-1", "model-2"),
            enabledModels = setOf("model-1"),
        ))
        val actions = FakeProviderManagementActions(
            onNameChanged = { value -> draft = draft.copy(name = value) },
            onBaseUrlChanged = { value -> draft = draft.copy(baseUrl = value) },
            onApiKeyChanged = { value -> draft = draft.copy(apiKey = value) },
            onModelToggled = { model, enabled ->
                draft = draft.copy(
                    enabledModels = if (enabled) draft.enabledModels + model else draft.enabledModels - model,
                )
            },
        )
        composeRule.setContent {
            MaterialTheme {
                ProviderEditorContent(
                    draft = draft,
                    inlineResult = null,
                    actions = actions,
                    onBack = { backCount += 1 },
                    onScanRequested = { scanCount += 1 },
                    onImportFromClipboard = { clipboardImportCount += 1 },
                    onCopyText = copiedValues::add,
                )
            }
        }

        composeRule.onNodeWithTag(PROVIDER_NAME_FIELD_TAG).performTextReplacement("备用提供方")
        composeRule.onNodeWithTag(PROVIDER_URL_FIELD_TAG).performTextReplacement("https://backup.example.com/v1")
        composeRule.onNodeWithTag(PROVIDER_API_KEY_FIELD_TAG).performTextReplacement("new-secret")
        composeRule.onNodeWithContentDescription("扫码导入").performClick()
        composeRule.onNodeWithContentDescription("从剪切板导入").performClick()
        composeRule.onNodeWithContentDescription("复制 URL").performClick()
        composeRule.onNodeWithContentDescription("复制 API Key").performClick()
        composeRule.onNodeWithTag(providerModelCheckboxTag("model-2")).performClick()
        composeRule.onNodeWithText("获取").performClick()
        composeRule.onNodeWithText("保存").performClick()
        composeRule.onNodeWithContentDescription("返回").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("备用提供方"), actions.names)
            assertEquals(listOf("https://backup.example.com/v1"), actions.baseUrls)
            assertEquals(listOf("new-secret"), actions.apiKeys)
            assertEquals(1, scanCount)
            assertEquals(1, clipboardImportCount)
            assertEquals(listOf("https://backup.example.com/v1", "new-secret"), copiedValues)
            assertEquals(listOf("model-2" to true), actions.modelToggles)
            assertEquals(1, actions.fetchCount)
            assertEquals(1, actions.saveCount)
            assertEquals(1, backCount)
        }
    }

    private fun providerManagementState(): ProviderManagementUiState {
        val profile = ProviderProfile.blank("provider-1").copy(
            name = "主提供方",
            baseUrl = "https://example.com/v1",
            availableModels = listOf("model-1"),
            enabledModels = listOf("model-1"),
        )
        return ProviderManagementUiState(
            profiles = listOf(
                ProviderManagementItemUiState(
                    profile = profile,
                    selected = true,
                    syncing = false,
                    syncResult = null,
                ),
            ),
        )
    }

    private class FakeProviderManagementActions(
        private val onNameChanged: (String) -> Unit = {},
        private val onBaseUrlChanged: (String) -> Unit = {},
        private val onApiKeyChanged: (String) -> Unit = {},
        private val onModelToggled: (String, Boolean) -> Unit = { _, _ -> },
    ) : ProviderManagementActions {
        var syncAllCount = 0
        val syncedProviderIds = mutableListOf<String>()
        val editedProviderIds = mutableListOf<String>()
        val deletedProviderIds = mutableListOf<String>()
        var openNewCount = 0
        val names = mutableListOf<String>()
        val baseUrls = mutableListOf<String>()
        val apiKeys = mutableListOf<String>()
        val modelToggles = mutableListOf<Pair<String, Boolean>>()
        var fetchCount = 0
        var saveCount = 0

        override fun syncAllProviders() {
            syncAllCount += 1
        }

        override fun syncProviderModels(profileId: String) {
            syncedProviderIds += profileId
        }

        override fun openEditProvider(profileId: String) {
            editedProviderIds += profileId
        }

        override fun deleteProvider(profileId: String) {
            deletedProviderIds += profileId
        }

        override fun openNewProvider() {
            openNewCount += 1
        }

        override fun closeProviderEditor() = Unit

        override fun importDraftFromQr(raw: String) = Unit

        override fun importDraftFromClipboard(raw: String) = Unit

        override fun updateDraftName(value: String) {
            names += value
            onNameChanged(value)
        }

        override fun updateDraftBaseUrl(value: String) {
            baseUrls += value
            onBaseUrlChanged(value)
        }

        override fun updateDraftApiKey(value: String) {
            apiKeys += value
            onApiKeyChanged(value)
        }

        override fun fetchDraftModels() {
            fetchCount += 1
        }

        override fun toggleDraftModel(model: String, enabled: Boolean) {
            modelToggles += model to enabled
            onModelToggled(model, enabled)
        }

        override fun saveDraftProvider() {
            saveCount += 1
        }
    }
}
