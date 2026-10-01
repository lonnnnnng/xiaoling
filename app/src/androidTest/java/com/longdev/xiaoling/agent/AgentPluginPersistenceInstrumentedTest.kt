package com.longdev.xiaoling.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.longdev.xiaoling.storage.SharedPreferencesAgentPluginStateStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject

class AgentPluginPersistenceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun enabledPluginSurvivesAndroidProcessScopedRegistryRecreation() {
        val store = SharedPreferencesAgentPluginStateStore(context)
        val first = AgentPluginRegistry(store)
        val manifest = manifest()
        assertTrue(first.install(manifest) is AgentPluginInstallResult.Installed)
        assertNotNull(first.setEnabled(manifest.id, true))

        val recreated = AgentPluginRegistry(SharedPreferencesAgentPluginStateStore(context))
        assertEquals(true, recreated.get(manifest.id)?.enabled)
        assertEquals(manifest, recreated.get(manifest.id)?.manifest)
    }

    @Test
    fun corruptedStateDoesNotEnableAnyPlugin() {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("state", "{broken")
            .commit()

        val registry = AgentPluginRegistry(SharedPreferencesAgentPluginStateStore(context))
        assertTrue(registry.list().isEmpty())
        assertNotNull(registry.persistenceFailureReason())
        assertTrue(registry.install(manifest()) is AgentPluginInstallResult.Rejected)
    }

    @Test
    fun unsupportedApiVersionDoesNotEnablePluginAfterDeviceRestore() {
        val encoded = AgentPluginJsonCodec.encode(listOf(InstalledAgentPlugin(manifest())))
        val root = JSONObject(encoded)
        root.getJSONArray("plugins")
            .getJSONObject(0)
            .getJSONObject("manifest")
            .put("apiVersion", AgentPluginPolicy.SUPPORTED_API_VERSION + 1)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("state", root.toString())
            .commit()

        val registry = AgentPluginRegistry(SharedPreferencesAgentPluginStateStore(context))
        assertTrue(registry.list().isEmpty())
        assertNotNull(registry.persistenceFailureReason())
    }

    private fun manifest() = AgentPluginManifest(
        id = "device.read",
        version = "1.0.0",
        name = "设备只读插件",
        description = "声明只读能力",
        publisher = "long",
        capabilities = setOf("device.snapshot"),
        permissions = setOf(AgentPluginPermission.READ_ONLY_CAPABILITIES),
    )

    private companion object {
        const val PREFERENCES = "xiaoling_agent_plugins"
    }
}
