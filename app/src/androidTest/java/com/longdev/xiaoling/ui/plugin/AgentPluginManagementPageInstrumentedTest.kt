package com.longdev.xiaoling.ui.plugin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.longdev.xiaoling.agent.AgentPluginJsonCodec
import com.longdev.xiaoling.agent.AgentPluginManifest
import com.longdev.xiaoling.agent.AgentPluginPermission
import com.longdev.xiaoling.agent.AgentPluginRegistry
import com.longdev.xiaoling.agent.AgentPluginStateStore
import com.longdev.xiaoling.agent.InstalledAgentPlugin
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AgentPluginManagementPageInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun installEnableAndRemoveOnlyChangeTheDeclarationRegistry() {
        val store = TestStore()
        val registry = AgentPluginRegistry(store)
        composeRule.setContent {
            MaterialTheme { AgentPluginManagementPage(registry = registry, onBack = {}) }
        }

        val raw = JSONObject(AgentPluginJsonCodec.encode(listOf(InstalledAgentPlugin(manifest()))))
            .getJSONArray("plugins").getJSONObject(0).getJSONObject("manifest").toString()
        composeRule.onNodeWithTag("agent-plugin-manifest-input").performTextInput(raw)
        composeRule.onNodeWithTag("agent-plugin-install").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(false, registry.get("demo.read")?.enabled)
            assertEquals(manifest(), AgentPluginRegistry(TestStore(store.state)).get("demo.read")?.manifest)
        }
        composeRule.onNodeWithTag("agent-plugin-demo.read").performScrollTo().assertExists()

        composeRule.onNodeWithTag("agent-plugin-enabled-demo.read").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(true, registry.get("demo.read")?.enabled) }
        composeRule.onNodeWithTag("agent-plugin-delete-demo.read").performScrollTo().performClick()
        composeRule.onNodeWithTag("agent-plugin-confirm-delete").performClick()
        composeRule.runOnIdle {
            assertTrue(registry.list().isEmpty())
            assertTrue(AgentPluginRegistry(TestStore(store.state)).list().isEmpty())
        }
        composeRule.onNodeWithTag("agent-plugin-empty").assertIsDisplayed()
    }

    @Test
    fun failedPersistenceFreezesManagementWithoutPretendingInstallSucceeded() {
        val registry = AgentPluginRegistry(TestStore(failWrites = true))
        composeRule.setContent {
            MaterialTheme { AgentPluginManagementPage(registry = registry, onBack = {}) }
        }

        val raw = JSONObject(AgentPluginJsonCodec.encode(listOf(InstalledAgentPlugin(manifest()))))
            .getJSONArray("plugins").getJSONObject(0).getJSONObject("manifest").toString()
        composeRule.onNodeWithTag("agent-plugin-manifest-input").performTextInput(raw)
        composeRule.onNodeWithTag("agent-plugin-install").performScrollTo().performClick()
        composeRule.onNodeWithTag("agent-plugin-state-error").assertExists()
        composeRule.runOnIdle { assertFalse(registry.list().isNotEmpty()) }
    }

    private fun manifest() = AgentPluginManifest(
        id = "demo.read",
        version = "1.0.0",
        name = "只读演示",
        description = "声明时间能力",
        publisher = "long",
        capabilities = setOf("app.current_time"),
        permissions = setOf(AgentPluginPermission.READ_ONLY_CAPABILITIES),
    )

    private class TestStore(
        var state: String? = null,
        private val failWrites: Boolean = false,
    ) : AgentPluginStateStore {
        override fun loadState(): String? = state
        override fun saveState(state: String) {
            if (failWrites) error("test write failed")
            this.state = state
        }
    }
}
