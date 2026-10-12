package com.longdev.xiaoling.ui.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.longdev.xiaoling.agent.RemoteChannelPairingPolicy
import com.longdev.xiaoling.agent.RemoteChannelPairingRegistry
import com.longdev.xiaoling.agent.RemoteChannelPairingStateStore
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemoteChannelPairingPageInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

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

    private class TestStore(var state: String? = null) : RemoteChannelPairingStateStore {
        override fun loadState(): String? = state
        override fun saveState(state: String) { this.state = state }
    }
}
