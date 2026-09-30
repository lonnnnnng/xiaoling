package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class AgentPluginPersistenceTest {
    @Test
    fun enabledStateSurvivesRegistryRecreationWithStableJson() {
        val store = FakeStateStore()
        val first = AgentPluginRegistry(store)
        val manifest = manifest()

        assertTrue(first.install(manifest) is AgentPluginInstallResult.Installed)
        assertNotNull(first.setEnabled(manifest.id, true))
        val encoded = store.state
        assertEquals(encoded, AgentPluginJsonCodec.encode(AgentPluginJsonCodec.decode(encoded!!)))

        val recreated = AgentPluginRegistry(store)
        assertEquals(true, recreated.get(manifest.id)?.enabled)
        assertEquals(manifest, recreated.get(manifest.id)?.manifest)
        assertEquals(null, recreated.persistenceFailureReason())
    }

    @Test
    fun malformedAndUnknownEnumStateFailsClosed() {
        val store = FakeStateStore("{broken")
        val malformed = AgentPluginRegistry(store)
        assertTrue(malformed.list().isEmpty())
        assertNotNull(malformed.persistenceFailureReason())
        assertTrue(malformed.install(manifest()) is AgentPluginInstallResult.Rejected)
        assertEquals("{broken", store.state)

        store.state = """
            {"schemaVersion":1,"plugins":[{"manifest":{"id":"demo","version":"1.0.0","name":"演示","description":"声明","publisher":"long","capabilities":[],"permissions":[],"executionMode":"FUTURE_MODE","source":null},"enabled":true}]}
        """.trimIndent()
        val unknownMode = AgentPluginRegistry(store)
        assertTrue(unknownMode.list().isEmpty())
        assertNotNull(unknownMode.persistenceFailureReason())
        assertTrue(unknownMode.install(manifest()) is AgentPluginInstallResult.Rejected)
    }

    @Test
    fun persistenceFailureDoesNotChangeInMemoryRegistry() {
        val store = FakeStateStore(failWrites = true)
        val registry = AgentPluginRegistry(store)
        assertTrue(registry.install(manifest()) is AgentPluginInstallResult.Rejected)
        assertTrue(registry.list().isEmpty())
        assertNotNull(registry.persistenceFailureReason())
    }

    @Test
    fun unknownFieldsAndDuplicateIdsAreRejected() {
        val base = AgentPluginJsonCodec.encode(listOf(InstalledAgentPlugin(manifest())))
        val unknownField = base.replace("\"plugins\"", "\"unexpected\":true,\"plugins\"")
        assertTrue(runCatching { AgentPluginJsonCodec.decode(unknownField) }.isFailure)

        val baseJson = JSONObject(base)
        val pluginJson = baseJson.getJSONArray("plugins").getJSONObject(0)
        val duplicate = baseJson.put("plugins", JSONArray().put(pluginJson).put(pluginJson)).toString()
        assertTrue(runCatching { AgentPluginJsonCodec.decode(duplicate) }.isFailure)
    }

    @Test
    fun versionRollbackAndFingerprintDriftAreRejectedAfterRecreation() {
        val store = FakeStateStore()
        val installed = manifest().copy(version = "2.0.0")
        assertTrue(AgentPluginRegistry(store).install(installed) is AgentPluginInstallResult.Installed)

        val recreated = AgentPluginRegistry(store)
        assertTrue(recreated.install(manifest()) is AgentPluginInstallResult.Rejected)
        val changedSource = installed.copy(
            source = installed.source!!.copy(contentSha256 = "c".repeat(64)),
        )
        assertTrue(recreated.install(changedSource) is AgentPluginInstallResult.Rejected)
        assertEquals(installed, AgentPluginRegistry(store).get(installed.id)?.manifest)
    }

    private fun manifest() = AgentPluginManifest(
        id = "demo",
        version = "1.0.0",
        name = "演示插件",
        description = "只声明能力，不加载代码",
        publisher = "long",
        capabilities = setOf("safe.read", "safe.write"),
        permissions = setOf(AgentPluginPermission.READ_ONLY_CAPABILITIES),
        source = AgentPluginSource(
            url = "https://github.com/example/plugin",
            commit = "a".repeat(40),
            contentSha256 = "b".repeat(64),
        ),
    )

    private class FakeStateStore(
        var state: String? = null,
        private val failWrites: Boolean = false,
    ) : AgentPluginStateStore {
        override fun loadState(): String? = state

        override fun saveState(state: String) {
            if (failWrites) error("write failed")
            this.state = state
        }
    }
}
