package com.longdev.xiaoling.ui.remote

import android.content.Context
import android.util.Base64
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.storage.*
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule

/** long: 公网门禁只接收外部 OpenSSL 生成的合成消息，所有配对、加密快照和退避都使用隔离前缀。 */
class RemoteChannelBatchHttpsInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun realBatchTlsCommitsMessagesCursorAndSignedAck() = runBlocking {
        val fixture = fixture("remote_batch_https_test_${UUID.randomUUID().toString().replace("-", "")}", "/batch")
        try {
            composeRule.setContent {
                MaterialTheme {
                    RemoteChannelPairingPage(registry = fixture.pairings, onBack = {}, inboxGateway = fixture.gateway(),
                        pullSources = fixture.sources, pullService = fixture.service)
                }
            }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("remote-inbox-empty").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("remote-source-pull-real-device-test-fixture-sender-fixture-key-1")
                .performScrollTo().performClick()
            composeRule.waitUntil(30_000) {
                composeRule.onAllNodesWithText("本次入箱 2 条，重复 0 条；送达回执已确认").fetchSemanticsNodes().isNotEmpty()
            }
            val restored = fixture.gateway()
            assertEquals(listOf("batch-message-1", "batch-message-2"),
                (restored.status() as RemoteChannelInboxStatus.Ready).pending.map { it.draft.messageId })
            val state = restored.batchState(fixture.source, fixture.peer)
            assertEquals("cursor-1", state.cursor)
            assertEquals(null, state.pendingAck)
            val raw = fixture.context.getSharedPreferences(fixture.inboxPreferences, 0).getString("snapshot", null)!!
            assertFalse(raw.contains("batch-message-1"))
            assertFalse(raw.contains("cursor-1"))
            assertFalse(raw.contains(state.receiverId))
            assertEquals(RemoteChannelPullSyncResult.Batch(0, 0, false), fixture.service.synchronize(fixture.peer))
            assertEquals(2, (restored.status() as RemoteChannelInboxStatus.Ready).pending.size)
        } finally { fixture.cleanup() }
    }

    @Test fun legacyCiphertextUpgradesWithoutLosingPendingDraftOrReplayIdentity() = runBlocking {
        val fixture = fixture("remote_batch_https_test_${UUID.randomUUID().toString().replace("-", "")}", "/next")
        try {
            assertTrue(fixture.service.pull(fixture.peer) is RemoteChannelReceiveResult.Accepted)
            val preferences = fixture.context.getSharedPreferences(fixture.inboxPreferences, 0)
            val envelope = JSONObject(preferences.getString("snapshot", null)!!)
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(fixture.alias, null) as SecretKey
            val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
            decrypt.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)))
            val plaintext = JSONObject(String(decrypt.doFinal(Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)), Charsets.UTF_8))
            // long: 使用同一测试 Keystore 密钥构造旧 v1 密文，确认升级只补批次状态，不清空原消息与重放身份。
            plaintext.put("schemaVersion", 1).remove("batchStates")
            plaintext.remove("auditEntries")
            val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
            encrypt.init(Cipher.ENCRYPT_MODE, key)
            envelope.put("iv", Base64.encodeToString(encrypt.iv, Base64.NO_WRAP))
                .put("ciphertext", Base64.encodeToString(encrypt.doFinal(plaintext.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
            assertTrue(preferences.edit().putString("snapshot", envelope.toString()).commit())
            assertEquals("real-https-20261014-1", (fixture.gateway().status() as RemoteChannelInboxStatus.Ready).pending.single().draft.messageId)
            assertEquals(RemoteChannelRejectionReason.DUPLICATE_MESSAGE,
                (fixture.service.pull(fixture.peer) as RemoteChannelReceiveResult.Rejected).reason)
            fixture.sources.save(fixture.peer, fixture.baseUrl + "/batch")
            assertEquals(RemoteChannelPullSyncResult.Batch(2, 0, false), fixture.service.synchronize(fixture.peer))
            assertEquals(3, (fixture.gateway().status() as RemoteChannelInboxStatus.Ready).pending.size)
        } finally { fixture.cleanup() }
    }

    @Test fun seedAckOutboxForIndependentProcessRestart() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("remoteBatchPhase") == "seed")
        val fixture = fixture(processPrefix(), "/batch-ack-once")
        assertEquals(RemoteChannelPullSyncResult.Batch(2, 0, true), fixture.service.synchronize(fixture.peer))
        assertNotNull(fixture.gateway().batchState(fixture.source, fixture.peer).pendingAck)
        assertTrue(runCatching { fixture.service.synchronize(fixture.peer) }.exceptionOrNull() is RemoteChannelPullDeferredException)
        println("STAGE285_BATCH phase=seed pid=${android.os.Process.myPid()}")
    }

    @Test fun restoreAckOutboxWithoutRedeliveringMessages() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("remoteBatchPhase") == "restore")
        val fixture = fixture(processPrefix(), "/batch-ack-once")
        val state = fixture.gateway().batchState(fixture.source, fixture.peer)
        assertEquals("cursor-1", state.cursor)
        assertEquals(listOf("batch-message-1", "batch-message-2"), state.pendingAck?.messageIds)
        val checkpoint = fixture.checkpoints.get(RemoteChannelPullReliabilityPolicy.sourceIdentity(fixture.peer))!!
        assertEquals(1, checkpoint.consecutiveFailures)
        assertEquals(RemoteChannelPullErrorCode.SERVER_5XX.name, checkpoint.lastErrorCode)
        val wait = (checkpoint.nextAttemptAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
        if (wait > 0) {
            assertTrue(runCatching { fixture.service.synchronize(fixture.peer) }.exceptionOrNull() is RemoteChannelPullDeferredException)
            Thread.sleep(wait + 25)
        }
        assertEquals(RemoteChannelPullSyncResult.Batch(0, 0, false), fixture.service.synchronize(fixture.peer))
        assertEquals(null, fixture.gateway().batchState(fixture.source, fixture.peer).pendingAck)
        assertEquals(2, (fixture.gateway().status() as RemoteChannelInboxStatus.Ready).pending.size)
        assertEquals(RemoteChannelPullSyncResult.Batch(0, 0, false), fixture.service.synchronize(fixture.peer))
        println("STAGE285_BATCH phase=restore pid=${android.os.Process.myPid()}")
        fixture.cleanup()
    }

    private fun processPrefix(): String {
        val prefix = InstrumentationRegistry.getArguments().getString("remoteBatchLedger").orEmpty()
        require(prefix.matches(Regex("remote_batch_https_process_test_[A-Za-z0-9_]{1,80}"))) { "必须指定隔离进程测试前缀" }
        return prefix
    }

    private fun fixture(prefix: String, path: String): RemoteBatchHttpsFixture {
        val args = InstrumentationRegistry.getArguments()
        val baseUrl = args.getString("remotePullBaseUrl").orEmpty().trimEnd('/')
        val publicKey = args.getString("remotePullPublicKey").orEmpty()
        assumeTrue("只在显式传入合成 HTTPS 来源与公钥时运行", baseUrl.isNotEmpty() && publicKey.isNotEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pairings = RemoteChannelPairingRegistry(SharedPreferencesRemoteChannelPairingStateStore(context, prefix + "_pairings"))
        val peer = RemoteChannelPairingPolicy.prepare("real-device-test", "fixture-sender", "fixture-key-1", "合成批次来源", publicKey)
        pairings.confirm(peer, peer.fingerprintSha256)
        pairings.setEnabled(peer.channelId, peer.senderId, peer.keyId, true)
        val sources = RemoteChannelPullSourceRegistry(SharedPreferencesRemoteChannelPullSourceStateStore(context, prefix + "_sources"), pairings)
        val source = sources.save(peer, baseUrl + path)
        val checkpoints = RemoteChannelPullCheckpointRegistry(SharedPreferencesRemoteChannelPullCheckpointStateStore(context, prefix + "_retry"))
        return RemoteBatchHttpsFixture(context, prefix, baseUrl, peer, pairings, sources, source, checkpoints)
    }
}

private class RemoteBatchHttpsFixture(
    val context: Context, val prefix: String, val baseUrl: String,
    val peer: RemoteChannelPeerCandidate, val pairings: RemoteChannelPairingRegistry,
    val sources: RemoteChannelPullSourceRegistry, val source: RemoteChannelPullSource,
    val checkpoints: RemoteChannelPullCheckpointRegistry,
) {
    val inboxPreferences = prefix + "_inbox"
    val alias = prefix + "_aes"
    fun gateway() = RemoteChannelInboxGateway(KeystoreRemoteChannelInboxStateStore(context, inboxPreferences, alias), pairings)
    val service = RemoteChannelPullService(pairings, sources, gateway(), checkpoints = checkpoints)
    fun cleanup() {
        require(prefix.startsWith("remote_batch_https_test_") || prefix.startsWith("remote_batch_https_process_test_"))
        listOf("_pairings", "_sources", "_inbox", "_retry").forEach { context.deleteSharedPreferences(prefix + it) }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }
}
