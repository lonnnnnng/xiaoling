package com.longdev.xiaoling.agent

import com.longdev.xiaoling.storage.RemoteChannelInboxSnapshotCodec
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteChannelInboxAuditTest {
    private var now = 1_000_000L
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val pairings = RemoteChannelPairingRegistry(object : RemoteChannelPairingStateStore {
        var raw: String? = null
        override fun loadState() = raw
        override fun saveState(state: String) { raw = state }
    })
    private val store = Store()
    init {
        val peer = RemoteChannelPairingPolicy.prepare("channel", "sender", "key-1", "测试", Base64.getEncoder().encodeToString(keys.public.encoded))
        pairings.confirm(peer, peer.fingerprintSha256)
        pairings.setEnabled("channel", "sender", "key-1", true)
    }
    private fun gateway() = RemoteChannelInboxGateway(store, pairings) { now }
    private fun ready() = gateway().status() as RemoteChannelInboxStatus.Ready

    @Test fun terminalDecisionsAreAtomicAndStoreNoBodyOrNonceInAudit() {
        val first = receive("open")
        val second = receive("ignore")
        assertTrue(ready().auditEntries.isEmpty())
        now++
        assertTrue(gateway().discard(first, RemoteChannelInboxDisposition.OPENED))
        assertTrue(gateway().discard(second, RemoteChannelInboxDisposition.IGNORED))
        assertFalse(gateway().discard(first, RemoteChannelInboxDisposition.IGNORED))
        assertTrue(ready().pending.isEmpty())
        assertEquals(listOf(RemoteChannelInboxDisposition.OPENED, RemoteChannelInboxDisposition.IGNORED), ready().auditEntries.map { it.disposition })
        assertEquals(RemoteChannelInboxAuditPolicy.identityHash(first), ready().auditEntries.first().messageIdentitySha256)
        val raw = JSONObject(RemoteChannelInboxSnapshotCodec.encode(store.snapshot!!)).getJSONArray("auditEntries").toString()
        listOf("私密正文", "nonce-", "conversation", "sender", "channel").forEach { assertFalse(raw.contains(it)) }
        assertEquals(4, store.snapshot!!.dedupeKeys.size)
    }

    @Test fun staleBodyCannotClaimAnOpenedDecisionAndExpiredCardOnlyRecordsExpiry() {
        val draft = receive("old")
        assertFalse(gateway().discard(draft.copy(payload = draft.payload.copy(text = "被替换正文")), RemoteChannelInboxDisposition.OPENED))
        assertTrue(ready().auditEntries.isEmpty())
        now += RemoteChannelPolicy.MAX_PENDING_AGE_MILLIS
        assertFalse(gateway().discard(draft, RemoteChannelInboxDisposition.OPENED))
        assertEquals(RemoteChannelInboxDisposition.EXPIRED, ready().auditEntries.single().disposition)
        assertFalse(gateway().discard(draft))
        assertEquals(1, ready().auditEntries.size)
    }

    @Test fun expiryBoundaryAndClockRollbackAreSafeForAnAlreadyOpenInbox() {
        val authenticator = HmacRemoteChannelAuthenticator(mapOf("key-1" to ByteArray(32) { 1 }))
        val inbox = InMemoryRemoteChannelInbox(RemoteChannelAllowlist("channel", setOf("sender")), authenticator = authenticator,
            inboxStateStore = store, clock = { now })
        val unsigned = message("clock")
        assertTrue(inbox.receive(unsigned.copy(signature = authenticator.sign(unsigned, "key-1"))) is RemoteChannelReceiveResult.Accepted)
        now -= 500
        assertEquals(1, inbox.pendingDrafts().size)
        now = 1_000_000 + RemoteChannelPolicy.MAX_PENDING_AGE_MILLIS - 1
        assertEquals(1, inbox.pendingDrafts().size)
        now++
        assertTrue(inbox.pendingDrafts().isEmpty())
        assertEquals(now, inbox.auditEntries().single().recordedAtMillis)
        assertEquals(2, store.snapshot!!.dedupeKeys.size)
        now = Long.MAX_VALUE
        assertTrue(inbox.pendingDrafts().isEmpty())
        assertEquals(1, inbox.auditEntries().size)
    }

    @Test fun failedDecisionKeepsBodyAndDoesNotInventAuditThenCanRetry() {
        val draft = receive("write-failure")
        val original = store.snapshot
        store.failWrites = true
        assertFalse(gateway().discard(draft, RemoteChannelInboxDisposition.OPENED))
        assertEquals(original, store.snapshot)
        assertEquals(1, ready().pending.size)
        assertTrue(ready().auditEntries.isEmpty())
        store.failWrites = false
        assertTrue(gateway().discard(draft, RemoteChannelInboxDisposition.OPENED))
        assertEquals(RemoteChannelInboxDisposition.OPENED, ready().auditEntries.single().disposition)
    }

    @Test fun failedExpiryCommitIsUnavailableAndPreservesOldCiphertextState() {
        receive("expiry-failure")
        val original = store.snapshot
        now += RemoteChannelPolicy.MAX_PENDING_AGE_MILLIS
        store.failWrites = true
        assertEquals(RemoteChannelInboxStatus.Unavailable, gateway().status())
        assertEquals(original, store.snapshot)
        store.failWrites = false
        assertEquals(RemoteChannelInboxDisposition.EXPIRED, ready().auditEntries.single().disposition)
    }

    @Test fun concurrentGatewaysOnlyCommitOneTerminalDecision() {
        val draft = receive("race")
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val tasks = listOf(RemoteChannelInboxDisposition.OPENED, RemoteChannelInboxDisposition.IGNORED).map { outcome ->
                pool.submit<Boolean> { start.await(); gateway().discard(draft, outcome) }
            }
            start.countDown()
            assertEquals(1, tasks.count { it.get() })
            assertEquals(1, ready().auditEntries.size)
            assertTrue(ready().pending.isEmpty())
        } finally { pool.shutdownNow() }
    }

    @Test fun auditCapacityRollsOnlyOldAuditAndKeepsUnreviewedReplayIdentity() {
        val unread = receive("unread")
        repeat(RemoteChannelInboxAuditPolicy.MAX_ENTRIES + 2) { i ->
            now++
            val draft = receive("processed-$i")
            assertTrue(gateway().discard(draft))
        }
        assertEquals(RemoteChannelInboxAuditPolicy.MAX_ENTRIES, ready().auditEntries.size)
        assertEquals(listOf(unread), ready().pending.map { it.draft })
        assertTrue(RemoteChannelPolicy.dedupeKey(unread) in store.snapshot!!.dedupeKeys)
        assertTrue(store.snapshot!!.pendingDrafts.single().nonceDedupeKey in store.snapshot!!.dedupeKeys)
    }

    @Test fun v1AndV2UpgradeWithoutFabricatingHistoricDecisions() {
        receive("legacy")
        val snapshot = store.snapshot!!
        val current = JSONObject(RemoteChannelInboxSnapshotCodec.encode(snapshot))
        assertEquals(3, current.getInt("schemaVersion"))
        val v2 = JSONObject(current.toString()).put("schemaVersion", 2).apply { remove("auditEntries") }
        val v1 = JSONObject(v2.toString()).put("schemaVersion", 1).apply { remove("batchStates") }
        for (old in listOf(v1, v2)) assertEquals(snapshot, RemoteChannelInboxSnapshotCodec.decode(old.toString()))
    }

    @Test fun strictAuditCodecRejectsCoercionUnknownFieldsAndDuplicates() {
        val draft = receive("codec")
        assertTrue(gateway().discard(draft, RemoteChannelInboxDisposition.OPENED))
        val raw = RemoteChannelInboxSnapshotCodec.encode(store.snapshot!!)
        assertEquals(store.snapshot, RemoteChannelInboxSnapshotCodec.decode(raw))
        fun mutated(block: (JSONObject) -> Unit): String = JSONObject(raw).apply { block(getJSONArray("auditEntries").getJSONObject(0)) }.toString()
        for (bad in listOf(mutated { it.put("text", "私密正文") }, mutated { it.remove("disposition") },
            mutated { it.put("acceptedAtMillis", "1000000") }, mutated { it.put("recordedAtMillis", 1.5) },
            mutated { it.put("messageIdentitySha256", "not-a-hash") }, mutated { it.put("recordId", "invalid") },
            mutated { it.put("disposition", "EXECUTED") })) {
            assertTrue(runCatching { RemoteChannelInboxSnapshotCodec.decode(bad) }.isFailure)
        }
        val duplicate = JSONObject(raw).apply { getJSONArray("auditEntries").put(getJSONArray("auditEntries").getJSONObject(0)) }
        assertTrue(runCatching { RemoteChannelInboxSnapshotCodec.decode(duplicate.toString()) }.isFailure)
        val overflow = JSONObject(raw).apply { put("auditEntries", JSONArray(List(129) { getJSONArray("auditEntries").getJSONObject(0) })) }
        assertTrue(runCatching { RemoteChannelInboxSnapshotCodec.decode(overflow.toString()) }.isFailure)
        val missing = JSONObject(raw).apply { remove("auditEntries") }
        assertTrue(runCatching { RemoteChannelInboxSnapshotCodec.decode(missing.toString()) }.isFailure)
        assertTrue(runCatching { gateway().discard(draft, RemoteChannelInboxDisposition.EXPIRED) }.isFailure)
    }

    @Test fun repeatedIdentityAndTimestampHaveSeparateAuditIdsAndCannotRewriteOldDecision() {
        val draft = receive("first")
        assertTrue(gateway().discard(draft, RemoteChannelInboxDisposition.OPENED))
        val opened = ready().auditEntries.single()
        // long: 两个去重槽位对应一条消息；让原身份滚出窗口，但旧审计仍在，模拟墙钟回到相同入箱时刻后再次投递。
        repeat(127) { assertTrue(gateway().discard(receive("roll-$it"))) }
        receive("unreviewed-rollover")
        assertTrue(opened in ready().auditEntries)
        val repeated = receive("first")
        assertTrue(gateway().discard(repeated))
        val ignored = ready().auditEntries.last()
        assertEquals(opened.messageIdentitySha256, ignored.messageIdentitySha256)
        assertEquals(opened.acceptedAtMillis, ignored.acceptedAtMillis)
        assertNotEquals(opened.recordId, ignored.recordId)
        assertEquals(RemoteChannelInboxDisposition.IGNORED, ignored.disposition)
        assertEquals(ready().auditEntries, RemoteChannelInboxAuditPolicy.append(ready().auditEntries,
            listOf(ignored.copy(disposition = RemoteChannelInboxDisposition.EXPIRED))))
    }

    private fun receive(id: String): RemoteChannelDraft {
        val unsigned = message(id)
        val signed = unsigned.copy(signature = Base64.getUrlEncoder().withoutPadding().encodeToString(Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private); update(RemoteChannelPolicy.canonicalBytes(unsigned)); sign()
        }))
        return (gateway().receive(signed) as RemoteChannelReceiveResult.Accepted).draft
    }
    private fun message(id: String) = RemoteChannelEnvelope("channel", id, "sender", "private-conversation", "私密正文-$id", now,
        nonce = "nonce-$id-1234567890123456", keyId = "key-1")
    private class Store : RemoteChannelInboxStateStore {
        var snapshot: RemoteChannelInboxSnapshot? = null
        var failWrites = false
        override fun loadSnapshot() = snapshot
        override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) { check(!failWrites) { "write failed" }; this.snapshot = snapshot }
    }
}
