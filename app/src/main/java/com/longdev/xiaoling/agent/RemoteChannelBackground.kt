package com.longdev.xiaoling.agent

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

enum class RemoteChannelBackgroundOutcome { SYNCED, ACK_PENDING, DEFERRED, RETRYABLE_ERROR, REJECTED, INVALIDATED, DISABLED }

data class RemoteChannelBackgroundEntry(
    val sourceIdentity: String,
    val endpointSha256: String,
    val generation: String,
    val enabled: Boolean = true,
    val notificationsEnabled: Boolean = false,
    val notifiedDigest: String? = null,
    val lastAttemptAtMillis: Long = 0,
    val lastOutcome: RemoteChannelBackgroundOutcome? = null,
    val receiverBindingSha256: String? = null,
)

interface RemoteChannelBackgroundStateStore {
    fun loadState(): String?
    fun saveState(state: String)
}

/** long: 后台许可独立于公钥验签许可，并绑定地址与代次；旧 Worker 不能继承新地址或重新启用后的许可。 */
class RemoteChannelBackgroundRegistry(
    private val store: RemoteChannelBackgroundStateStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var unavailable = false
    fun list(): List<RemoteChannelBackgroundEntry> = synchronized(LOCK) {
        check(!unavailable) { "后台收件许可账本已冻结" }
        try { decode(store.loadState()) } catch (error: Exception) {
            unavailable = true
            throw IllegalStateException("后台收件许可账本不可用", error)
        }
    }
    fun get(identity: String): RemoteChannelBackgroundEntry? = list().singleOrNull { it.sourceIdentity == identity }
    fun permits(identity: String, generation: String): Boolean = get(identity)?.let { it.enabled && it.generation == generation } == true

    fun enable(peer: RemoteChannelPeerCandidate, source: RemoteChannelPullSource): RemoteChannelBackgroundEntry = synchronized(LOCK) {
        val identity = RemoteChannelPullReliabilityPolicy.sourceIdentity(peer)
        val entry = RemoteChannelBackgroundEntry(identity, RemoteChannelBatchProtocol.endpointHash(source), UUID.randomUUID().toString(),
            receiverBindingSha256 = receiverBinding(source))
        val retained = list().filterNot { it.sourceIdentity == identity }
        save(retained + entry)
        entry
    }

    fun disable(identity: String, outcome: RemoteChannelBackgroundOutcome = RemoteChannelBackgroundOutcome.DISABLED) = synchronized(LOCK) {
        save(list().map { if (it.sourceIdentity == identity) it.copy(enabled = false, lastOutcome = outcome) else it })
    }

    fun invalidate(identity: String, generation: String, cancelNotification: () -> Unit) = synchronized(LOCK) {
        val current = get(identity)
        // long: 旧 Worker 检查地址时用户可能已经重新启用；撤销和取消通知只作用于原代次，不能覆盖新许可或新通知。
        if (current?.enabled == true && current.generation == generation) {
            update(current.copy(enabled = false, lastAttemptAtMillis = clock(), lastOutcome = RemoteChannelBackgroundOutcome.INVALIDATED))
            cancelNotification()
        }
    }

    fun setNotifications(identity: String, enabled: Boolean) = synchronized(LOCK) {
        val current = get(identity) ?: error("请先启用后台收件")
        require(current.enabled) { "请先启用后台收件" }
        update(current.copy(notificationsEnabled = enabled, notifiedDigest = null))
    }

    fun record(identity: String, generation: String, outcome: RemoteChannelBackgroundOutcome) = synchronized(LOCK) {
        val current = get(identity) ?: return@synchronized
        if (current.generation == generation && current.enabled) {
            update(current.copy(lastAttemptAtMillis = clock(), lastOutcome = outcome))
        }
    }

    fun notifyIfNeeded(identity: String, generation: String, digest: String, deliver: () -> Boolean) = synchronized(LOCK) {
        val current = get(identity) ?: return@synchronized
        // long: 通知与停用共用许可锁；停用随后取消通知时，不会被迟到的 Worker 再发出旧提示。
        if (current.generation == generation && current.enabled && current.notificationsEnabled && current.notifiedDigest != digest && deliver())
            update(current.copy(notifiedDigest = digest))
    }

    private fun update(entry: RemoteChannelBackgroundEntry) = save(list().map { if (it.sourceIdentity == entry.sourceIdentity) entry else it })
    private fun save(entries: List<RemoteChannelBackgroundEntry>) {
        check(!unavailable) { "后台收件许可账本已冻结" }
        try { store.saveState(encode(entries)) } catch (error: Exception) {
            // long: commit 失败时磁盘与内存可能分歧，当前进程必须冻结许可，不能用旧启用状态继续收件。
            unavailable = true
            throw IllegalStateException("后台收件许可无法提交，已冻结", error)
        }
    }

    companion object {
        private val LOCK = Any()
        private val HASH = Regex("[0-9a-f]{64}")
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun receiverBinding(source: RemoteChannelPullSource): String? = if (!source.receiverAuthRequired) null else
            digest(RemoteChannelReceiverAuthProtocol.manifest(source.receiverAuthorization ?: error("接收公钥尚未完成登记")))

        fun matchesSource(entry: RemoteChannelBackgroundEntry, source: RemoteChannelPullSource): Boolean =
            RemoteChannelBatchProtocol.endpointHash(source) == entry.endpointSha256 &&
                runCatching { receiverBinding(source) == entry.receiverBindingSha256 }.getOrDefault(false)

        fun encode(entries: List<RemoteChannelBackgroundEntry>): String {
            require(entries.size <= RemoteChannelBatchProtocol.MAX_STATES && entries.map { it.sourceIdentity }.distinct().size == entries.size) { "后台来源过多或重复" }
            val raw = JSONObject().put("schemaVersion", 2).put("entries", JSONArray().apply { entries.forEach { entry ->
                RemoteChannelPullReliabilityPolicy.validate(RemoteChannelPullCheckpoint(entry.sourceIdentity))
                require(HASH.matches(entry.endpointSha256) && runCatching { UUID.fromString(entry.generation).toString() == entry.generation }.getOrDefault(false) &&
                    entry.lastAttemptAtMillis >= 0 && (entry.notifiedDigest == null || HASH.matches(entry.notifiedDigest)) &&
                    (entry.receiverBindingSha256 == null || HASH.matches(entry.receiverBindingSha256))) { "后台来源状态无效" }
                put(JSONObject().put("sourceIdentity", entry.sourceIdentity).put("endpointSha256", entry.endpointSha256)
                    .put("generation", entry.generation).put("enabled", entry.enabled).put("notificationsEnabled", entry.notificationsEnabled)
                    .put("notifiedDigest", entry.notifiedDigest ?: JSONObject.NULL).put("lastAttemptAtMillis", entry.lastAttemptAtMillis)
                    .put("lastOutcome", entry.lastOutcome?.name ?: JSONObject.NULL)
                    .put("receiverBindingSha256", entry.receiverBindingSha256 ?: JSONObject.NULL))
            } }).toString()
            require(raw.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "后台收件状态过大" }
            return raw
        }

        fun decode(raw: String?): List<RemoteChannelBackgroundEntry> {
            if (raw == null) return emptyList()
            require(raw.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "后台收件状态过大" }
            val root = JSONObject(raw)
            RemoteChannelBatchProtocol.keys(root, setOf("schemaVersion", "entries"))
            require(root.opt("schemaVersion") in setOf(1, 2)) { "后台收件版本无效" }
            val version = root.getInt("schemaVersion")
            val array = root.getJSONArray("entries")
            require(array.length() <= RemoteChannelBatchProtocol.MAX_STATES) { "后台来源过多" }
            val entries = (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                RemoteChannelBatchProtocol.keys(item, setOf("sourceIdentity", "endpointSha256", "generation", "enabled", "notificationsEnabled", "notifiedDigest", "lastAttemptAtMillis", "lastOutcome") +
                    if (version == 1) emptySet() else setOf("receiverBindingSha256"))
                fun str(key: String) = RemoteChannelBatchProtocol.string(item, key)
                fun bool(key: String) = item.opt(key) as? Boolean ?: error("后台 $key 必须是布尔值")
                RemoteChannelBackgroundEntry(str("sourceIdentity"), str("endpointSha256"), str("generation"), bool("enabled"), bool("notificationsEnabled"),
                    RemoteChannelBatchProtocol.nullableString(item, "notifiedDigest"), RemoteChannelBatchProtocol.integer(item, "lastAttemptAtMillis"),
                    RemoteChannelBatchProtocol.nullableString(item, "lastOutcome")?.let(RemoteChannelBackgroundOutcome::valueOf),
                    if (version == 1) null else RemoteChannelBatchProtocol.nullableString(item, "receiverBindingSha256"))
            }
            encode(entries)
            return entries
        }
    }
}

interface RemoteChannelBackgroundScheduler {
    suspend fun schedule(entry: RemoteChannelBackgroundEntry)
    suspend fun ensureScheduled(entry: RemoteChannelBackgroundEntry) = schedule(entry)
    suspend fun cancel(identity: String)
}

interface RemoteChannelInboxNotifier {
    fun show(identity: String): Boolean
    fun cancel(identity: String)
}

class RemoteChannelBackgroundController(
    private val registry: RemoteChannelBackgroundRegistry,
    private val pairings: RemoteChannelPairingRegistry,
    private val sources: RemoteChannelPullSourceRegistry,
    private val scheduler: RemoteChannelBackgroundScheduler,
    private val notifier: RemoteChannelInboxNotifier,
) {
    private val changeMutex = Mutex()
    fun entry(peer: RemoteChannelPeerCandidate) = registry.get(RemoteChannelPullReliabilityPolicy.sourceIdentity(peer))

    suspend fun setEnabled(peer: RemoteChannelPeerCandidate, enabled: Boolean) = changeMutex.withLock {
        val identity = RemoteChannelPullReliabilityPolicy.sourceIdentity(peer)
        if (!enabled) {
            // long: 先持久撤销许可，再取消系统任务；取消完成前返回的旧响应也会被代次门禁拒绝。
            registry.disable(identity)
            scheduler.cancel(identity)
            notifier.cancel(identity)
            return@withLock
        }
        require(pairings.active(peer.channelId, peer.senderId, peer.keyId) == peer) { "请先启用当前配对身份" }
        val source = sources.sourceFor(peer) ?: error("请先登记 HTTPS 来源")
        val entry = registry.enable(peer, source)
        try { scheduler.schedule(entry) } catch (error: Exception) {
            registry.disable(identity)
            runCatching { scheduler.cancel(identity) }
            throw error
        }
    }

    suspend fun reconcile() = changeMutex.withLock {
        val peers = pairings.list()
        for (entry in registry.list()) {
            val peer = peers.singleOrNull { it.enabled && RemoteChannelPullReliabilityPolicy.sourceIdentity(it.candidate) == entry.sourceIdentity }?.candidate
            val source = peer?.let(sources::sourceFor)
            if (entry.enabled && source != null && RemoteChannelBackgroundRegistry.matchesSource(entry, source)) {
                // long: 许可提交与系统入队不在同一个数据库；启动或配对页对账只补同一已授权代次的缺失任务，不替新地址创建许可。
                scheduler.ensureScheduled(entry)
            } else {
                if (entry.enabled) registry.disable(entry.sourceIdentity, RemoteChannelBackgroundOutcome.INVALIDATED)
                scheduler.cancel(entry.sourceIdentity)
                notifier.cancel(entry.sourceIdentity)
            }
        }
    }

    fun setNotifications(peer: RemoteChannelPeerCandidate, enabled: Boolean) {
        val identity = RemoteChannelPullReliabilityPolicy.sourceIdentity(peer)
        registry.setNotifications(identity, enabled)
        if (!enabled) notifier.cancel(identity)
    }
}

/** long: Worker 每次只调用一次既有同步服务，草稿/ACK 的事务与验签不另造后台旁路。 */
class RemoteChannelBackgroundSynchronizer(
    private val registry: RemoteChannelBackgroundRegistry,
    private val pairings: RemoteChannelPairingRegistry,
    private val sources: RemoteChannelPullSourceRegistry,
    private val inbox: RemoteChannelInboxGateway,
    private val synchronize: suspend (RemoteChannelPeerCandidate, () -> Boolean) -> RemoteChannelPullSyncResult,
    private val notifier: RemoteChannelInboxNotifier,
    private val checkpoints: RemoteChannelPullCheckpointRegistry? = null,
) {
    suspend fun run(identity: String, generation: String): RemoteChannelBackgroundOutcome {
        val entry = registry.get(identity)
        if (entry == null || !entry.enabled || entry.generation != generation) return RemoteChannelBackgroundOutcome.DISABLED
        val peer = pairings.list().singleOrNull { it.enabled && RemoteChannelPullReliabilityPolicy.sourceIdentity(it.candidate) == identity }?.candidate
        val source = peer?.let(sources::sourceFor)
        if (peer == null || source == null || !RemoteChannelBackgroundRegistry.matchesSource(entry, source)) {
            registry.invalidate(identity, generation) { notifier.cancel(identity) }
            return RemoteChannelBackgroundOutcome.INVALIDATED
        }
        val outcome = try {
            // long: 许可代次和认证绑定一起复核；同地址启用接收认证后，旧裸请求任务也不能继续入箱或清回执。
            when (val result = synchronize(peer) { registry.permits(identity, generation) && sources.sourceFor(peer) == source }) {
                is RemoteChannelPullSyncResult.Batch -> if (!result.ackPending) RemoteChannelBackgroundOutcome.SYNCED
                    else if (checkpoints?.get(identity)?.lastErrorCode?.let { it !in setOf("NETWORK", "TIMEOUT", "SERVER_5XX") } == true)
                        RemoteChannelBackgroundOutcome.REJECTED else RemoteChannelBackgroundOutcome.ACK_PENDING
                is RemoteChannelPullSyncResult.Single -> if (result.result is RemoteChannelReceiveResult.Accepted ||
                    (result.result as? RemoteChannelReceiveResult.Rejected)?.reason == RemoteChannelRejectionReason.DUPLICATE_MESSAGE) RemoteChannelBackgroundOutcome.SYNCED
                    else RemoteChannelBackgroundOutcome.REJECTED
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (deferred: RemoteChannelPullDeferredException) { RemoteChannelBackgroundOutcome.DEFERRED
        } catch (error: Exception) {
            if (RemoteChannelPullReliabilityPolicy.classify(error) in setOf(RemoteChannelPullErrorCode.NETWORK, RemoteChannelPullErrorCode.TIMEOUT, RemoteChannelPullErrorCode.SERVER_5XX))
                RemoteChannelBackgroundOutcome.RETRYABLE_ERROR else RemoteChannelBackgroundOutcome.REJECTED
        }
        registry.record(identity, generation, outcome)
        if (sources.sourceFor(peer) != source) {
            registry.invalidate(identity, generation) { notifier.cancel(identity) }
            return RemoteChannelBackgroundOutcome.INVALIDATED
        }
        val current = registry.get(identity)
        if (current?.enabled == true && current.generation == generation && current.notificationsEnabled) {
            val pending = (inbox.status() as? RemoteChannelInboxStatus.Ready)?.pending ?: error("加密收件箱不可用")
            val keys = pending.filter { it.draft.channelId == peer.channelId && it.draft.senderId == peer.senderId }
                .map { RemoteChannelPolicy.dedupeKey(it.draft) }.sorted()
            if (keys.isEmpty()) notifier.cancel(identity)
            else {
                // long: 通知摘要只使用已安全入箱的身份；重复批次和 ACK 补发不重复打扰，进程中断后的未通知正文可补提示。
                val digest = RemoteChannelBackgroundRegistry.digest(JSONArray(keys).toString())
                registry.notifyIfNeeded(identity, generation, digest) { notifier.show(identity) }
            }
        }
        return outcome
    }

    companion object {
        fun shouldRetry(outcome: RemoteChannelBackgroundOutcome, attempt: Int): Boolean = attempt < 3 && outcome in
            setOf(RemoteChannelBackgroundOutcome.ACK_PENDING, RemoteChannelBackgroundOutcome.DEFERRED, RemoteChannelBackgroundOutcome.RETRYABLE_ERROR)
    }
}
