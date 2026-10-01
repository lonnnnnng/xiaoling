package com.longdev.xiaoling.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class SecondGroupFoundationTest {
    @Test
    fun remoteChannelAcceptsAllowlistedMessageOnlyOnceAndKeepsItAsDraft() {
        val inbox = InMemoryRemoteChannelInbox(
            RemoteChannelAllowlist("telegram", setOf("user-1")),
        )
        val message = RemoteChannelEnvelope(
            channelId = "telegram",
            messageId = "42",
            senderId = "user-1",
            conversationKey = "chat-9",
            text = "  你好\r\n请先查看任务  ",
            receivedAtMillis = 1_000L,
        )

        val accepted = inbox.receive(message) as RemoteChannelReceiveResult.Accepted
        assertEquals("你好\n请先查看任务", accepted.draft.payload.text)
        assertTrue(accepted.draft.requiresForegroundConfirmation)
        assertEquals(null, accepted.draft.payload.imageUri)
        assertEquals(
            RemoteChannelRejectionReason.DUPLICATE_MESSAGE,
            (inbox.receive(message) as RemoteChannelReceiveResult.Rejected).reason,
        )
    }

    @Test
    fun remoteChannelRejectsUnknownSenderAndOversizedText() {
        val inbox = InMemoryRemoteChannelInbox(RemoteChannelAllowlist("loopback", setOf("known")))
        val unknown = inbox.receive(
            RemoteChannelEnvelope("loopback", "1", "unknown", "chat", "hello", 1L),
        )
        assertEquals(RemoteChannelRejectionReason.SENDER_NOT_ALLOWED, (unknown as RemoteChannelReceiveResult.Rejected).reason)

        val oversized = inbox.receive(
            RemoteChannelEnvelope("loopback", "2", "known", "chat", "x".repeat(RemoteChannelPolicy.MAX_TEXT_CHARS + 1), 1L),
        )
        assertEquals(RemoteChannelRejectionReason.TEXT_TOO_LONG, (oversized as RemoteChannelReceiveResult.Rejected).reason)
    }

    @Test
    fun remoteChannelDedupeSurvivesInboxRecreation() {
        val store = FakeDedupeStore()
        val message = RemoteChannelEnvelope("telegram", "persisted-1", "user-1", "chat", "hello", 1L)
        val first = InMemoryRemoteChannelInbox(
            RemoteChannelAllowlist("telegram", setOf("user-1")),
            dedupeStore = store,
        )
        assertTrue(first.receive(message) is RemoteChannelReceiveResult.Accepted)

        val recreated = InMemoryRemoteChannelInbox(
            RemoteChannelAllowlist("telegram", setOf("user-1")),
            dedupeStore = store,
        )
        assertEquals(
            RemoteChannelRejectionReason.DUPLICATE_MESSAGE,
            (recreated.receive(message) as RemoteChannelReceiveResult.Rejected).reason,
        )
    }

    @Test
    fun remoteChannelRejectsWhenDedupeCannotBePersisted() {
        val store = FakeDedupeStore(failWrites = true)
        val inbox = InMemoryRemoteChannelInbox(
            RemoteChannelAllowlist("telegram", setOf("user-1")),
            dedupeStore = store,
        )
        val result = inbox.receive(RemoteChannelEnvelope("telegram", "1", "user-1", "chat", "hello", 1L))
        assertEquals(
            RemoteChannelRejectionReason.DEDUPE_PERSISTENCE_FAILURE,
            (result as RemoteChannelReceiveResult.Rejected).reason,
        )
        assertTrue(store.lastKeys.isEmpty())
    }

    @Test
    fun signedRemoteChannelAcceptsRotatedKeysAndRejectsNonceReplay() {
        val now = 1_000_000L
        val authenticator = HmacRemoteChannelAuthenticator(
            keys = mapOf(
                "current" to ByteArray(RemoteChannelPolicy.MIN_HMAC_KEY_BYTES) { 1 },
                "previous" to ByteArray(RemoteChannelPolicy.MIN_HMAC_KEY_BYTES) { 2 },
            ),
            maxAgeMillis = 1_000L,
            maxFutureSkewMillis = 100L,
        )
        val unsigned = RemoteChannelEnvelope(
            channelId = "telegram",
            messageId = "signed-1",
            senderId = "user-1",
            conversationKey = "chat",
            text = "签名消息",
            receivedAtMillis = now,
            nonce = "n".repeat(RemoteChannelPolicy.MIN_NONCE_CHARS),
            keyId = "previous",
        )
        val signed = unsigned.copy(signature = authenticator.sign(unsigned, "previous"))
        val inbox = InMemoryRemoteChannelInbox(
            allowlist = RemoteChannelAllowlist("telegram", setOf("user-1")),
            authenticator = authenticator,
            clock = { now },
        )

        assertTrue(inbox.receive(signed) is RemoteChannelReceiveResult.Accepted)
        val sameNonceDifferentMessage = signed.copy(
            messageId = "signed-2",
            signature = authenticator.sign(signed.copy(messageId = "signed-2"), "previous"),
        )
        assertEquals(
            RemoteChannelRejectionReason.DUPLICATE_MESSAGE,
            (inbox.receive(sameNonceDifferentMessage) as RemoteChannelReceiveResult.Rejected).reason,
        )
    }

    @Test
    fun signedRemoteChannelRejectsMutationMissingSignatureUnknownKeyAndTimeOutsideWindow() {
        val now = 2_000_000L
        val authenticator = HmacRemoteChannelAuthenticator(
            keys = mapOf("current" to ByteArray(RemoteChannelPolicy.MIN_HMAC_KEY_BYTES) { 3 }),
            maxAgeMillis = 1_000L,
            maxFutureSkewMillis = 100L,
        )
        val unsigned = RemoteChannelEnvelope(
            channelId = "loopback",
            messageId = "signed-1",
            senderId = "known",
            conversationKey = "chat",
            text = "原文",
            receivedAtMillis = now,
            nonce = "x".repeat(RemoteChannelPolicy.MIN_NONCE_CHARS),
            keyId = "current",
        )
        val signed = unsigned.copy(signature = authenticator.sign(unsigned, "current"))
        fun inbox() = InMemoryRemoteChannelInbox(
            allowlist = RemoteChannelAllowlist("loopback", setOf("known")),
            authenticator = authenticator,
            clock = { now },
        )

        assertEquals(
            RemoteChannelRejectionReason.INVALID_SIGNATURE,
            (inbox().receive(signed.copy(text = "篡改")) as RemoteChannelReceiveResult.Rejected).reason,
        )
        assertEquals(
            RemoteChannelRejectionReason.MISSING_SIGNATURE,
            (inbox().receive(signed.copy(signature = null)) as RemoteChannelReceiveResult.Rejected).reason,
        )
        assertEquals(
            RemoteChannelRejectionReason.UNKNOWN_KEY_ID,
            (inbox().receive(signed.copy(keyId = "retired")) as RemoteChannelReceiveResult.Rejected).reason,
        )
        val future = signed.copy(receivedAtMillis = now + 101L)
        assertEquals(
            RemoteChannelRejectionReason.TIMESTAMP_OUT_OF_WINDOW,
            (inbox().receive(future) as RemoteChannelReceiveResult.Rejected).reason,
        )
    }

    @Test
    fun signedRemoteChannelCanUseNonExportedKeyHandle() {
        val now = 3_000_000L
        val authenticator = HmacRemoteChannelAuthenticator.fromKeystore(
            keys = mapOf("current" to SecretKeySpec(ByteArray(RemoteChannelPolicy.MIN_HMAC_KEY_BYTES) { 4 }, "HmacSHA256")),
            maxAgeMillis = 1_000L,
            maxFutureSkewMillis = 100L,
        )
        val unsigned = RemoteChannelEnvelope(
            channelId = "loopback",
            messageId = "handle-1",
            senderId = "known",
            conversationKey = "chat",
            text = "handle",
            receivedAtMillis = now,
            nonce = "h".repeat(RemoteChannelPolicy.MIN_NONCE_CHARS),
            keyId = "current",
        )

        val signed = unsigned.copy(signature = authenticator.sign(unsigned, "current"))

        assertEquals(null, authenticator.verify(signed, now))
    }

    @Test
    fun aciDiscoversAndCallsOnlyExplicitSafeCapabilities() = runTest {
        val registry = SafeTestToolRegistry()
        val bridge = ReadOnlyAciCapabilityBridge(registry, setOf("safe.read"))
        val context = AciInvocationContext(
            AgentExecutionOrigin.FOREGROUND,
            AgentInvocationSource.DIRECT,
            allowedToolNames = setOf("safe.read"),
        )
        assertEquals(listOf("safe.read"), bridge.discover(context).map { it.id })
        val success = bridge.call(
            capabilityId = "safe.read",
            arguments = emptyMap(),
            context = context,
        )
        assertEquals(AciCallResult.Success("read"), success)

        val rejected = bridge.call(
            capabilityId = "safe.read",
            arguments = emptyMap(),
            context = context.copy(executionOrigin = AgentExecutionOrigin.BACKGROUND),
        )
        assertTrue(rejected is AciCallResult.Rejected)
        val notAllowed = bridge.call("safe.read", emptyMap(), context.copy(allowedToolNames = emptySet()))
        assertTrue(notAllowed is AciCallResult.Rejected)
    }

    @Test
    fun pluginManifestIsDeclarativeAndUpgradeStartsDisabled() {
        val registry = AgentPluginRegistry()
        val v1 = manifest("1.0.0")
        assertTrue(registry.install(v1) is AgentPluginInstallResult.Installed)
        assertFalse(registry.get("demo")!!.enabled)
        registry.setEnabled("demo", true)
        assertTrue(registry.get("demo")!!.enabled)

        val upgraded = registry.install(manifest("1.1.0")) as AgentPluginInstallResult.Installed
        assertFalse(upgraded.plugin.enabled)
        assertEquals("1.1.0", registry.get("demo")!!.manifest.version)
        assertTrue(registry.uninstall("demo"))
        assertEquals(null, registry.get("demo"))
    }

    @Test
    fun pluginRejectsVersionDowngradeAndExecutableMode() {
        val registry = AgentPluginRegistry()
        registry.install(manifest("2.0.0"))
        assertTrue(registry.install(manifest("1.9.9")) is AgentPluginInstallResult.Rejected)
        assertTrue(registry.install(manifest("2.0.0").copy(name = "篡改名称")) is AgentPluginInstallResult.Rejected)
        val invalid = manifest("2.1.0").copy(executionMode = AgentPluginExecutionMode.EXTERNAL_CODE)
        assertTrue(registry.install(invalid) is AgentPluginInstallResult.Rejected)
    }

    @Test
    fun pluginSourceFingerprintIsRequiredToBeStableAndHttpsBound() {
        val source = AgentPluginSource(
            url = "https://github.com/example/plugin",
            commit = "a".repeat(40),
            contentSha256 = "b".repeat(64),
        )
        val registry = AgentPluginRegistry()
        assertTrue(registry.install(manifest("1.0.0").copy(source = source)) is AgentPluginInstallResult.Installed)
        assertTrue(
            registry.install(
                manifest("1.0.0").copy(
                    source = source.copy(contentSha256 = "c".repeat(64)),
                ),
            ) is AgentPluginInstallResult.Rejected,
        )
        assertTrue(
            registry.install(
                manifest("1.1.0").copy(source = source.copy(url = "http://github.com/example/plugin")),
            ) is AgentPluginInstallResult.Rejected,
        )
    }

    private fun manifest(version: String) = AgentPluginManifest(
        id = "demo",
        version = version,
        name = "演示插件",
        description = "只声明能力，不加载代码",
        publisher = "long",
        capabilities = setOf("safe.read"),
        permissions = setOf(AgentPluginPermission.READ_ONLY_CAPABILITIES),
    )

    private class SafeTestToolRegistry : ToolRegistry {
        private val definition = ToolDefinition(
            name = "safe.read",
            description = "读取测试事实",
            risk = ToolRisk.SAFE,
        )

        override fun availableTools(): List<ToolDefinition> = listOf(definition)
        override fun definition(name: String): ToolDefinition? = definition.takeIf { it.name == name }
        override suspend fun execute(call: ToolCall): ToolExecutionResult = ToolExecutionResult(true, "read")
    }

    private class FakeDedupeStore(
        private val failWrites: Boolean = false,
    ) : RemoteChannelDedupeStore {
        var lastKeys: List<String> = emptyList()

        override fun loadKeys(): List<String> = lastKeys

        override fun saveKeys(keys: List<String>) {
            if (failWrites) error("write failed")
            lastKeys = keys
        }
    }
}
