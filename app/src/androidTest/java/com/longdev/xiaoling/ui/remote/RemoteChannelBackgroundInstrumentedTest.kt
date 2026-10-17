package com.longdev.xiaoling.ui.remote

import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.testing.TestListenableWorkerBuilder
import com.longdev.xiaoling.XiaoLingApplication
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.remote.*
import com.longdev.xiaoling.storage.*
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** long: WorkManager 测试使用专属测试数据库和 UUID 存储，不触发用户来源或正式工作流。 */
class RemoteChannelBackgroundInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun defaultFactoryConstructsWorkerAndRejectsIncompleteIdentity() = runBlocking {
        val worker = TestListenableWorkerBuilder.from(context, RemoteChannelReceiveWorker::class.java).build()
        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
    }

    @Test fun settingsOptInCreatesOnePeriodicTaskAndDisableCancelsBeforeAnyFetch() = runBlocking {
        val fixture = fixture()
        try {
            val manager = initializeWork(fixture)
            composeRule.setContent { MaterialTheme {
                RemoteChannelPairingPage(fixture.pairings, {}, inboxGateway = fixture.inbox, pullSources = fixture.sources,
                    pullService = fixture.service, backgroundController = fixture.controller(manager))
            } }
            val tag = "remote-background-enabled-real-device-test-fixture-sender-fixture-key-1"
            assertNull(fixture.registry.get(fixture.identity))
            composeRule.onNodeWithTag(tag).performScrollTo().performClick()
            await { fixture.registry.get(fixture.identity)?.enabled == true && infos(manager, fixture.identity).count { !it.state.isFinished } == 1 }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(tag).performScrollTo().performClick()
            await { fixture.registry.get(fixture.identity)?.enabled == false && infos(manager, fixture.identity).all { it.state == WorkInfo.State.CANCELLED } }
            assertEquals(0, fixture.calls.get())
        } finally { fixture.cleanup() }
    }

    @Test fun constrainedWorkerUsesRealHttpsAndDuplicatePeriodicPassDoesNotRepeatNotification() = runBlocking {
        val fixture = fixture()
        try {
            val manager = initializeWork(fixture)
            val controller = fixture.controller(manager)
            controller.setEnabled(fixture.peer, true)
            controller.setNotifications(fixture.peer, true)
            val info = infos(manager, fixture.identity).single { !it.state.isFinished }
            assertEquals(0, fixture.calls.get())
            val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
            driver.setAllConstraintsMet(info.id)
            await { fixture.registry.get(fixture.identity)?.lastOutcome == RemoteChannelBackgroundOutcome.SYNCED &&
                fixture.registry.get(fixture.identity)?.notifiedDigest != null }
            assertEquals(2, (fixture.inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
            assertEquals("cursor-1", fixture.inbox.batchState(fixture.source, fixture.peer).cursor)
            assertNull(fixture.inbox.batchState(fixture.source, fixture.peer).pendingAck)
            assertEquals(1, fixture.delivered.get())
            await { infos(manager, fixture.identity).single { it.id == info.id }.state == WorkInfo.State.ENQUEUED }
            driver.setPeriodDelayMet(info.id)
            // long: TestScheduler 启动时移除本轮约束状态，第二个周期必须重新显式满足网络约束。
            driver.setAllConstraintsMet(info.id)
            await { fixture.calls.get() == 2 && infos(manager, fixture.identity).single { it.id == info.id }.state == WorkInfo.State.ENQUEUED }
            assertEquals(2, (fixture.inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
            assertEquals(1, fixture.delivered.get())
            controller.setEnabled(fixture.peer, false)
            assertTrue(infos(manager, fixture.identity).all { it.state.isFinished })
        } finally { fixture.cleanup() }
    }

    @Test fun forgedAndReplayedNavigationTokenCannotBeConsumed() {
        val prefix = "remote_background_token_test_${UUID.randomUUID()}"
        val tokens = RemoteChannelInboxNavigationStore(context, prefix)
        try {
            assertFalse(tokens.consume(UUID.randomUUID().toString()))
            val token = tokens.issue("isolated-source")!!
            assertNull(RemoteChannelInboxNavigationIntent.read(RemoteChannelInboxNavigationIntent.create(context, token).apply {
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }))
            assertTrue(tokens.consume(token))
            assertFalse(RemoteChannelInboxNavigationStore(context, prefix).consume(token))
            val expired = RemoteChannelInboxNavigationStore(context, prefix) { 1L }.issue("isolated-source")!!
            assertFalse(RemoteChannelInboxNavigationStore(context, prefix) { 100_000_000L }.consume(expired))
        } finally { context.deleteSharedPreferences(prefix) }
    }

    private fun fixture(): BackgroundDeviceFixture {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("remotePullBaseUrl").orEmpty().trimEnd('/')
        val key = args.getString("remotePullPublicKey").orEmpty()
        assumeTrue("显式合成公网来源是 Worker 真机门禁的前置条件", url.isNotEmpty() && key.isNotEmpty())
        return BackgroundDeviceFixture(context, url, key)
    }

    private suspend fun initializeWork(fixture: BackgroundDeviceFixture): WorkManager {
        // long: 应用启动对账可能正在访问正式 WorkManager，先等其单轮结束再替换测试委托，防止正式许可进入测试数据库。
        withTimeout(30_000) { (context.applicationContext as XiaoLingApplication).remoteChannelStartupRecovery.await() }
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == RemoteChannelReceiveWorker::class.java.name) RemoteChannelReceiveWorker(appContext, workerParameters, fixture.runner) else null
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        return WorkManager.getInstance(context)
    }

    private fun infos(manager: WorkManager, identity: String) = manager.getWorkInfosForUniqueWork(WorkManagerRemoteChannelScheduler.workName(identity)).get()
    private fun await(block: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!block() && System.currentTimeMillis() < deadline) Thread.sleep(30)
        assertTrue("有界等待的后台状态没有收敛", block())
    }
}

private class BackgroundDeviceFixture(val context: Context, url: String, key: String) {
    val prefix = "remote_background_test_${UUID.randomUUID()}"
    val alias = prefix + "_aes"
    val peer = RemoteChannelPairingPolicy.prepare("real-device-test", "fixture-sender", "fixture-key-1", "后台合成来源", key)
    val pairings = RemoteChannelPairingRegistry(SharedPreferencesRemoteChannelPairingStateStore(context, prefix + "_pairings")).apply {
        confirm(peer, peer.fingerprintSha256); setEnabled(peer.channelId, peer.senderId, peer.keyId, true)
    }
    val sources = RemoteChannelPullSourceRegistry(SharedPreferencesRemoteChannelPullSourceStateStore(context, prefix + "_sources"), pairings)
    val source = sources.save(peer, url + "/batch")
    val inbox = RemoteChannelInboxGateway(KeystoreRemoteChannelInboxStateStore(context, prefix + "_inbox", alias), pairings)
    val checkpoints = RemoteChannelPullCheckpointRegistry(SharedPreferencesRemoteChannelPullCheckpointStateStore(context, prefix + "_retry"))
    val service = RemoteChannelPullService(pairings, sources, inbox, checkpoints = checkpoints)
    val registry = RemoteChannelBackgroundRegistry(SharedPreferencesRemoteChannelBackgroundStateStore(context, prefix + "_background"))
    val identity = RemoteChannelPullReliabilityPolicy.sourceIdentity(peer)
    val calls = AtomicInteger()
    val delivered = AtomicInteger()
    val notifier = object : RemoteChannelInboxNotifier {
        override fun show(identity: String): Boolean { delivered.incrementAndGet(); return true }
        override fun cancel(identity: String) {}
    }
    val runner = RemoteChannelBackgroundSynchronizer(registry, pairings, sources, inbox, { peer, allowed ->
        calls.incrementAndGet(); service.synchronize(peer, allowed)
    }, notifier, checkpoints)
    fun controller(manager: WorkManager) = RemoteChannelBackgroundController(registry, pairings, sources, WorkManagerRemoteChannelScheduler(context, manager), notifier)
    fun cleanup() {
        require(prefix.startsWith("remote_background_test_"))
        listOf("_pairings", "_sources", "_inbox", "_retry", "_background").forEach { context.deleteSharedPreferences(prefix + it) }
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }
}
