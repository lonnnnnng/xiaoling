package com.longdev.xiaoling.storage

import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.RemoteChannelPullCheckpointRegistry
import com.longdev.xiaoling.agent.RemoteChannelPullDeferredException
import com.longdev.xiaoling.agent.RemoteChannelPullErrorCode
import java.io.IOException
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class RemoteChannelPullCheckpointStateStoreInstrumentedTest {
    @Test fun retrySurvivesSeparateInstrumentationProcesses() {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("remoteCheckpointPhase")
        assumeTrue("只在显式 seed/restore 两进程门禁运行", phase == "seed" || phase == "restore")
        val preferencesName = arguments.getString("remoteCheckpointLedger") ?: error("缺少隔离账本名称")
        require(preferencesName.matches(Regex("xiaoling_remote_checkpoint_process_test_[A-Za-z0-9_]{1,80}"))) {
            "进程恢复门禁只能操作隔离测试账本"
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SharedPreferencesRemoteChannelPullCheckpointStateStore(context, preferencesName)
        val registry = RemoteChannelPullCheckpointRegistry(store) { 1_000L }
        val source = "channel\u0000sender\u0000key-1\u0000${"b".repeat(64)}"
        if (phase == "seed") {
            check(store.loadState() == null) { "seed 不得覆盖已有测试账本" }
            registry.recordAccepted(source, "process-message", "process-cursor")
            registry.recordRetryableFailure(source, IOException("fixture offline"))
        } else {
            try {
                assertEquals("process-cursor", registry.get(source)?.cursor)
                assertEquals(listOf("process-message"), registry.get(source)?.pendingAckMessageIds)
                assertEquals(31_000L, registry.get(source)?.nextAttemptAtMillis)
                assertTrue(runCatching { registry.beforeAttempt(source) }.exceptionOrNull() is RemoteChannelPullDeferredException)
            } finally {
                // long: restore 完成后只清理经过前缀约束的隔离测试数据，生产退避和用户收件箱不参与此门禁。
                context.deleteSharedPreferences(preferencesName)
            }
        }
        println("STAGE285_CHECKPOINT phase=$phase pid=${android.os.Process.myPid()}")
    }

    @Test fun retryAndAckStateSurviveStoreRecreationAndCorruptionFreezesWrites() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "xiaoling_remote_checkpoint_test_${UUID.randomUUID()}"
        val source = "channel\u0000sender\u0000key-1\u0000${"a".repeat(64)}"
        var now = 1_000L
        try {
            val registry = RemoteChannelPullCheckpointRegistry(
                SharedPreferencesRemoteChannelPullCheckpointStateStore(context, preferencesName),
            ) { now }
            registry.recordAccepted(source, "message-1", "cursor-1")
            registry.recordRetryableFailure(source, IOException("fixture offline"))
            val restored = RemoteChannelPullCheckpointRegistry(
                SharedPreferencesRemoteChannelPullCheckpointStateStore(context, preferencesName),
            ) { now }
            assertEquals("cursor-1", restored.get(source)?.cursor)
            assertEquals(listOf("message-1"), restored.get(source)?.pendingAckMessageIds)
            assertEquals(RemoteChannelPullErrorCode.NETWORK.name, restored.get(source)?.lastErrorCode)
            assertTrue(runCatching { restored.beforeAttempt(source) }.exceptionOrNull() is RemoteChannelPullDeferredException)
            now = 31_000L
            restored.beforeAttempt(source)
            restored.acknowledge(source, "message-1")
            val afterAck = RemoteChannelPullCheckpointRegistry(
                SharedPreferencesRemoteChannelPullCheckpointStateStore(context, preferencesName),
            ) { now }
            assertEquals(emptyList<String>(), afterAck.get(source)?.pendingAckMessageIds)
            assertEquals("cursor-1", afterAck.get(source)?.cursor)
            // long: 测试只污染随机命名的隔离账本，损坏时拒绝后续写入而非覆盖成空白状态。
            assertTrue(context.getSharedPreferences(preferencesName, 0).edit().putString("state", "{broken").commit())
            val corrupt = RemoteChannelPullCheckpointRegistry(
                SharedPreferencesRemoteChannelPullCheckpointStateStore(context, preferencesName),
            ) { now }
            assertTrue(!corrupt.isAvailable())
            assertTrue(runCatching { corrupt.beforeAttempt(source) }.isFailure)
            assertTrue(runCatching { corrupt.recordAccepted(source, "message-2") }.isFailure)
            assertEquals("{broken", context.getSharedPreferences(preferencesName, 0).getString("state", null))
        } finally {
            context.deleteSharedPreferences(preferencesName)
        }
    }
}
