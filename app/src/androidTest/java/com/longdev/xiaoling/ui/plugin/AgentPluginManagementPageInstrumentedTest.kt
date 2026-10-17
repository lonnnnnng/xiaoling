package com.longdev.xiaoling.ui.plugin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import com.longdev.xiaoling.agent.AgentPluginJsonCodec
import com.longdev.xiaoling.agent.AgentPluginManifest
import com.longdev.xiaoling.agent.AgentPluginPermission
import com.longdev.xiaoling.agent.AgentPluginRegistry
import com.longdev.xiaoling.agent.AgentPluginStateStore
import com.longdev.xiaoling.agent.InstalledAgentPlugin
import com.longdev.xiaoling.agent.AgentPluginSigningFixture
import com.longdev.xiaoling.agent.AgentPluginSigning
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolRisk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AgentPluginManagementPageInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun signedTaskRequiresGoalAndExplicitClickWithFrozenDigest() {
        val registry = AgentPluginRegistry(TestStore())
        val signed = AgentPluginSigningFixture.sign()
        val digest = AgentPluginSigning.verify(signed).manifestSha256
        registry.installSigned(signed, AgentPluginSigning.verify(signed)); registry.setEnabled("signed.read", true)
        var invocation: Triple<String, String, String>? = null
        composeRule.setContent { MaterialTheme { AgentPluginManagementPage(registry, {},
            selectedProfileName = "当前 Agent", registeredTools = listOf(ToolDefinition("app.current_time", "时间", ToolRisk.SAFE)),
            allowedToolNames = setOf("app.current_time"), executionAvailable = true,
            onRunReadOnly = { id, goal, hash -> invocation = Triple(id, goal, hash); true }) } }
        composeRule.onNodeWithTag("agent-plugin-run-signed.read").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("agent-plugin-goal-signed.read").performScrollTo().performTextInput("读取当前时间")
        composeRule.runOnIdle { assertEquals(null, invocation) }
        composeRule.onNodeWithTag("agent-plugin-run-signed.read").performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(Triple("signed.read", "读取当前时间", digest), invocation) }
    }

    @Test fun unsignedDisabledEmptyIntersectionAndUnavailableContextCannotRun() {
        val registry = AgentPluginRegistry(TestStore())
        registry.install(manifest()); registry.setEnabled("demo.read", true)
        val signed = AgentPluginSigningFixture.sign(); registry.installSigned(signed, AgentPluginSigning.verify(signed))
        composeRule.setContent { MaterialTheme { AgentPluginManagementPage(registry, {},
            selectedProfileName = "当前 Agent", registeredTools = listOf(ToolDefinition("app.current_time", "时间", ToolRisk.SAFE)),
            allowedToolNames = emptySet(), executionAvailable = false,
            onRunReadOnly = { _, _, _ -> error("不可调用") }) } }
        composeRule.onNodeWithTag("agent-plugin-run-demo.read").assertDoesNotExist()
        composeRule.onNodeWithTag("agent-plugin-run-signed.read").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("agent-plugin-enabled-signed.read").performScrollTo().performClick()
        composeRule.onNodeWithTag("agent-plugin-goal-signed.read").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("agent-plugin-run-signed.read").assertIsNotEnabled()
    }

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

    @Test
    fun signatureConfirmationCanBeCancelledAndUpgradeRollbackRemainDisabled() {
        val store = TestStore()
        val registry = AgentPluginRegistry(store)
        val key = AgentPluginSigningFixture.key()
        val signed = AgentPluginSigningFixture.sign(key = key)
        composeRule.setContent { MaterialTheme { AgentPluginManagementPage(registry, {}) } }
        composeRule.onNodeWithTag("agent-plugin-manifest-input")
            .performTextInput(AgentPluginJsonCodec.encodeSignedManifest(signed))
        composeRule.onNodeWithTag("agent-plugin-verify-signature").performScrollTo().performClick()
        composeRule.onNodeWithTag("agent-plugin-publisher-confirmation").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(registry.list().isEmpty()) }
        composeRule.onNodeWithTag("agent-plugin-cancel-publisher").performClick()
        composeRule.runOnIdle { assertTrue(registry.list().isEmpty()); assertEquals(null, store.state) }
        composeRule.onNodeWithTag("agent-plugin-verify-signature").performClick()
        composeRule.onNodeWithTag("agent-plugin-confirm-publisher").performClick()
        composeRule.runOnIdle { assertFalse(registry.get("signed.read")!!.enabled) }
        composeRule.onNodeWithTag("agent-plugin-enabled-signed.read").performScrollTo().performClick()
        val upgraded = AgentPluginSigningFixture.sign(signed.manifest.copy(version = "2.0.0"), key)
        composeRule.onNodeWithTag("agent-plugin-manifest-input").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("agent-plugin-manifest-input").performTextInput(AgentPluginJsonCodec.encodeSignedManifest(upgraded))
        composeRule.onNodeWithTag("agent-plugin-verify-signature").performScrollTo().performClick()
        composeRule.onNodeWithTag("agent-plugin-confirm-publisher").performClick()
        composeRule.runOnIdle { assertFalse(registry.get("signed.read")!!.enabled) }
        composeRule.onNodeWithTag("agent-plugin-enabled-signed.read").performScrollTo().performClick()
        composeRule.onNodeWithTag("agent-plugin-rollback-signed.read-1.0.0").performScrollTo().performClick()
        composeRule.onNodeWithTag("agent-plugin-confirm-rollback").performClick()
        composeRule.runOnIdle {
            val restored = AgentPluginRegistry(TestStore(store.state)).get("signed.read")!!
            assertEquals("1.0.0", restored.manifest.version)
            assertEquals("2.0.0", restored.highestSignedManifest!!.manifest.version)
            assertFalse(restored.enabled)
        }
    }

    @Test
    fun failedSignedWriteFreezesUiWithoutSavingPublisherTrust() {
        val store = TestStore(failWrites = true)
        val registry = AgentPluginRegistry(store)
        val signed = AgentPluginSigningFixture.sign()
        composeRule.setContent { MaterialTheme { AgentPluginManagementPage(registry, {}) } }
        composeRule.onNodeWithTag("agent-plugin-manifest-input").performTextInput(AgentPluginJsonCodec.encodeSignedManifest(signed))
        composeRule.onNodeWithTag("agent-plugin-verify-signature").performScrollTo().performClick()
        composeRule.onNodeWithTag("agent-plugin-confirm-publisher").performClick()
        composeRule.onNodeWithTag("agent-plugin-state-error").assertExists()
        composeRule.runOnIdle { assertTrue(registry.list().isEmpty()); assertEquals(null, store.state) }
    }

    @Test
    fun oldRemovalDialogCannotRemoveConcurrentSignedUpgrade() {
        val registry = AgentPluginRegistry(TestStore())
        val key = AgentPluginSigningFixture.key()
        val first = AgentPluginSigningFixture.sign(key = key)
        registry.installSigned(first, AgentPluginSigning.verify(first))
        composeRule.setContent { MaterialTheme { AgentPluginManagementPage(registry, {}) } }
        composeRule.onNodeWithTag("agent-plugin-delete-signed.read").performScrollTo().performClick()
        composeRule.runOnIdle {
            registry.installSigned(AgentPluginSigningFixture.sign(first.manifest.copy(version = "2.0.0"), key))
        }
        composeRule.onNodeWithTag("agent-plugin-confirm-delete").performClick()
        composeRule.runOnIdle { assertEquals("2.0.0", registry.get("signed.read")!!.manifest.version) }
        composeRule.onNodeWithTag("agent-plugin-signed.read").performScrollTo().assertExists()
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
