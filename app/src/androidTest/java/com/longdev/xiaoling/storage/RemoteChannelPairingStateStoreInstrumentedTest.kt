package com.longdev.xiaoling.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.longdev.xiaoling.agent.PublicKeyRemoteChannelAuthenticator
import com.longdev.xiaoling.agent.RemoteChannelEnvelope
import com.longdev.xiaoling.agent.RemoteChannelPairingPolicy
import com.longdev.xiaoling.agent.RemoteChannelPairingRegistry
import com.longdev.xiaoling.agent.RemoteChannelPolicy
import com.longdev.xiaoling.agent.RemoteChannelRejectionReason
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class RemoteChannelPairingStateStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule val cleanup = object : TestWatcher() {
        override fun starting(description: Description) {
            // long: 配对真机测试只清理专属偏好文件，不触碰用户正式的远端身份和收件箱。
            context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        }
        override fun finished(description: Description) {
            context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun pairedPublicKeySurvivesStoreRecreationAndRevocationStopsVerification() {
        val now = System.currentTimeMillis()
        val key = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val candidate = RemoteChannelPairingPolicy.prepare(
            "channel", "sender", "key-1", "测试来源", Base64.getEncoder().encodeToString(key.public.encoded),
        )
        val registry = registry()
        registry.confirm(candidate, candidate.fingerprintSha256)
        assertFalse(registry.list().single().enabled)
        assertTrue(registry.setEnabled("channel", "sender", "key-1", true))
        val envelope = RemoteChannelEnvelope(
            "channel", "message", "sender", "chat", "测试正文", now,
            nonce = "nonce-for-message-1", keyId = "key-1",
        )
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(key.private)
        signer.update(RemoteChannelPolicy.canonicalBytes(envelope))
        val signed = envelope.copy(signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()))
        val reopened = registry()
        val verifier = PublicKeyRemoteChannelAuthenticator(reopened)
        assertEquals(null, verifier.verify(signed, now))
        assertEquals(RemoteChannelRejectionReason.INVALID_SIGNATURE,
            verifier.verify(signed.copy(text = "篡改"), now))
        assertTrue(reopened.revoke("channel", "sender", "key-1"))
        assertEquals(RemoteChannelRejectionReason.UNKNOWN_KEY_ID, verifier.verify(signed, now))
        assertTrue(registry().list().isEmpty())

        context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("state", "{broken").commit()
        assertEquals(RemoteChannelRejectionReason.PAIRING_STATE_UNAVAILABLE, verifier.verify(signed, now))
    }

    private fun registry() = RemoteChannelPairingRegistry(
        SharedPreferencesRemoteChannelPairingStateStore(context, TEST_PREFERENCES),
    )

    private companion object { const val TEST_PREFERENCES = "xiaoling_remote_channel_pairings_instrumentation" }
}
