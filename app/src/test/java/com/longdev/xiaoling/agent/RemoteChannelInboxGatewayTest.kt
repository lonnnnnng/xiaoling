package com.longdev.xiaoling.agent

import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChannelInboxGatewayTest {
    private val now = 1_000_000L
    private val keyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val registry = RemoteChannelPairingRegistry(FakePairingStore())

    init {
        listOf("channel-a" to "sender-a", "channel-b" to "sender-b", "channel" to "sender")
            .forEach { (channel, sender) ->
                val candidate = RemoteChannelPairingPolicy.prepare(
                    channel, sender, "paired", "测试来源",
                    Base64.getEncoder().encodeToString(keyPair.public.encoded),
                )
                registry.confirm(candidate, candidate.fingerprintSha256)
                registry.setEnabled(channel, sender, "paired", true)
            }
    }

    @Test
    fun channelsShareOneDurableInboxAndOpenedBodyDoesNotReturn() {
        val store = FakeStore()
        val first = signed("channel-a", "sender-a", "same-id", "nonce-aaaaaaaaaaaa")
        val second = signed("channel-b", "sender-b", "same-id", "nonce-bbbbbbbbbbbb")
        val gateway = gateway(store)
        assertTrue(gateway.receive(first) is RemoteChannelReceiveResult.Accepted)
        assertTrue(gateway.receive(second) is RemoteChannelReceiveResult.Accepted)
        val pending = (gateway(store).status() as RemoteChannelInboxStatus.Ready).pending
        assertEquals(2, pending.size)
        assertEquals(setOf("channel-a", "channel-b"), pending.map { it.draft.channelId }.toSet())

        assertTrue(gateway.discard(pending.first().draft))
        assertEquals(1, (gateway(store).status() as RemoteChannelInboxStatus.Ready).pending.size)
        assertEquals(
            RemoteChannelRejectionReason.DUPLICATE_MESSAGE,
            (gateway(store).receive(first) as RemoteChannelReceiveResult.Rejected).reason,
        )
    }

    @Test
    fun failedDiscardRetainsBodyAndCorruptSnapshotHasExplicitUnavailableState() {
        val store = FakeStore()
        val gateway = gateway(store)
        val message = signed("channel", "sender", "message", "nonce-cccccccccccc")
        assertTrue(gateway.receive(message) is RemoteChannelReceiveResult.Accepted)
        store.failWrites = true
        val draft = (gateway.status() as RemoteChannelInboxStatus.Ready).pending.single().draft
        assertFalse(gateway.discard(draft))
        assertEquals(1, (gateway(store).status() as RemoteChannelInboxStatus.Ready).pending.size)
        store.failReads = true
        assertEquals(RemoteChannelInboxStatus.Unavailable, gateway(store).status())
        assertEquals(
            RemoteChannelRejectionReason.DEDUPE_PERSISTENCE_FAILURE,
            (gateway(store).receive(message.copy(messageId = "new")) as RemoteChannelReceiveResult.Rejected).reason,
        )
    }

    @Test
    fun disablingPeerBlocksNewIngressButKeepsPreviouslyVerifiedPendingForUserDecision() {
        val store = FakeStore()
        val gateway = gateway(store)
        assertTrue(gateway.receive(signed("channel", "sender", "old", "nonce-old-123456789012"))
            is RemoteChannelReceiveResult.Accepted)
        registry.setEnabled("channel", "sender", "paired", false)
        assertEquals("old", (gateway.status() as RemoteChannelInboxStatus.Ready).pending.single().draft.messageId)
        assertEquals(
            RemoteChannelRejectionReason.UNKNOWN_KEY_ID,
            (gateway.receive(signed("channel", "sender", "new", "nonce-new-123456789012"))
                as RemoteChannelReceiveResult.Rejected).reason,
        )
    }

    @Test
    fun manualImportRequiresExactSignedEnvelopeFieldsAndIntegerTimestamp() {
        val signed = signed("channel", "sender", "message", "nonce-dddddddddddd")
        val json = JSONObject()
            .put("channelId", signed.channelId)
            .put("messageId", signed.messageId)
            .put("senderId", signed.senderId)
            .put("conversationKey", signed.conversationKey)
            .put("text", signed.text)
            .put("receivedAtMillis", signed.receivedAtMillis)
            .put("nonce", signed.nonce)
            .put("keyId", signed.keyId)
            .put("signature", signed.signature)
        assertEquals(signed, RemoteChannelSignedEnvelopeCodec.decode(json.toString()))
        assertTrue(runCatching { RemoteChannelSignedEnvelopeCodec.decode(json.put("attachment", "uri").toString()) }.isFailure)
        json.remove("attachment")
        assertTrue(runCatching { RemoteChannelSignedEnvelopeCodec.decode(json.put("receivedAtMillis", "1000000").toString()) }.isFailure)
    }

    private fun gateway(store: FakeStore) = RemoteChannelInboxGateway(store, registry) { now }

    private fun signed(channel: String, sender: String, id: String, nonce: String): RemoteChannelEnvelope {
        val unsigned = RemoteChannelEnvelope(channel, id, sender, "conversation", "待确认正文", now,
            nonce = nonce, keyId = "paired")
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(RemoteChannelPolicy.canonicalBytes(unsigned))
            Base64.getUrlEncoder().withoutPadding().encodeToString(sign())
        }
        return unsigned.copy(signature = signature)
    }

    private class FakeStore : RemoteChannelInboxStateStore {
        var snapshot: RemoteChannelInboxSnapshot? = null
        var failReads = false
        var failWrites = false

        override fun loadSnapshot(): RemoteChannelInboxSnapshot? {
            if (failReads) error("快照损坏")
            return snapshot
        }

        override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) {
            if (failWrites) error("写入失败")
            this.snapshot = snapshot
        }
    }

    private class FakePairingStore : RemoteChannelPairingStateStore {
        private var state: String? = null
        override fun loadState(): String? = state
        override fun saveState(state: String) { this.state = state }
    }
}
