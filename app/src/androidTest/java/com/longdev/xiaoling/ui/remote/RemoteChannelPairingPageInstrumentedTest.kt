package com.longdev.xiaoling.ui.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.longdev.xiaoling.agent.RemoteChannelPairingPolicy
import com.longdev.xiaoling.agent.RemoteChannelPairingRegistry
import com.longdev.xiaoling.agent.RemoteChannelPairingStateStore
import com.longdev.xiaoling.agent.RemoteChannelEnvelope
import com.longdev.xiaoling.agent.RemoteChannelInboxGateway
import com.longdev.xiaoling.agent.RemoteChannelInboxSnapshot
import com.longdev.xiaoling.agent.RemoteChannelInboxStateStore
import com.longdev.xiaoling.agent.RemoteChannelPolicy
import com.longdev.xiaoling.agent.RemoteChannelPullService
import com.longdev.xiaoling.agent.RemoteChannelPullSourceRegistry
import com.longdev.xiaoling.agent.RemoteChannelPullSourceStateStore
import com.longdev.xiaoling.agent.RemoteChannelHttpsPuller
import com.longdev.xiaoling.agent.AndroidKeystoreRemoteChannelReceiverSigner
import com.longdev.xiaoling.agent.RemoteChannelReceiverAuthProtocol
import com.longdev.xiaoling.agent.RemoteChannelBackgroundRegistry
import com.longdev.xiaoling.agent.RemoteChannelBackgroundStateStore
import com.longdev.xiaoling.agent.RemoteChannelBackgroundController
import com.longdev.xiaoling.agent.RemoteChannelBackgroundScheduler
import com.longdev.xiaoling.agent.RemoteChannelBackgroundEntry
import com.longdev.xiaoling.agent.RemoteChannelInboxNotifier
import com.longdev.xiaoling.agent.RemoteChannelPullReliabilityPolicy
import androidx.compose.ui.test.assertIsEnabled
import java.security.KeyStore
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import kotlinx.coroutines.runBlocking
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.json.JSONObject
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class RemoteChannelPairingPageInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun receiverManifestExportRestoresPublicBindingBeforeWritingSelectedFile() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val peer = RemoteChannelPairingPolicy.prepare("ui-export", "sender", "key-1", "公钥导出测试", Base64.getEncoder().encodeToString(keys.public.encoded))
        val pairs = RemoteChannelPairingRegistry(TestStore()).apply { confirm(peer, peer.fingerprintSha256) }
        val sources = RemoteChannelPullSourceRegistry(TestSourceStore(), pairs)
        sources.save(peer, "https://ui-export.example/batch")
        val inbox = RemoteChannelInboxGateway(TestInboxStore(), pairs)
        val uuid = UUID.randomUUID().toString()
        val signer = AndroidKeystoreRemoteChannelReceiverSigner("remote_receiver_export_${uuid}_")
        val service = RemoteChannelPullService(pairs, sources, inbox, receiverIdentity = signer)
        val auth = runBlocking { service.createReceiver(peer) }
        var pendingCode: Int? = null
        var fileName: String? = null
        val results = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                pendingCode = requestCode; fileName = input as String
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = results }
        val restoration = StateRestorationTester(composeRule)
        val output = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "receiver-public-export-$uuid.json")
        require(!output.exists())
        var completed = false
        try {
            restoration.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme { RemoteChannelPairingPage(pairs, onBack = {}, inboxGateway = inbox, pullSources = sources, pullService = service) }
            } }
            composeRule.onNodeWithTag("remote-receiver-export-ui-export-sender-key-1").performScrollTo().performClick()
            composeRule.waitUntil(5_000) { pendingCode != null }
            assertEquals("xiaoling-receiver-${auth.receiverId}.json", fileName)
            // long: 不启动真实文件选择器，模拟其返回前页面恢复；真实回调仍通过 ContentResolver 写入独立缓存文件。
            restoration.emulateSavedInstanceStateRestore()
            composeRule.runOnIdle { results.dispatchResult(pendingCode!!, Activity.RESULT_OK, Intent().setData(Uri.fromFile(output))) }
            composeRule.waitUntil(5_000) { runCatching {
                RemoteChannelReceiverAuthProtocol.decodeManifest(JSONObject(output.readText(Charsets.UTF_8))) == auth
            }.getOrDefault(false) }
            composeRule.waitUntil(5_000) { runCatching {
                composeRule.onNodeWithTag("remote-receiver-export-ui-export-sender-key-1").assertIsEnabled()
            }.isSuccess }
            assertEquals(auth, RemoteChannelReceiverAuthProtocol.decodeManifest(JSONObject(output.readText(Charsets.UTF_8))))
            assertTrue(!output.readText(Charsets.UTF_8).contains("PRIVATE KEY"))
            completed = true
        } finally {
            if (completed) {
                assertTrue(output.delete())
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                keyStore.deleteEntry(signer.alias(auth))
                assertFalse(keyStore.containsAlias(signer.alias(auth)))
            }
        }
    }

    @Test fun explicitReceiverCreationStopsBackgroundAnd403CannotFallBackToGet() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val peer = RemoteChannelPairingPolicy.prepare("ui-auth", "sender", "key-1", "接收认证测试", Base64.getEncoder().encodeToString(keys.public.encoded))
        val pairs = RemoteChannelPairingRegistry(TestStore()).apply {
            confirm(peer, peer.fingerprintSha256); setEnabled(peer.channelId, peer.senderId, peer.keyId, true)
        }
        val sources = RemoteChannelPullSourceRegistry(TestSourceStore(), pairs)
        val source = sources.save(peer, "https://ui-auth.example/batch")
        val inbox = RemoteChannelInboxGateway(TestInboxStore(), pairs)
        val prefix = "remote_receiver_ui_" + UUID.randomUUID().toString()
        val signer = AndroidKeystoreRemoteChannelReceiverSigner(prefix + "_")
        val background = RemoteChannelBackgroundRegistry(object : RemoteChannelBackgroundStateStore {
            var raw: String? = null
            override fun loadState() = raw
            override fun saveState(state: String) { raw = state }
        })
        background.enable(peer, source)
        var cancelled = 0; var calls = 0
        val controller = RemoteChannelBackgroundController(background, pairs, sources, object : RemoteChannelBackgroundScheduler {
            override suspend fun schedule(entry: RemoteChannelBackgroundEntry) {}
            override suspend fun cancel(identity: String) { cancelled++ }
        }, object : RemoteChannelInboxNotifier {
            override fun show(identity: String) = false
            override fun cancel(identity: String) {}
        })
        val puller = RemoteChannelHttpsPuller(client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); calls++
            assertEquals("POST", request.method); assertEquals("1", request.header("X-Xiaoling-Receiver-Auth"))
            val envelope = JSONObject(okio.Buffer().apply { request.body!!.writeTo(this) }.readUtf8())
            val proof = envelope.getJSONObject("proof")
            val auth = sources.sourceFor(peer)!!.receiverAuthorization!!
            assertEquals(RemoteChannelReceiverAuthProtocol.sha256(RemoteChannelReceiverAuthProtocol.canonicalRequestBytes(envelope.getJSONObject("request"))),
                proof.getString("requestDigestSha256"))
            assertTrue(Signature.getInstance("SHA256withECDSA").run {
                initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(auth.publicKeySpkiBase64))))
                update(RemoteChannelReceiverAuthProtocol.canonicalProofBytes(proof)); verify(Base64.getUrlDecoder().decode(proof.getString("signature")))
            })
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(403).message("fixture rejected")
                .header("Content-Type", "application/json").body("{}".toResponseBody("application/json".toMediaType())).build()
        }.build())
        val service = RemoteChannelPullService(pairs, sources, inbox, puller, receiverIdentity = signer)
        var completed = false
        try {
            composeRule.setContent { MaterialTheme { RemoteChannelPairingPage(pairs, onBack = {}, inboxGateway = inbox,
                pullSources = sources, pullService = service, backgroundController = controller) } }
            val tag = "ui-auth-sender-key-1"
            composeRule.onNodeWithTag("remote-receiver-export-$tag").performScrollTo().assertIsNotEnabled()
            composeRule.onNodeWithTag("remote-receiver-create-$tag").performScrollTo().performClick()
            composeRule.runOnIdle { assertEquals(false, sources.sourceFor(peer)!!.receiverAuthRequired) }
            composeRule.onNodeWithTag("remote-receiver-confirm-create").performClick()
            composeRule.waitUntil(5_000) { sources.sourceFor(peer)?.receiverAuthorization != null }
            composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("remote-receiver-fingerprint-$tag").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("remote-receiver-fingerprint-$tag").performScrollTo().assertExists()
            composeRule.onNodeWithTag("remote-receiver-create-$tag").performScrollTo().assertIsNotEnabled()
            composeRule.onNodeWithTag("remote-receiver-export-$tag").performScrollTo().assertIsEnabled()
            assertEquals(false, background.get(RemoteChannelPullReliabilityPolicy.sourceIdentity(peer))!!.enabled)
            assertTrue(cancelled >= 1)
            val auth = runBlocking { service.exportReceiver(peer) }
            assertEquals(auth, RemoteChannelReceiverAuthProtocol.decodeManifest(JSONObject(RemoteChannelReceiverAuthProtocol.manifest(auth))))
            composeRule.onNodeWithTag("remote-source-pull-$tag").performScrollTo().performClick()
            composeRule.waitUntil(5_000) { calls == 1 }
            composeRule.waitUntil(5_000) { runCatching { composeRule.onNodeWithTag("remote-source-pull-$tag").assertIsEnabled() }.isSuccess }
            composeRule.onNodeWithTag("remote-source-pull-$tag").performScrollTo().assertIsEnabled()
            assertEquals(1, calls)
            assertTrue((inbox.status() as com.longdev.xiaoling.agent.RemoteChannelInboxStatus.Ready).pending.isEmpty())
            completed = true
        } finally {
            if (completed) {
                val auth = sources.sourceFor(peer)!!.receiverAuthorization!!
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                keyStore.deleteEntry(signer.alias(auth))
                assertFalse(keyStore.containsAlias(signer.alias(auth)))
            }
        }
    }

    @Test
    fun registeredHttpsSourceCanPullOneSignedMessageIntoPendingList() {
        val now = System.currentTimeMillis()
        val keyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val registry = RemoteChannelPairingRegistry(TestStore())
        val peer = RemoteChannelPairingPolicy.prepare(
            "channel", "sender", "paired", "测试来源",
            Base64.getEncoder().encodeToString(keyPair.public.encoded),
        )
        registry.confirm(peer, peer.fingerprintSha256)
        registry.setEnabled("channel", "sender", "paired", true)
        val gateway = RemoteChannelInboxGateway(TestInboxStore(), registry) { now }
        val sources = RemoteChannelPullSourceRegistry(TestSourceStore(), registry)
        val unsigned = RemoteChannelEnvelope(
            "channel", "network-message", "sender", "conversation", "网络待确认正文", now,
            nonce = "nonce-network-1234567890123456", keyId = "paired",
        )
        val signed = unsigned.copy(signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(RemoteChannelPolicy.canonicalBytes(unsigned))
            Base64.getUrlEncoder().withoutPadding().encodeToString(sign())
        })
        val raw = JSONObject().put("channelId", signed.channelId).put("messageId", signed.messageId)
            .put("senderId", signed.senderId).put("conversationKey", signed.conversationKey)
            .put("text", signed.text).put("receivedAtMillis", signed.receivedAtMillis)
            .put("nonce", signed.nonce).put("keyId", signed.keyId)
            .put("signature", signed.signature).toString()
        val puller = RemoteChannelHttpsPuller(client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "application/json")
                .body(raw.toResponseBody("application/json".toMediaType())).build()
        }).build())
        composeRule.setContent {
            MaterialTheme {
                RemoteChannelPairingPage(
                    registry = registry, onBack = {}, inboxGateway = gateway,
                    pullSources = sources, pullService = RemoteChannelPullService(registry, sources, gateway, puller),
                )
            }
        }
        val tag = "channel-sender-paired"
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("remote-inbox-empty").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("remote-source-url-$tag").performScrollTo().performTextInput("https://inbox.example/next")
        composeRule.onNodeWithTag("remote-source-save-$tag").performScrollTo().performClick()
        composeRule.onNodeWithTag("remote-source-pull-$tag").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("remote-inbox-item-channel-sender-network-message")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("network-message", (gateway.status() as com.longdev.xiaoling.agent.RemoteChannelInboxStatus.Ready)
            .pending.single().draft.messageId)
    }

    @Test
    fun previewConfirmEnableAndRevokeKeepPublicKeyBoundToOneSender() {
        val publicKey = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public
        val encoded = Base64.getEncoder().encodeToString(publicKey.encoded)
        val candidate = RemoteChannelPairingPolicy.prepare("channel", "sender", "key-1", "测试来源", encoded)
        val registry = RemoteChannelPairingRegistry(TestStore())
        composeRule.setContent { MaterialTheme { RemoteChannelPairingPage(registry, onBack = {}) } }

        composeRule.onNodeWithTag("remote-pairing-channel").performTextInput("channel")
        composeRule.onNodeWithTag("remote-pairing-sender").performTextInput("sender")
        composeRule.onNodeWithTag("remote-pairing-key-id").performTextInput("key-1")
        composeRule.onNodeWithTag("remote-pairing-source").performTextInput("测试来源")
        composeRule.onNodeWithTag("remote-pairing-public-key").performTextInput(encoded)
        composeRule.onNodeWithTag("remote-pairing-preview").performScrollTo().performClick()
        composeRule.onNodeWithTag("remote-pairing-fingerprint").performScrollTo().assertExists()
        composeRule.onNodeWithTag("remote-pairing-confirm").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("remote-pairing-confirm-fingerprint")
            .performScrollTo().performTextInput(candidate.fingerprintSha256)
        composeRule.onNodeWithTag("remote-pairing-confirm").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(false, registry.list().single().enabled) }

        composeRule.onNodeWithTag("remote-peer-enabled-channel-sender-key-1").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(registry.list().single().enabled) }
        composeRule.onNodeWithTag("remote-peer-revoke-channel-sender-key-1").performScrollTo().performClick()
        composeRule.onNodeWithTag("remote-pairing-confirm-revoke").performClick()
        composeRule.runOnIdle { assertTrue(registry.list().isEmpty()) }
    }

    @Test
    fun signedImportListsPendingAndOpenOrIgnoreRemovesDurableBody() {
        val now = System.currentTimeMillis()
        val keyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val registry = RemoteChannelPairingRegistry(TestStore())
        val candidate = RemoteChannelPairingPolicy.prepare(
            "channel", "sender", "paired", "测试来源",
            Base64.getEncoder().encodeToString(keyPair.public.encoded),
        )
        registry.confirm(candidate, candidate.fingerprintSha256)
        registry.setEnabled("channel", "sender", "paired", true)
        val store = TestInboxStore()
        val gateway = RemoteChannelInboxGateway(store, registry) { now }
        var stagedMessageId: String? = null
        composeRule.setContent {
            MaterialTheme {
                RemoteChannelPairingPage(
                    registry = registry,
                    onBack = {},
                    inboxGateway = gateway,
                    onOpenPending = { draft -> stagedMessageId = draft.messageId; true },
                    onIgnorePending = { draft -> gateway.discard(draft) },
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("remote-inbox-empty").fetchSemanticsNodes().isNotEmpty()
        }

        fun import(id: String) {
            val unsigned = RemoteChannelEnvelope(
                channelId = "channel", messageId = id, senderId = "sender",
                conversationKey = "conversation", text = "待确认正文-$id", receivedAtMillis = now,
                nonce = "nonce-$id-1234567890123456", keyId = "paired",
            )
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(RemoteChannelPolicy.canonicalBytes(unsigned))
                Base64.getUrlEncoder().withoutPadding().encodeToString(sign())
            }
            val message = unsigned.copy(signature = signature)
            val raw = JSONObject()
                .put("channelId", message.channelId).put("messageId", message.messageId)
                .put("senderId", message.senderId).put("conversationKey", message.conversationKey)
                .put("text", message.text).put("receivedAtMillis", message.receivedAtMillis)
                .put("nonce", message.nonce).put("keyId", message.keyId)
                .put("signature", message.signature).toString()
            composeRule.onNodeWithTag("remote-inbox-import-json").performScrollTo().performTextInput(raw)
            composeRule.onNodeWithTag("remote-inbox-import").performScrollTo().performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("remote-inbox-item-channel-sender-$id")
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }

        import("message-1")
        composeRule.onNodeWithTag("remote-inbox-open-channel-sender-message-1").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { stagedMessageId == "message-1" }
        assertEquals(1, (gateway.status() as com.longdev.xiaoling.agent.RemoteChannelInboxStatus.Ready).pending.size)
        val staged = (gateway.status() as com.longdev.xiaoling.agent.RemoteChannelInboxStatus.Ready).pending.single().draft
        assertTrue(gateway.discard(staged))
        composeRule.onNodeWithTag("remote-inbox-refresh").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("remote-inbox-empty").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, (gateway.status() as com.longdev.xiaoling.agent.RemoteChannelInboxStatus.Ready).pending.size)

        import("message-2")
        composeRule.onNodeWithTag("remote-inbox-ignore-channel-sender-message-2").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("remote-inbox-empty").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, (RemoteChannelInboxGateway(store, registry) { now }.status()
            as com.longdev.xiaoling.agent.RemoteChannelInboxStatus.Ready).pending.size)
    }

    private class TestStore(var state: String? = null) : RemoteChannelPairingStateStore {
        override fun loadState(): String? = state
        override fun saveState(state: String) { this.state = state }
    }

    private class TestInboxStore : RemoteChannelInboxStateStore {
        private var snapshot: RemoteChannelInboxSnapshot? = null
        override fun loadSnapshot(): RemoteChannelInboxSnapshot? = snapshot
        override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) { this.snapshot = snapshot }
    }

    private class TestSourceStore : RemoteChannelPullSourceStateStore {
        private var state: String? = null
        override fun loadState(): String? = state
        override fun saveState(state: String) { this.state = state }
    }
}
