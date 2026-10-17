package com.longdev.xiaoling.agent

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteChannelBackgroundStartupRecoveryTest {
    @Test fun emptyLedgerDoesNotConstructControllerOrScheduler() = runBlocking {
        val registry = RemoteChannelBackgroundRegistry(Store())
        assertEquals(RemoteChannelBackgroundStartupOutcome.NOT_CONFIGURED,
            RemoteChannelBackgroundStartupRecovery.reconcile({ registry }) { error("空账本不能构建系统调度器") })
    }

    @Test fun corruptLedgerIsPreservedAndNeverConstructsController() = runBlocking {
        val store = Store().apply { raw = "broken" }
        var controllerBuilt = false
        assertEquals(RemoteChannelBackgroundStartupOutcome.UNAVAILABLE,
            RemoteChannelBackgroundStartupRecovery.reconcile({ RemoteChannelBackgroundRegistry(store) }) {
                controllerBuilt = true
                error("损坏账本不能构建调度器")
            })
        assertFalse(controllerBuilt)
        assertEquals("broken", store.raw)
    }

    @Test fun dependencyConstructionFailureIsIsolatedFromApplicationStartup() = runBlocking {
        var controllerBuilt = false
        assertEquals(RemoteChannelBackgroundStartupOutcome.UNAVAILABLE,
            RemoteChannelBackgroundStartupRecovery.reconcile({ error("偏好文件不可用") }) {
                controllerBuilt = true
                error("不应调用")
            })
        assertFalse(controllerBuilt)
        val fixture = Fixture()
        fixture.registry.enable(fixture.peer, fixture.source)
        val before = fixture.store.raw
        assertEquals(RemoteChannelBackgroundStartupOutcome.UNAVAILABLE,
            RemoteChannelBackgroundStartupRecovery.reconcile({ fixture.registry }) { error("系统调度器初始化失败") })
        assertEquals(before, fixture.store.raw)
    }

    @Test fun persistedGapIsRepairedWithSameGenerationAndWithoutChangingNotificationPermission() = runBlocking {
        val fixture = Fixture()
        val entry = fixture.registry.enable(fixture.peer, fixture.source)
        fixture.registry.setNotifications(entry.sourceIdentity, true)
        val before = fixture.store.raw
        val scheduled = mutableListOf<RemoteChannelBackgroundEntry>()
        val controller = fixture.controller(object : RemoteChannelBackgroundScheduler {
            override suspend fun schedule(entry: RemoteChannelBackgroundEntry) { scheduled += entry }
            override suspend fun cancel(identity: String) { error("有效许可不能取消") }
        })
        assertEquals(RemoteChannelBackgroundStartupOutcome.RECONCILED,
            RemoteChannelBackgroundStartupRecovery.reconcile({ RemoteChannelBackgroundRegistry(fixture.store) }) { controller })
        assertEquals(entry.generation, scheduled.single().generation)
        assertTrue(scheduled.single().notificationsEnabled)
        assertEquals(before, fixture.store.raw)
        assertEquals(0, fixture.notifications)
    }

    @Test fun schedulingFailureHasNoAutomaticLoopAndRetainsPermitForLaterReconciliation() = runBlocking {
        val fixture = Fixture()
        fixture.registry.enable(fixture.peer, fixture.source)
        val before = fixture.store.raw
        var attempts = 0
        var fail = true
        val controller = fixture.controller(object : RemoteChannelBackgroundScheduler {
            override suspend fun schedule(entry: RemoteChannelBackgroundEntry) { attempts++; check(!fail) { "系统入队失败" } }
            override suspend fun cancel(identity: String) { error("对账失败不能撤销有效许可") }
        })
        assertEquals(RemoteChannelBackgroundStartupOutcome.UNAVAILABLE,
            RemoteChannelBackgroundStartupRecovery.reconcile({ fixture.registry }) { controller })
        assertEquals(1, attempts)
        assertEquals(before, fixture.store.raw)
        fail = false
        assertEquals(RemoteChannelBackgroundStartupOutcome.RECONCILED,
            RemoteChannelBackgroundStartupRecovery.reconcile({ fixture.registry }) { controller })
        assertEquals(2, attempts)
        assertEquals(before, fixture.store.raw)
    }

    @Test fun disabledAndChangedEndpointEntriesOnlyCancelOldWork() = runBlocking {
        val fixture = Fixture()
        val first = fixture.registry.enable(fixture.peer, fixture.source)
        fixture.registry.disable(first.sourceIdentity)
        var cancellations = 0
        val controller = fixture.controller(object : RemoteChannelBackgroundScheduler {
            override suspend fun schedule(entry: RemoteChannelBackgroundEntry) { error("停用或变更地址不能调度") }
            override suspend fun cancel(identity: String) {
                assertFalse(fixture.registry.get(identity)!!.enabled)
                cancellations++
            }
        })
        assertEquals(RemoteChannelBackgroundStartupOutcome.RECONCILED,
            RemoteChannelBackgroundStartupRecovery.reconcile({ fixture.registry }) { controller })
        val next = fixture.registry.enable(fixture.peer, fixture.source)
        fixture.sources.save(fixture.peer, "https://changed.example/batch")
        assertEquals(RemoteChannelBackgroundStartupOutcome.RECONCILED,
            RemoteChannelBackgroundStartupRecovery.reconcile({ fixture.registry }) { controller })
        assertEquals(2, cancellations)
        val invalid = fixture.registry.get(next.sourceIdentity)!!
        assertEquals(next.generation, invalid.generation)
        assertEquals(RemoteChannelBackgroundOutcome.INVALIDATED, invalid.lastOutcome)
        assertFalse(invalid.enabled)
    }

    @Test fun cancellationPropagatesWithoutRevokingPersistedPermission() = runBlocking {
        val fixture = Fixture()
        fixture.registry.enable(fixture.peer, fixture.source)
        val before = fixture.store.raw
        val controller = fixture.controller(object : RemoteChannelBackgroundScheduler {
            override suspend fun schedule(entry: RemoteChannelBackgroundEntry) { throw CancellationException("启动对账取消") }
            override suspend fun cancel(identity: String) { error("不能撤销许可") }
        })
        assertTrue(runCatching {
            RemoteChannelBackgroundStartupRecovery.reconcile({ fixture.registry }) { controller }
        }.exceptionOrNull() is CancellationException)
        assertEquals(before, fixture.store.raw)
    }

    @Test fun foregroundDisableWaitsForStartupReconciliationThenRevokesAndCancels() = runBlocking {
        val fixture = Fixture()
        val entry = fixture.registry.enable(fixture.peer, fixture.source)
        val enqueued = CompletableDeferred<Unit>()
        var cancellations = 0
        val controller = fixture.controller(object : RemoteChannelBackgroundScheduler {
            override suspend fun schedule(entry: RemoteChannelBackgroundEntry) { enqueued.await() }
            override suspend fun cancel(identity: String) {
                assertFalse(fixture.registry.permits(identity, entry.generation))
                cancellations++
            }
        })
        // long: 用同一应用级 Controller 交错启动对账和前台停用，停用完成后不能遗留仍获许可的后台任务。
        val startup = async(start = CoroutineStart.UNDISPATCHED) {
            RemoteChannelBackgroundStartupRecovery.reconcile({ fixture.registry }) { controller }
        }
        val disable = async(start = CoroutineStart.UNDISPATCHED) { controller.setEnabled(fixture.peer, false) }
        assertTrue(fixture.registry.permits(entry.sourceIdentity, entry.generation))
        assertEquals(0, cancellations)
        enqueued.complete(Unit)
        assertEquals(RemoteChannelBackgroundStartupOutcome.RECONCILED, startup.await())
        disable.await()
        assertFalse(fixture.registry.permits(entry.sourceIdentity, entry.generation))
        assertEquals(1, cancellations)
    }

    private class Store : RemoteChannelBackgroundStateStore {
        var raw: String? = null
        override fun loadState() = raw
        override fun saveState(state: String) { raw = state }
    }

    private class Fixture {
        private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val peer = RemoteChannelPairingPolicy.prepare("startup", "sender", "key-1", "启动恢复测试", Base64.getEncoder().encodeToString(keys.public.encoded))
        val pairings = RemoteChannelPairingRegistry(object : RemoteChannelPairingStateStore {
            var raw: String? = null
            override fun loadState() = raw
            override fun saveState(state: String) { raw = state }
        }).apply { confirm(peer, peer.fingerprintSha256); setEnabled(peer.channelId, peer.senderId, peer.keyId, true) }
        val sources = RemoteChannelPullSourceRegistry(object : RemoteChannelPullSourceStateStore {
            var raw: String? = null
            override fun loadState() = raw
            override fun saveState(state: String) { raw = state }
        }, pairings)
        val source = sources.save(peer, "https://inbox.example/batch")
        val store = Store()
        val registry = RemoteChannelBackgroundRegistry(store)
        var notifications = 0
        fun controller(scheduler: RemoteChannelBackgroundScheduler) = RemoteChannelBackgroundController(registry, pairings, sources, scheduler,
            object : RemoteChannelInboxNotifier {
                override fun show(identity: String): Boolean { notifications++; return true }
                override fun cancel(identity: String) {}
            })
    }
}
