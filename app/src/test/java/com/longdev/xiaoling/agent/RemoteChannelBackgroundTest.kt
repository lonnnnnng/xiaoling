package com.longdev.xiaoling.agent

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteChannelBackgroundTest {
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val peer = RemoteChannelPairingPolicy.prepare("channel", "sender", "key-1", "后台测试", Base64.getEncoder().encodeToString(keys.public.encoded))
    private val pairings = RemoteChannelPairingRegistry(object : RemoteChannelPairingStateStore {
        var raw: String? = null
        override fun loadState() = raw
        override fun saveState(state: String) { raw = state }
    }).apply { confirm(peer, peer.fingerprintSha256); setEnabled(peer.channelId, peer.senderId, peer.keyId, true) }
    private val sourceStore = object : RemoteChannelPullSourceStateStore {
        var raw: String? = null
        var onLoad: (() -> Unit)? = null
        override fun loadState(): String? {
            val callback = onLoad
            onLoad = null
            callback?.invoke()
            return raw
        }
        override fun saveState(state: String) { raw = state }
    }
    private val sources = RemoteChannelPullSourceRegistry(sourceStore, pairings)
    private val source = sources.save(peer, "https://inbox.example/batch")
    private val store = Store()
    private val registry = RemoteChannelBackgroundRegistry(store) { 1_000_000 }
    private val identity = RemoteChannelPullReliabilityPolicy.sourceIdentity(peer)
    private val inbox = RemoteChannelInboxGateway(object : RemoteChannelInboxStateStore {
        var snapshot: RemoteChannelInboxSnapshot? = null
        override fun loadSnapshot() = snapshot
        override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) { this.snapshot = snapshot }
    }, pairings) { 1_000_000 }
    private val notifier = Notifier()

    @Test fun freshStateIsOffAndGenerationCannotSurviveDisableOrReenable() {
        assertNull(registry.get(identity))
        val first = registry.enable(peer, source)
        assertFalse(first.notificationsEnabled)
        registry.disable(identity)
        assertFalse(registry.permits(identity, first.generation))
        val next = RemoteChannelBackgroundRegistry(store).enable(peer, source)
        assertNotEquals(first.generation, next.generation)
        assertFalse(registry.permits(identity, first.generation))
        assertTrue(registry.permits(identity, next.generation))
    }

    @Test fun corruptOrFailedPermitWriteNeverStartsSystemWork() = runBlocking {
        var scheduled = 0
        val controller = controller(object : RemoteChannelBackgroundScheduler {
            override suspend fun schedule(entry: RemoteChannelBackgroundEntry) { scheduled++ }
            override suspend fun cancel(identity: String) {}
        })
        store.fail = true
        assertTrue(runCatching { controller.setEnabled(peer, true) }.isFailure)
        store.fail = false
        store.raw = "broken"
        assertTrue(runCatching { controller.setEnabled(peer, true) }.isFailure)
        assertEquals(0, scheduled)
    }

    @Test fun scheduleFailureRevokesPersistedPermitAndDisablePrecedesCancellation() = runBlocking {
        var cancellationSawDisabled = false
        val controller = controller(object : RemoteChannelBackgroundScheduler {
            override suspend fun schedule(entry: RemoteChannelBackgroundEntry) { error("enqueue failed") }
            override suspend fun cancel(identity: String) { cancellationSawDisabled = registry.get(identity)?.enabled == false }
        })
        assertTrue(runCatching { controller.setEnabled(peer, true) }.isFailure)
        assertTrue(cancellationSawDisabled)
        assertFalse(registry.get(identity)!!.enabled)
    }

    @Test fun failedDisableCommitFreezesOldEnabledPermitInCurrentProcess() {
        val entry = registry.enable(peer, source)
        store.fail = true
        assertTrue(runCatching { registry.disable(identity) }.isFailure)
        assertTrue(runCatching { registry.permits(identity, entry.generation) }.isFailure)
        assertTrue(RemoteChannelBackgroundRegistry.decode(store.raw).single().enabled)
    }

    @Test fun reconciliationRepairsMissingScheduleWithoutGrantingChangedUrlPermission() = runBlocking {
        val entry = registry.enable(peer, source)
        val scheduled = mutableListOf<RemoteChannelBackgroundEntry>()
        var cancelled = 0
        val controller = controller(object : RemoteChannelBackgroundScheduler {
            override suspend fun schedule(entry: RemoteChannelBackgroundEntry) { scheduled += entry }
            override suspend fun cancel(identity: String) { cancelled++ }
        })
        controller.reconcile()
        assertEquals(listOf(entry), scheduled)
        assertEquals(entry.generation, registry.get(identity)?.generation)
        sources.save(peer, "https://changed.example/batch")
        controller.reconcile()
        assertEquals(1, scheduled.size)
        assertEquals(1, cancelled)
        assertFalse(registry.get(identity)!!.enabled)
    }

    @Test fun changedUrlDisabledPairingAndStaleGenerationPreventNetwork() = runBlocking {
        val first = registry.enable(peer, source)
        var calls = 0
        val runner = runner { _, _ -> calls++; RemoteChannelPullSyncResult.Batch(0, 0, false) }
        assertEquals(RemoteChannelBackgroundOutcome.DISABLED, runner.run(identity, "old"))
        sources.save(peer, "https://other.example/batch")
        assertEquals(RemoteChannelBackgroundOutcome.INVALIDATED, runner.run(identity, first.generation))
        assertEquals(RemoteChannelBackgroundOutcome.INVALIDATED, registry.get(identity)?.lastOutcome)
        val next = registry.enable(peer, sources.sourceFor(peer)!!)
        pairings.setEnabled(peer.channelId, peer.senderId, peer.keyId, false)
        assertEquals(RemoteChannelBackgroundOutcome.INVALIDATED, runner.run(identity, next.generation))
        assertEquals(0, calls)
    }

    @Test fun staleWorkerInvalidationCannotDisableNewGenerationOrCancelItsNotification() = runBlocking {
        val first = registry.enable(peer, source)
        val changed = sources.save(peer, "https://changed.example/batch")
        var next: RemoteChannelBackgroundEntry? = null
        // long: 在旧 Worker 读过许可、即将读来源时交错重新启用，复现新代次被旧失效结论覆盖的窗口。
        sourceStore.onLoad = { next = registry.enable(peer, changed) }
        assertEquals(RemoteChannelBackgroundOutcome.INVALIDATED,
            runner { _, _ -> error("旧代次不能联网") }.run(identity, first.generation))
        assertTrue(registry.permits(identity, next!!.generation))
        assertNull(registry.get(identity)?.lastOutcome)
        assertEquals(0, notifier.cancelled)
    }

    @Test fun durablePendingNotificationIsRecoveredOnceAndDeniedDeliveryIsNotMarkedNotified() = runBlocking {
        val entry = registry.enable(peer, source)
        registry.setNotifications(identity, true)
        assertTrue(inbox.receive(message()) is RemoteChannelReceiveResult.Accepted)
        notifier.allowed = false
        val runner = runner { _, allowed -> assertTrue(allowed()); RemoteChannelPullSyncResult.Batch(0, 0, false) }
        runner.run(identity, entry.generation)
        assertNull(registry.get(identity)?.notifiedDigest)
        notifier.allowed = true
        runner.run(identity, entry.generation)
        runner.run(identity, entry.generation)
        assertEquals(1, notifier.delivered)
        registry.setNotifications(identity, false)
        runner.run(identity, entry.generation)
        assertEquals(1, notifier.delivered)
    }

    @Test fun cancellationNeverBecomesRetryOrSuccessfulAudit() = runBlocking {
        val entry = registry.enable(peer, source)
        assertTrue(runCatching { runner { _, _ -> throw CancellationException("worker stopped") }.run(identity, entry.generation) }.exceptionOrNull() is CancellationException)
        assertNull(registry.get(identity)?.lastOutcome)
        assertEquals(0, notifier.delivered)
    }

    @Test fun retryableErrorsHaveBoundedRetriesAndProtocolRejectsDoNotRetry() = runBlocking {
        val entry = registry.enable(peer, source)
        val outcome = runner { _, _ -> throw java.io.IOException("network down") }.run(identity, entry.generation)
        assertEquals(RemoteChannelBackgroundOutcome.RETRYABLE_ERROR, outcome)
        assertEquals(listOf(true, true, true, false, false), (0..4).map { RemoteChannelBackgroundSynchronizer.shouldRetry(outcome, it) })
        assertEquals(RemoteChannelBackgroundOutcome.REJECTED,
            runner { _, _ -> throw IllegalArgumentException("invalid signature") }.run(identity, entry.generation))
        assertFalse(RemoteChannelBackgroundSynchronizer.shouldRetry(RemoteChannelBackgroundOutcome.REJECTED, 0))
    }

    @Test fun strictPermitCodecRejectsUnknownFieldsCoercionAndDuplicateSources() {
        registry.enable(peer, source)
        val raw = store.raw!!
        assertEquals(registry.list(), RemoteChannelBackgroundRegistry.decode(raw))
        for (value in listOf("true", 1, JSONObject.NULL)) {
            val bad = JSONObject(raw)
            bad.getJSONArray("entries").getJSONObject(0).put("enabled", value)
            assertTrue(runCatching { RemoteChannelBackgroundRegistry.decode(bad.toString()) }.isFailure)
        }
        val bad = JSONObject(raw)
        bad.getJSONArray("entries").getJSONObject(0).put("unexpected", true)
        assertTrue(runCatching { RemoteChannelBackgroundRegistry.decode(bad.toString()) }.isFailure)
        val duplicates = JSONObject(raw)
        duplicates.getJSONArray("entries").put(duplicates.getJSONArray("entries").getJSONObject(0))
        assertTrue(runCatching { RemoteChannelBackgroundRegistry.decode(duplicates.toString()) }.isFailure)
    }

    private fun controller(scheduler: RemoteChannelBackgroundScheduler) = RemoteChannelBackgroundController(registry, pairings, sources, scheduler, notifier)
    private fun runner(block: suspend (RemoteChannelPeerCandidate, () -> Boolean) -> RemoteChannelPullSyncResult) =
        RemoteChannelBackgroundSynchronizer(registry, pairings, sources, inbox, block, notifier)
    private fun message(): RemoteChannelEnvelope {
        val value = RemoteChannelEnvelope("channel", "stored-message", "sender", "conversation", "仅测试正文", 1_000_000, "nonce-12345678901234567890", "key-1")
        return value.copy(signature = Base64.getUrlEncoder().withoutPadding().encodeToString(Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private); update(RemoteChannelPolicy.canonicalBytes(value)); sign()
        }))
    }
    private class Store : RemoteChannelBackgroundStateStore {
        var raw: String? = null
        var fail = false
        override fun loadState() = raw
        override fun saveState(state: String) { check(!fail); raw = state }
    }
    private class Notifier : RemoteChannelInboxNotifier {
        var allowed = true
        var delivered = 0
        var cancelled = 0
        override fun show(identity: String): Boolean { if (allowed) delivered++; return allowed }
        override fun cancel(identity: String) { cancelled++ }
    }
}
