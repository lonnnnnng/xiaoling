package com.longdev.xiaoling.agent

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChannelPairingTest {
    private val now = 1_000_000L

    @Test
    fun fingerprintConfirmationDefaultsOffAndEnablesOnlyTheBoundSender() {
        val key = keyPair()
        val store = TestStore()
        val registry = RemoteChannelPairingRegistry(store)
        val candidate = candidate(key)
        assertTrue(runCatching { registry.confirm(candidate, "0".repeat(64)) }.isFailure)
        assertTrue(registry.list().isEmpty())
        assertFalse(registry.confirm(candidate, candidate.fingerprintSha256).enabled)
        val verifier = PublicKeyRemoteChannelAuthenticator(registry)
        val message = signed(key, "message-1")
        assertEquals(RemoteChannelRejectionReason.UNKNOWN_KEY_ID, verifier.verify(message, now))

        assertTrue(registry.setEnabled("channel", "sender", "key-1", true))
        assertEquals(null, verifier.verify(message, now))
        assertEquals(RemoteChannelRejectionReason.UNKNOWN_KEY_ID,
            verifier.verify(signed(key, "message-2").copy(senderId = "other"), now))
        assertEquals(RemoteChannelRejectionReason.INVALID_SIGNATURE,
            verifier.verify(message.copy(text = "篡改正文"), now))
        assertEquals(RemoteChannelRejectionReason.INVALID_NONCE,
            verifier.verify(message.copy(nonce = "short"), now))
        assertEquals(RemoteChannelRejectionReason.TIMESTAMP_OUT_OF_WINDOW,
            verifier.verify(message, now + RemoteChannelPolicy.DEFAULT_MAX_AGE_MILLIS + 1L))
    }

    @Test
    fun revokeAndKeyRotationTakeEffectWithoutRebuildingAuthenticator() {
        val old = keyPair()
        val newer = keyPair()
        val store = TestStore()
        val registry = RemoteChannelPairingRegistry(store)
        val oldCandidate = candidate(old)
        registry.confirm(oldCandidate, oldCandidate.fingerprintSha256)
        assertTrue(registry.setEnabled("channel", "sender", "key-1", true))
        val verifier = PublicKeyRemoteChannelAuthenticator(registry)
        assertEquals(null, verifier.verify(signed(old, "old"), now))

        assertTrue(runCatching {
            val sameId = candidate(newer)
            registry.confirm(sameId, sameId.fingerprintSha256)
        }.isFailure)
        val nextCandidate = candidate(newer, "key-2")
        registry.confirm(nextCandidate, nextCandidate.fingerprintSha256)
        assertEquals(RemoteChannelRejectionReason.UNKNOWN_KEY_ID,
            verifier.verify(signed(newer, "new", "key-2"), now))
        assertTrue(registry.setEnabled("channel", "sender", "key-2", true))
        assertEquals(null, verifier.verify(signed(newer, "new", "key-2"), now))
        assertTrue(registry.revoke("channel", "sender", "key-1"))
        assertEquals(RemoteChannelRejectionReason.UNKNOWN_KEY_ID, verifier.verify(signed(old, "old-2"), now))
        assertEquals(1, RemoteChannelPairingRegistry(TestStore(store.state)).list().size)
    }

    @Test
    fun corruptedOrUnavailablePairingStateRejectsSignedIngress() {
        val key = keyPair()
        val store = TestStore()
        val registry = RemoteChannelPairingRegistry(store)
        val candidate = candidate(key)
        registry.confirm(candidate, candidate.fingerprintSha256)
        registry.setEnabled("channel", "sender", "key-1", true)
        val verifier = PublicKeyRemoteChannelAuthenticator(registry)
        store.state = "{broken"
        assertEquals(RemoteChannelRejectionReason.PAIRING_STATE_UNAVAILABLE,
            verifier.verify(signed(key, "message"), now))
        assertTrue(runCatching { registry.list() }.isFailure)
        store.failReads = true
        assertEquals(RemoteChannelRejectionReason.PAIRING_STATE_UNAVAILABLE,
            verifier.verify(signed(key, "message"), now))
    }

    @Test
    fun confirmedPublicKeyUsesExistingDurableInboxReplayAndPendingRules() {
        val key = keyPair()
        val registry = RemoteChannelPairingRegistry(TestStore())
        val candidate = candidate(key)
        registry.confirm(candidate, candidate.fingerprintSha256)
        registry.setEnabled("channel", "sender", "key-1", true)
        val snapshotStore = object : RemoteChannelInboxStateStore {
            var snapshot: RemoteChannelInboxSnapshot? = null
            override fun loadSnapshot(): RemoteChannelInboxSnapshot? = snapshot
            override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) { this.snapshot = snapshot }
        }
        fun inbox() = InMemoryRemoteChannelInbox(
            allowlist = RemoteChannelAllowlist("channel", setOf("sender")),
            authenticator = PublicKeyRemoteChannelAuthenticator(registry),
            inboxStateStore = snapshotStore,
            clock = { now },
        )
        val message = signed(key, "durable")
        assertTrue(inbox().receive(message) is RemoteChannelReceiveResult.Accepted)
        assertEquals("正文", inbox().pendingDrafts().single().draft.payload.text)
        assertEquals(RemoteChannelRejectionReason.DUPLICATE_MESSAGE,
            (inbox().receive(message) as RemoteChannelReceiveResult.Rejected).reason)
        assertNotNull(snapshotStore.snapshot)
    }

    @Test
    fun wrongCurveAndMalformedPublicKeyAreRejectedBeforePairing() {
        val wrong = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        assertTrue(runCatching { candidate(wrong) }.isFailure)
        assertTrue(runCatching {
            RemoteChannelPairingPolicy.prepare("channel", "sender", "key-1", "来源", "not-base64")
        }.isFailure)
    }

    @Test
    fun failedRevocationWriteKeepsLastPersistedTrustStateVisible() {
        val key = keyPair()
        val store = TestStore()
        val registry = RemoteChannelPairingRegistry(store)
        val candidate = candidate(key)
        registry.confirm(candidate, candidate.fingerprintSha256)
        registry.setEnabled("channel", "sender", "key-1", true)
        val previous = store.state
        store.failWrites = true
        assertTrue(runCatching { registry.revoke("channel", "sender", "key-1") }.isFailure)
        assertEquals(previous, store.state)
        assertTrue(registry.list().single().enabled)
    }

    private fun candidate(key: KeyPair, keyId: String = "key-1") = RemoteChannelPairingPolicy.prepare(
        "channel", "sender", keyId, "本机测试来源",
        Base64.getEncoder().encodeToString(key.public.encoded),
    )

    private fun signed(key: KeyPair, messageId: String, keyId: String = "key-1"): RemoteChannelEnvelope {
        val envelope = RemoteChannelEnvelope(
            channelId = "channel", messageId = messageId, senderId = "sender", conversationKey = "chat",
            text = "正文", receivedAtMillis = now, nonce = "nonce-$messageId".padEnd(20, 'x'), keyId = keyId,
        )
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(key.private)
        signature.update(RemoteChannelPolicy.canonicalBytes(envelope))
        return envelope.copy(signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign()))
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private class TestStore(
        var state: String? = null,
        var failReads: Boolean = false,
        var failWrites: Boolean = false,
    ) : RemoteChannelPairingStateStore {
        override fun loadState(): String? {
            if (failReads) error("配对账本读取失败")
            return state
        }
        override fun saveState(state: String) {
            if (failWrites) error("配对账本写入失败")
            this.state = state
        }
    }
}
