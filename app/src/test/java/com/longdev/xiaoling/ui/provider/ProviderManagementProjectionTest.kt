package com.longdev.xiaoling.ui.provider

import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.ui.OperationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderManagementProjectionTest {
    @Test
    fun projectBindsSelectedAndSyncingStateByStableProviderId() {
        val first = provider("provider-1")
        val second = provider("provider-2")

        val result = ProviderManagementProjection.project(
            profiles = listOf(first, second),
            selectedProfileId = second.id,
            syncingProfileIds = setOf(first.id),
            syncingAllProfiles = false,
            batchSyncResults = mapOf(second.id to "同步失败"),
            draft = null,
            result = null,
        )

        assertFalse(result.syncingAllProfiles)
        assertEquals(listOf(first.id, second.id), result.profiles.map { it.profile.id })
        assertTrue(result.profiles.first().syncing)
        assertFalse(result.profiles.first().selected)
        assertEquals(null, result.profiles.first().syncResult)
        assertFalse(result.profiles.last().syncing)
        assertTrue(result.profiles.last().selected)
        assertEquals("同步失败", result.profiles.last().syncResult)
    }

    @Test
    fun projectKeepsEditorDraftAndOnlyInlineNetworkResult() {
        val draft = ProviderEditDraft(
            id = "provider-1",
            name = "主提供方",
            baseUrl = "https://example.com/v1",
            apiKey = "secret",
            upstreamModels = listOf("model-1"),
            enabledModels = setOf("model-1"),
        )
        val transientResult = OperationResult(true, "已保存", "保存成功")
        val inlineResult = OperationResult(
            success = true,
            title = "获取成功",
            message = "1 个模型",
            requestUrl = "https://example.com/v1/models",
        )

        val transient = ProviderManagementProjection.project(
            profiles = emptyList(),
            selectedProfileId = "",
            syncingProfileIds = emptySet(),
            syncingAllProfiles = false,
            batchSyncResults = emptyMap(),
            draft = draft,
            result = transientResult,
        )
        val inline = ProviderManagementProjection.project(
            profiles = emptyList(),
            selectedProfileId = "",
            syncingProfileIds = emptySet(),
            syncingAllProfiles = false,
            batchSyncResults = emptyMap(),
            draft = draft,
            result = inlineResult,
        )

        assertEquals(draft, transient.draft)
        assertEquals(null, transient.inlineResult)
        assertEquals(inlineResult, inline.inlineResult)
    }

    private fun provider(id: String): ProviderProfile {
        return ProviderProfile.blank(id).copy(
            name = id,
            baseUrl = "https://example.com/v1",
            availableModels = listOf("model-1"),
            enabledModels = listOf("model-1"),
        )
    }
}
