package com.longdev.xiaoling.storage

import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.RemoteChannelPairingPolicy
import com.longdev.xiaoling.agent.RemoteChannelPairingRegistry
import com.longdev.xiaoling.agent.RemoteChannelPairingStateStore
import com.longdev.xiaoling.agent.RemoteChannelPullSourceRegistry
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChannelPullSourceStateStoreInstrumentedTest {
    @Test fun registeredSourceSurvivesStoreRecreationAndCorruptionFreezesPullConfiguration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "xiaoling_remote_pull_test_${UUID.randomUUID()}"
        try {
            val publicKey = KeyPairGenerator.getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public
            val peer = RemoteChannelPairingPolicy.prepare(
                "channel", "sender", "key-1", "测试来源", Base64.getEncoder().encodeToString(publicKey.encoded),
            )
            val pairings = RemoteChannelPairingRegistry(object : RemoteChannelPairingStateStore {
                private var state: String? = null
                override fun loadState(): String? = state
                override fun saveState(state: String) { this.state = state }
            })
            pairings.confirm(peer, peer.fingerprintSha256)
            RemoteChannelPullSourceRegistry(
                SharedPreferencesRemoteChannelPullSourceStateStore(context, preferencesName), pairings,
            ).save(peer, "https://inbox.example/next")
            val restored = RemoteChannelPullSourceRegistry(
                SharedPreferencesRemoteChannelPullSourceStateStore(context, preferencesName), pairings,
            )
            assertEquals("https://inbox.example/next", restored.sourceFor(peer)?.url)
            // long: 配置快照受损时必须冻结来源读取，不能静默当作尚未登记而改写信任绑定。
            context.getSharedPreferences(preferencesName, 0).edit().putString("state", "{broken").commit()
            assertTrue(runCatching { restored.list() }.isFailure)
        } finally {
            context.deleteSharedPreferences(preferencesName)
        }
    }
}
