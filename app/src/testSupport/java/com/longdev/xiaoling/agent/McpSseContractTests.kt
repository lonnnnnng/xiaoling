package com.longdev.xiaoling.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** long: JVM 与 Redmi 共用同一默认 OkHttp/socket 契约，不使用正式偏好或模型配置。 */
abstract class McpSseContractTests {
    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(10) }

    @Test fun concurrentHandshakeCatalogPaginationAndSixApis() = runBlocking {
        McpSseFixture().use { fixture ->
            fixture.paginate = true
            val config = fixture.config()
            val registry = McpSessionRegistry(McpFixtureStore(config))
            try {
                val client = registry.client("settings")
                val catalogs = (1..4).map { async(Dispatchers.IO) { client.listTools(config) } }.awaitAll()
                assertTrue(catalogs.all { it.map(McpToolDescriptor::name) == listOf("fixture.echo", "fixture.second") })
                assertEquals(1, fixture.getCount.get())
                assertEquals(1, fixture.methodCount("initialize"))
                assertEquals(2, fixture.methodCount("tools/list"))
                assertEquals(listOf("initialize", "notifications/initialized", "tools/list", "tools/list"), fixture.requests.take(4).map { it.getString("method") })
                assertEquals(McpConnectionState.READY, registry.state("settings", config.id))
                assertTrue(client.callTool(config, "fixture.echo", JSONObject().put("text", "hello"), catalogs.first()).contains("echo:hello"))
                assertEquals("memo://fixture", client.listResources(config).single().uri)
                assertEquals("fixture-resource", client.readResource(config, "memo://fixture").single().text)
                assertEquals("fixture-summary", client.listPrompts(config).single().name)
                assertTrue(client.getPrompt(config, "fixture-summary", mapOf("topic" to "test")).messages.single().contentJson.contains("fixture-prompt"))
                assertTrue(fixture.authorization.all { it == "Bearer synthetic-token" })
            } finally { registry.close() }
        }
    }

    @Test fun ownerIsolationAndConfigurationRevocation() = runBlocking {
        McpSseFixture().use { fixture ->
            val config = fixture.config()
            val registry = McpSessionRegistry(McpFixtureStore(config))
            try {
                val run = registry.client("run")
                val settings = registry.client("settings")
                run.listTools(config); settings.listTools(config)
                run.close()
                assertEquals(McpConnectionState.DISCONNECTED, registry.state("run", config.id))
                assertTrue(settings.isConnected(config))
                assertEquals(2, fixture.getCount.get())
                settings.listTools(config)
                registry.setEnabled(config.id, false)
                assertFalse(settings.isConnected(config))
                assertTrue(registry.availableTools("settings", config.id).isEmpty())
                assertTrue(runCatching { settings.listTools(config) }.isFailure)
                val enabled = registry.setEnabled(config.id, true)
                settings.listTools(enabled); run.listTools(enabled)
                registry.delete(config.id)
                assertFalse(settings.isConnected(enabled)); assertFalse(run.isConnected(enabled))
                assertTrue(runCatching { settings.listTools(enabled) }.isFailure)
            } finally { registry.close() }
        }
    }

    @Test fun directoryMustFinishBeforeReadyAndCancelledResultCannotReviveIt() = runBlocking {
        McpSseFixture().use { fixture ->
            fixture.stallMethod = "tools/list"
            val config = fixture.config()
            val registry = McpSessionRegistry(McpFixtureStore(config))
            try {
                val client = registry.client("run")
                val job = launch(Dispatchers.IO) { client.connect(config) }
                awaitCondition { fixture.methodCount("tools/list") == 1 }
                assertEquals(McpConnectionState.CONNECTING, registry.state("run", config.id))
                assertTrue(registry.availableTools("run", config.id).isEmpty())
                job.cancel(); withTimeout(5000) { job.join() }
                fixture.releaseStalled()
                assertEquals(McpConnectionState.DISCONNECTED, registry.state("run", config.id))
                fixture.stallMethod = null
                client.listTools(config)
                assertEquals(2, fixture.methodCount("initialize"))
            } finally { registry.close() }
        }
    }

    @Test fun cancellationAfterPostAckClosesSseAndDoesNotReplayCall() = runBlocking {
        McpSseFixture().use { fixture ->
            val config = fixture.config()
            val registry = McpSessionRegistry(McpFixtureStore(config))
            try {
                val client = registry.client("run")
                client.listTools(config)
                fixture.stallMethod = "tools/call"
                val job = launch(Dispatchers.IO) { client.callTool(config, "fixture.echo", JSONObject().put("text", "cancel")) }
                awaitCondition { fixture.methodCount("tools/call") == 1 }
                job.cancel(); withTimeout(5000) { job.join() }
                assertEquals(McpConnectionState.DISCONNECTED, registry.state("run", config.id))
                fixture.releaseStalled(); fixture.stallMethod = null
                client.listTools(config)
                assertEquals(2, fixture.methodCount("initialize"))
                assertEquals(1, fixture.methodCount("tools/call"))
            } finally { registry.close() }
        }
    }

    @Test fun disconnectedStreamRebuildsSchemaAndRejectsOldDescriptor() = runBlocking {
        McpSseFixture().use { fixture ->
            val config = fixture.config()
            val registry = McpSessionRegistry(McpFixtureStore(config))
            try {
                val client = registry.client("run")
                val old = client.listTools(config)
                fixture.closeStreams()
                awaitCondition { !client.isConnected(config) }
                fixture.schemaType = "integer"
                val current = client.listTools(config)
                assertNotEquals(old, current)
                assertTrue(runCatching { client.callTool(config, "fixture.echo", JSONObject().put("text", 1), old) }.isFailure)
                assertEquals(0, fixture.methodCount("tools/call"))
                assertEquals(2, fixture.methodCount("initialize"))
            } finally { registry.close() }
        }
    }

    @Test fun catalogNotificationAndWhitelistChangeInvalidateAvailability() = runBlocking {
        McpSseFixture().use { fixture ->
            val config = fixture.config()
            val registry = McpSessionRegistry(McpFixtureStore(config))
            try {
                val client = registry.client("run")
                client.listTools(config)
                fixture.notifyCatalogChanged()
                awaitCondition { !client.isConnected(config) }
                assertTrue(registry.availableTools("run", config.id).isEmpty())
                client.listTools(config)
                val disabled = registry.updateToolAccess(config.id, setOf("fixture.echo"), "fixture.echo", false)
                assertFalse(client.isConnected(disabled))
                client.listTools(disabled)
                assertTrue(registry.availableTools("run", config.id).isEmpty())
                assertTrue(runCatching { client.callTool(disabled, "fixture.echo", JSONObject().put("text", "blocked")) }.isFailure)
                assertEquals(0, fixture.methodCount("tools/call"))
            } finally { registry.close() }
        }
    }

    @Test fun editedCredentialsRevokeAllOwnersEvenWhenHashCodesCollide() = runBlocking {
        McpSseFixture().use { fixture ->
            val config = fixture.config().copy(bearerToken = "Aa")
            assertEquals("Aa".hashCode(), "BB".hashCode())
            val registry = McpSessionRegistry(McpFixtureStore(config))
            try {
                val client = registry.client("settings")
                client.listTools(config)
                val changed = registry.upsert(config.copy(bearerToken = "BB"))
                assertFalse(client.isConnected(config))
                assertNotEquals(mcpServerBinding(config), mcpServerBinding(changed))
                assertTrue(runCatching { client.listTools(config) }.isFailure)
                client.listTools(changed)
                assertFalse(client.isConnected(config))
                assertTrue(client.isConnected(changed))
                assertEquals(2, fixture.methodCount("initialize"))
                assertTrue(fixture.authorization.contains("Bearer BB"))
            } finally { registry.close() }
        }
    }

    @Test fun revocationBetweenRegistryCheckAndTransportDispatchCannotSubmitCall() = runBlocking {
        McpSseFixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val config = fixture.config()
            val registry = McpSessionRegistry(McpFixtureStore(config)) {
                val transport = StreamableHttpMcpClient()
                object : McpClient by transport {
                    override suspend fun callTool(server: McpServerConfig, name: String, arguments: JSONObject, knownTools: List<McpToolDescriptor>?): String {
                        entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                        return transport.callTool(server, name, arguments, knownTools)
                    }
                }
            }
            try {
                val client = registry.client("run")
                client.listTools(config)
                val call = async(Dispatchers.IO) { runCatching { client.callTool(config, "fixture.echo", JSONObject().put("text", "revoked")) } }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                registry.setEnabled(config.id, false)
                release.countDown()
                assertTrue(call.await().isFailure)
                assertEquals(1, fixture.methodCount("initialize"))
                assertEquals(0, fixture.methodCount("tools/call"))
            } finally { release.countDown(); registry.close() }
        }
    }

    @Test fun sse503RetryAfterZeroCannotReplaySubmittedToolCall() = runBlocking {
        McpSseFixture().use { fixture ->
            val config = fixture.config()
            val registry = McpSessionRegistry(McpFixtureStore(config))
            try {
                val client = registry.client("run")
                client.listTools(config)
                fixture.failToolPostWith503 = true
                assertTrue(runCatching { client.callTool(config, "fixture.echo", JSONObject().put("text", "once")) }.isFailure)
                assertEquals(1, fixture.methodCount("tools/call"))
            } finally { registry.close() }
        }
    }
}
