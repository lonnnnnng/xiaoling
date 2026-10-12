package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChannelPersistentInboxTest {
    private val now = 1_000_000L
    private val authenticator = HmacRemoteChannelAuthenticator(
        mapOf("paired" to ByteArray(RemoteChannelPolicy.MIN_HMAC_KEY_BYTES) { 7 }),
    )
    private val allowlist = RemoteChannelAllowlist("channel", setOf("sender"))

    @Test
    fun signedMessageAndReplayIdentitySurviveRecreationUntilExplicitDiscard() {
        val store = FakeInboxStore()
        val message = signed("message-1")
        val first = inbox(store)
        assertTrue(first.receive(message) is RemoteChannelReceiveResult.Accepted)
        assertEquals("待处理正文", first.pendingDrafts().single().draft.payload.text)
        assertEquals(2, store.snapshot!!.dedupeKeys.size)

        val recreated = inbox(store)
        assertEquals("待处理正文", recreated.pendingDrafts().single().draft.payload.text)
        assertEquals(
            RemoteChannelRejectionReason.DUPLICATE_MESSAGE,
            (recreated.receive(message) as RemoteChannelReceiveResult.Rejected).reason,
        )
        assertTrue(recreated.discardPending("channel", "sender", "message-1"))
        assertTrue(inbox(store).pendingDrafts().isEmpty())
        assertEquals(2, store.snapshot!!.dedupeKeys.size)
        assertEquals(
            RemoteChannelRejectionReason.DUPLICATE_MESSAGE,
            (inbox(store).receive(message) as RemoteChannelReceiveResult.Rejected).reason,
        )
    }

    @Test
    fun failedAtomicWriteLeavesOldDedupeAndPendingUntouched() {
        val store = FakeInboxStore()
        val inbox = inbox(store)
        assertTrue(inbox.receive(signed("old")) is RemoteChannelReceiveResult.Accepted)
        val previous = store.snapshot
        store.failWrites = true
        assertEquals(
            RemoteChannelRejectionReason.DEDUPE_PERSISTENCE_FAILURE,
            (inbox.receive(signed("new")) as RemoteChannelReceiveResult.Rejected).reason,
        )
        assertEquals(previous, store.snapshot)
        assertEquals(listOf("old"), inbox.pendingDrafts().map { it.draft.messageId })
        store.failWrites = false
        assertTrue(inbox.receive(signed("new")) is RemoteChannelReceiveResult.Accepted)
    }

    @Test
    fun failedDiscardKeepsPendingBodyAndReplayHistory() {
        val store = FakeInboxStore()
        val inbox = inbox(store)
        assertTrue(inbox.receive(signed("message")) is RemoteChannelReceiveResult.Accepted)
        val previous = store.snapshot
        store.failWrites = true
        assertFalse(inbox.discardPending("channel", "sender", "message"))
        assertEquals(previous, store.snapshot)
        assertEquals("message", inbox.pendingDrafts().single().draft.messageId)
    }

    @Test
    fun durableInboxRequiresSignedMessages() {
        assertTrue(runCatching {
            InMemoryRemoteChannelInbox(allowlist = allowlist, inboxStateStore = FakeInboxStore())
        }.isFailure)
    }

    @Test
    fun fullInboxRejectsNinthMessageWithoutEvictingUnreviewedText() {
        val store = FakeInboxStore()
        val inbox = inbox(store)
        repeat(RemoteChannelPolicy.MAX_PENDING_DRAFTS) { index ->
            assertTrue(inbox.receive(signed("message-$index")) is RemoteChannelReceiveResult.Accepted)
        }
        assertEquals(
            RemoteChannelRejectionReason.PENDING_INBOX_FULL,
            (inbox.receive(signed("overflow")) as RemoteChannelReceiveResult.Rejected).reason,
        )
        assertEquals(RemoteChannelPolicy.MAX_PENDING_DRAFTS, inbox.pendingDrafts().size)
        assertTrue(inbox.discardPending("channel", "sender", "message-0"))
        assertTrue(inbox.receive(signed("overflow")) is RemoteChannelReceiveResult.Accepted)
    }

    @Test
    fun unreadMessageAndNonceStayProtectedWhenDedupeWindowRollsOver() {
        val store = FakeInboxStore()
        val inbox = inbox(store)
        val unread = signed("unread")
        assertTrue(inbox.receive(unread) is RemoteChannelReceiveResult.Accepted)
        repeat(130) { index ->
            val id = "processed-$index"
            assertTrue(inbox.receive(signed(id)) is RemoteChannelReceiveResult.Accepted)
            assertTrue(inbox.discardPending("channel", "sender", id))
        }
        assertEquals(listOf("unread"), inbox.pendingDrafts().map { it.draft.messageId })
        assertEquals(RemoteChannelPolicy.MAX_DEDUPE_ENTRIES, store.snapshot!!.dedupeKeys.size)
        assertEquals(
            RemoteChannelRejectionReason.DUPLICATE_MESSAGE,
            (inbox(store).receive(unread) as RemoteChannelReceiveResult.Rejected).reason,
        )
        val reusedNonce = signed("fresh-id").copy(nonce = unread.nonce)
        val replay = reusedNonce.copy(signature = authenticator.sign(reusedNonce, "paired"))
        assertEquals(
            RemoteChannelRejectionReason.DUPLICATE_MESSAGE,
            (inbox(store).receive(replay) as RemoteChannelReceiveResult.Rejected).reason,
        )
    }

    @Test
    fun unreadableSnapshotFreezesIngressInsteadOfLosingReplayHistory() {
        val store = FakeInboxStore(failReads = true)
        val inbox = inbox(store)
        assertTrue(inbox.pendingDrafts().isEmpty())
        assertEquals(
            RemoteChannelRejectionReason.DEDUPE_PERSISTENCE_FAILURE,
            (inbox.receive(signed("message")) as RemoteChannelReceiveResult.Rejected).reason,
        )
        assertFalse(inbox.discardPending("channel", "sender", "message"))
    }

    @Test
    fun expiredPendingBodyIsPurgedButReplayIdentityRemains() {
        val store = FakeInboxStore()
        assertTrue(inbox(store).receive(signed("message")) is RemoteChannelReceiveResult.Accepted)
        val later = now + RemoteChannelPolicy.MAX_PENDING_AGE_MILLIS + 1L
        val recreated = InMemoryRemoteChannelInbox(
            allowlist = allowlist,
            authenticator = authenticator,
            inboxStateStore = store,
            clock = { later },
        )
        assertTrue(recreated.pendingDrafts().isEmpty())
        assertTrue(store.snapshot!!.pendingDrafts.isEmpty())
        assertEquals(2, store.snapshot!!.dedupeKeys.size)
    }

    @Test
    fun identityCannotContainDedupeDelimiter() {
        assertEquals(
            RemoteChannelRejectionReason.INVALID_IDENTITY,
            RemoteChannelPolicy.validate(signed("a\u0000b")),
        )
    }

    private fun inbox(store: RemoteChannelInboxStateStore) = InMemoryRemoteChannelInbox(
        allowlist = allowlist,
        authenticator = authenticator,
        inboxStateStore = store,
        clock = { now },
    )

    private fun signed(messageId: String): RemoteChannelEnvelope {
        val unsigned = RemoteChannelEnvelope(
            channelId = "channel",
            messageId = messageId,
            senderId = "sender",
            conversationKey = "conversation",
            text = "待处理正文",
            receivedAtMillis = now,
            nonce = "nonce-$messageId".padEnd(RemoteChannelPolicy.MIN_NONCE_CHARS, 'x'),
            keyId = "paired",
        )
        return unsigned.copy(signature = authenticator.sign(unsigned, "paired"))
    }

    private class FakeInboxStore(
        var snapshot: RemoteChannelInboxSnapshot? = null,
        var failWrites: Boolean = false,
        var failReads: Boolean = false,
    ) : RemoteChannelInboxStateStore {
        override fun loadSnapshot(): RemoteChannelInboxSnapshot? {
            if (failReads) error("snapshot read failed")
            return snapshot
        }

        override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) {
            if (failWrites) error("snapshot write failed")
            this.snapshot = snapshot
        }
    }
}
