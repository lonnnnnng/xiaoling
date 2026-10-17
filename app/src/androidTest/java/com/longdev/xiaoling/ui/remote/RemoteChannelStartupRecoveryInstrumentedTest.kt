package com.longdev.xiaoling.ui.remote

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.longdev.xiaoling.XiaoLingApplication
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.remote.RemoteChannelReceiveWorker
import com.longdev.xiaoling.remote.WorkManagerRemoteChannelScheduler
import com.longdev.xiaoling.storage.*
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** long: 启动对账使用 UUID 偏好和独立 WorkManager 测试数据库，不改用户许可，不满足测试周期的网络约束。 */
class RemoteChannelStartupRecoveryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun applicationLaunchStartsOneAwaitableReconciliationJob() = runBlocking {
        val application = context.applicationContext as XiaoLingApplication
        val startup = application.remoteChannelStartupRecovery
        val outcome = withTimeout(30_000) { startup.await() }
        assertTrue(startup.isCompleted)
        assertFalse(startup.isCancelled)
        assertSame(startup, application.remoteChannelStartupRecovery)
        println("Remote Channel Application startup outcome: $outcome")
    }

    @Test fun persistedPermitGapRestoresSameGenerationAndKeepsExistingPeriodicWork() = runBlocking {
        val fixture = Fixture(context)
        var manager: WorkManager? = null
        try {
            val registry = fixture.registry()
            val entry = registry.enable(fixture.peer, fixture.source)
            registry.setNotifications(entry.sourceIdentity, true)
            val raw = fixture.backgroundStore().loadState()
            manager = initializeWork(fixture)
            assertTrue(infos(manager, entry.sourceIdentity).isEmpty())
            assertEquals(RemoteChannelBackgroundStartupOutcome.RECONCILED, fixture.recover(manager))
            val first = infos(manager, entry.sourceIdentity).single { !it.state.isFinished }
            assertTrue(entry.generation in first.tags)
            assertEquals(raw, fixture.backgroundStore().loadState())
            // long: 重建所有偏好注册表再对账，必须保留原系统 Work ID，不能把每次应用启动变成新周期或新许可。
            assertEquals(RemoteChannelBackgroundStartupOutcome.RECONCILED, fixture.recover(manager))
            assertEquals(first.id, infos(manager, entry.sourceIdentity).single { !it.state.isFinished }.id)
            assertEquals(0, fixture.fetches.get())
            assertEquals(0, fixture.notifications.get())
            assertEquals(raw, fixture.backgroundStore().loadState())
        } finally { fixture.cleanup(manager) }
    }

    @Test fun changedEndpointInvalidatesPersistedPermissionAndCancelsOnlyFixtureWork() = runBlocking {
        val fixture = Fixture(context)
        var manager: WorkManager? = null
        try {
            val entry = fixture.registry().enable(fixture.peer, fixture.source)
            manager = initializeWork(fixture)
            assertEquals(RemoteChannelBackgroundStartupOutcome.RECONCILED, fixture.recover(manager))
            fixture.sources().save(fixture.peer, "https://changed.example/batch")
            assertEquals(RemoteChannelBackgroundStartupOutcome.RECONCILED, fixture.recover(manager))
            val current = fixture.registry().get(entry.sourceIdentity)!!
            assertFalse(current.enabled)
            assertEquals(entry.generation, current.generation)
            assertEquals(RemoteChannelBackgroundOutcome.INVALIDATED, current.lastOutcome)
            assertTrue(infos(manager, entry.sourceIdentity).all { it.state == WorkInfo.State.CANCELLED })
            assertEquals(0, fixture.fetches.get())
            assertEquals(0, fixture.notifications.get())
        } finally { fixture.cleanup(manager) }
    }

    @Test fun corruptPersistentLedgerIsNotErasedAndNeverConstructsSystemScheduler() = runBlocking {
        val fixture = Fixture(context)
        try {
            val store = fixture.backgroundStore()
            store.saveState("broken")
            var controllerBuilt = false
            assertEquals(RemoteChannelBackgroundStartupOutcome.UNAVAILABLE,
                RemoteChannelBackgroundStartupRecovery.reconcile({ fixture.registry() }) {
                    controllerBuilt = true
                    error("损坏账本不能初始化 WorkManager")
                })
            assertFalse(controllerBuilt)
            assertEquals("broken", fixture.backgroundStore().loadState())
        } finally { fixture.cleanup(null) }
    }

    private suspend fun initializeWork(fixture: Fixture): WorkManager {
        // long: 先等待正式启动对账结束，再替换测试委托，避免把用户原有后台设置排入测试数据库。
        withTimeout(30_000) { (context.applicationContext as XiaoLingApplication).remoteChannelStartupRecovery.await() }
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (workerClassName == RemoteChannelReceiveWorker::class.java.name)
                    RemoteChannelReceiveWorker(appContext, workerParameters, fixture.runner) else null
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        return WorkManager.getInstance(context)
    }

    private fun infos(manager: WorkManager, identity: String) =
        manager.getWorkInfosForUniqueWork(WorkManagerRemoteChannelScheduler.workName(identity)).get()

    private class Fixture(private val context: Context) {
        private val id = UUID.randomUUID().toString()
        private val prefix = "remote_startup_recovery_test_$id"
        private val publicKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().public
        val peer = RemoteChannelPairingPolicy.prepare("startup-$id", "sender", "key-1", "启动恢复隔离来源",
            Base64.getEncoder().encodeToString(publicKey.encoded))
        private val identity = RemoteChannelPullReliabilityPolicy.sourceIdentity(peer)
        val fetches = AtomicInteger()
        val notifications = AtomicInteger()
        private val notifier = object : RemoteChannelInboxNotifier {
            override fun show(identity: String): Boolean { notifications.incrementAndGet(); return true }
            override fun cancel(identity: String) {}
        }
        init { pairings().apply { confirm(peer, peer.fingerprintSha256); setEnabled(peer.channelId, peer.senderId, peer.keyId, true) } }
        val source = sources().save(peer, "https://inbox.example/batch")
        fun pairings() = RemoteChannelPairingRegistry(SharedPreferencesRemoteChannelPairingStateStore(context, prefix + "_pairings"))
        fun sources() = RemoteChannelPullSourceRegistry(SharedPreferencesRemoteChannelPullSourceStateStore(context, prefix + "_sources"), pairings())
        fun backgroundStore() = SharedPreferencesRemoteChannelBackgroundStateStore(context, prefix + "_background")
        fun registry() = RemoteChannelBackgroundRegistry(backgroundStore())
        val runner by lazy {
            val pairs = pairings()
            val inbox = RemoteChannelInboxGateway(object : RemoteChannelInboxStateStore {
                override fun loadSnapshot(): RemoteChannelInboxSnapshot? = null
                override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) { error("调度门禁不能收件") }
            }, pairs)
            RemoteChannelBackgroundSynchronizer(registry(), pairs, sources(), inbox,
                { _, _ -> fetches.incrementAndGet(); error("调度门禁不能联网") }, notifier)
        }
        suspend fun recover(manager: WorkManager) = RemoteChannelBackgroundStartupRecovery.reconcile({ registry() }) {
            RemoteChannelBackgroundController(registry(), pairings(), sources(), WorkManagerRemoteChannelScheduler(context, manager), notifier)
        }
        suspend fun cleanup(manager: WorkManager?) {
            require(prefix == "remote_startup_recovery_test_$id")
            manager?.let { WorkManagerRemoteChannelScheduler(context, it).cancel(identity) }
            listOf("_pairings", "_sources", "_background").forEach { context.deleteSharedPreferences(prefix + it) }
        }
    }
}
