package com.longdev.xiaoling.agent

import java.io.IOException
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

/**
 * long: 每个 HTTPS 来源独立保存游标、待 ACK 消息和退避状态；状态只描述“安全入箱”事实，不能授权后台执行 Agent。
 */
data class RemoteChannelPullCheckpoint(
    val sourceIdentity: String,
    val cursor: String? = null,
    val pendingAckMessageIds: List<String> = emptyList(),
    val consecutiveFailures: Int = 0,
    val nextAttemptAtMillis: Long = 0L,
    val lastMessageId: String? = null,
    val lastErrorCode: String? = null,
    val updatedAtMillis: Long = 0L,
)

enum class RemoteChannelPullErrorCode {
    NETWORK,
    TIMEOUT,
    SERVER_5XX,
    SERVER_4XX,
    INVALID_RESPONSE,
    IDENTITY_MISMATCH,
    STORAGE,
    DISABLED,
    REJECTED_MESSAGE,
}

class RemoteChannelPullDeferredException(val nextAttemptAtMillis: Long) : IOException(
    "HTTPS 收件来源正在退避，下一次尝试时间为 $nextAttemptAtMillis",
)

class RemoteChannelPullHttpException(val statusCode: Int) : IOException(
    "HTTPS 收件返回 HTTP $statusCode",
)

class RemoteChannelPullIdentityMismatchException : IllegalArgumentException("HTTPS 来源返回了其他身份的签名消息")

interface RemoteChannelPullCheckpointStateStore {
    fun loadState(): String?

    fun saveState(state: String)
}

/** long: 连接、响应和收件箱结果都共享同一退避规则，避免不同入口各自实现一套重试语义。 */
object RemoteChannelPullReliabilityPolicy {
    const val MAX_CHECKPOINTS = 32
    const val MAX_CURSOR_CHARS = 1_024
    const val MAX_ACK_MESSAGE_IDS = 64
    const val MAX_MESSAGE_ID_CHARS = 200
    const val BASE_RETRY_DELAY_MILLIS = 30_000L
    const val MAX_RETRY_DELAY_MILLIS = 60 * 60 * 1_000L

    fun sourceIdentity(peer: RemoteChannelPeerCandidate): String =
        "${peer.channelId}\u0000${peer.senderId}\u0000${peer.keyId}\u0000${peer.fingerprintSha256}"

    fun retryDelayMillis(consecutiveFailures: Int): Long {
        require(consecutiveFailures > 0) { "失败次数必须为正数" }
        val exponent = min(consecutiveFailures - 1, 16)
        return min(MAX_RETRY_DELAY_MILLIS, BASE_RETRY_DELAY_MILLIS shl exponent)
    }

    fun classify(error: Throwable): RemoteChannelPullErrorCode = when (error) {
        is RemoteChannelPullIdentityMismatchException -> RemoteChannelPullErrorCode.IDENTITY_MISMATCH
        is RemoteChannelPullHttpException -> if (error.statusCode in 500..599) {
            RemoteChannelPullErrorCode.SERVER_5XX
        } else if (error.statusCode in 400..499) {
            RemoteChannelPullErrorCode.SERVER_4XX
        } else {
            RemoteChannelPullErrorCode.INVALID_RESPONSE
        }
        is java.nio.charset.CharacterCodingException -> RemoteChannelPullErrorCode.INVALID_RESPONSE
        is java.io.InterruptedIOException -> RemoteChannelPullErrorCode.TIMEOUT
        is IOException -> RemoteChannelPullErrorCode.NETWORK
        is IllegalArgumentException, is org.json.JSONException -> RemoteChannelPullErrorCode.INVALID_RESPONSE
        else -> RemoteChannelPullErrorCode.STORAGE
    }

    fun validate(checkpoint: RemoteChannelPullCheckpoint) {
        val identity = checkpoint.sourceIdentity.split('\u0000')
        require(identity.size == 4 && identity.take(2).all {
            it.length in 1..200 && it == it.trim()
        } && identity[2].matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) &&
            identity[3].matches(Regex("[0-9a-f]{64}"))) {
            "收件来源身份无效"
        }
        checkpoint.cursor?.let {
            require(it.isNotBlank() && it.length <= MAX_CURSOR_CHARS && '\u0000' !in it) {
                "收件游标无效"
            }
        }
        require(checkpoint.pendingAckMessageIds.size <= MAX_ACK_MESSAGE_IDS) { "待 ACK 消息过多" }
        require(checkpoint.pendingAckMessageIds.distinct().size == checkpoint.pendingAckMessageIds.size) {
            "待 ACK 消息重复"
        }
        require(checkpoint.pendingAckMessageIds.all {
            it.isNotBlank() && it.length <= MAX_MESSAGE_ID_CHARS && '\u0000' !in it
        }) { "待 ACK 消息身份无效" }
        require(checkpoint.consecutiveFailures >= 0) { "失败次数无效" }
        require(checkpoint.nextAttemptAtMillis >= 0L && checkpoint.updatedAtMillis >= 0L) {
            "收件时间状态无效"
        }
        checkpoint.lastMessageId?.let {
            require(it.isNotBlank() && it.length <= MAX_MESSAGE_ID_CHARS && '\u0000' !in it) {
                "最近消息身份无效"
            }
        }
        checkpoint.lastErrorCode?.let {
            require(it in RemoteChannelPullErrorCode.entries.map(Enum<*>::name)) { "收件错误码无效" }
        }
    }
}

/**
 * long: 失败退避持久化后才允许下一次请求；安全入箱后的消息身份预留给后续 ACK 协议，不代表服务器已收到回执。
 * 当前服务仍使用单条兼容响应，因此 cursor 暂由协议账本保存，批次 transport 接入时复用同一状态。
 */
class RemoteChannelPullCheckpointRegistry(
    private val store: RemoteChannelPullCheckpointStateStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val checkpoints = linkedMapOf<String, RemoteChannelPullCheckpoint>()
    private var unavailable = false

    init {
        runCatching { RemoteChannelPullCheckpointJsonCodec.decode(store.loadState()) }
            .onSuccess { checkpoints.putAll(it.associateBy(RemoteChannelPullCheckpoint::sourceIdentity)) }
            .onFailure { unavailable = true }
    }

    @Synchronized
    fun isAvailable(): Boolean = !unavailable

    @Synchronized
    fun get(sourceIdentity: String): RemoteChannelPullCheckpoint? {
        check(!unavailable) { "收件可靠性账本不可用" }
        return checkpoints[sourceIdentity]
    }

    @Synchronized
    fun beforeAttempt(sourceIdentity: String) {
        check(!unavailable) { "收件可靠性账本不可用" }
        RemoteChannelPullReliabilityPolicy.validate(RemoteChannelPullCheckpoint(sourceIdentity))
        // long: 未确认身份不能因容量淘汰而消失；满账本或满 ACK 队列在建连前阻断，保留全部旧记录。
        check(sourceIdentity in checkpoints || checkpoints.size < RemoteChannelPullReliabilityPolicy.MAX_CHECKPOINTS) {
            "收件可靠性账本来源已达到上限"
        }
        val current = checkpoints[sourceIdentity] ?: return
        if (current.nextAttemptAtMillis > clock()) {
            throw RemoteChannelPullDeferredException(current.nextAttemptAtMillis)
        }
        check(current.pendingAckMessageIds.size < RemoteChannelPullReliabilityPolicy.MAX_ACK_MESSAGE_IDS) {
            "待 ACK 消息已达到上限"
        }
    }

    @Synchronized
    fun recordAccepted(sourceIdentity: String, messageId: String, cursor: String? = null, awaitingAck: Boolean = true) {
        val now = clock()
        val current = checkpoints[sourceIdentity] ?: RemoteChannelPullCheckpoint(sourceIdentity)
        val pending = if (awaitingAck) (current.pendingAckMessageIds + messageId).distinct() else current.pendingAckMessageIds
        save(current.copy(
            cursor = cursor ?: current.cursor,
            pendingAckMessageIds = pending,
            consecutiveFailures = 0,
            nextAttemptAtMillis = now,
            lastMessageId = messageId,
            lastErrorCode = null,
            updatedAtMillis = now,
        ))
    }

    @Synchronized
    fun recordDuplicate(sourceIdentity: String, messageId: String) {
        val now = clock()
        val current = checkpoints[sourceIdentity] ?: RemoteChannelPullCheckpoint(sourceIdentity)
        save(current.copy(
            consecutiveFailures = 0,
            nextAttemptAtMillis = now,
            lastMessageId = messageId,
            lastErrorCode = null,
            updatedAtMillis = now,
        ))
    }

    @Synchronized
    fun recordRetryableFailure(sourceIdentity: String, error: Throwable) {
        val now = clock()
        val current = checkpoints[sourceIdentity] ?: RemoteChannelPullCheckpoint(sourceIdentity)
        val failures = if (current.consecutiveFailures == Int.MAX_VALUE) Int.MAX_VALUE else current.consecutiveFailures + 1
        val delay = RemoteChannelPullReliabilityPolicy.retryDelayMillis(failures)
        save(current.copy(
            consecutiveFailures = failures,
            nextAttemptAtMillis = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay,
            lastErrorCode = RemoteChannelPullReliabilityPolicy.classify(error).name,
            updatedAtMillis = now,
        ))
    }

    @Synchronized
    fun recordNonRetryableFailure(sourceIdentity: String, code: RemoteChannelPullErrorCode) {
        val now = clock()
        val current = checkpoints[sourceIdentity] ?: RemoteChannelPullCheckpoint(sourceIdentity)
        save(current.copy(
            // long: 协议或验签拒绝只记录错误，不安排自动重试；用户修复地址、响应或身份后仍可主动再拉取。
            consecutiveFailures = 0,
            nextAttemptAtMillis = now,
            lastErrorCode = code.name,
            updatedAtMillis = now,
        ))
    }

    @Synchronized
    fun acknowledge(sourceIdentity: String, messageId: String) {
        val current = checkpoints[sourceIdentity] ?: return
        save(current.copy(
            pendingAckMessageIds = current.pendingAckMessageIds.filterNot { it == messageId },
            updatedAtMillis = clock(),
        ))
    }

    private fun save(next: RemoteChannelPullCheckpoint) {
        check(!unavailable) { "收件可靠性账本不可用" }
        RemoteChannelPullReliabilityPolicy.validate(next)
        val updated = LinkedHashMap(checkpoints).apply { put(next.sourceIdentity, next) }
        val encoded = RemoteChannelPullCheckpointJsonCodec.encode(updated.values.toList())
        runCatching { store.saveState(encoded) }
            .onFailure {
                unavailable = true
                throw IllegalStateException("收件可靠性账本无法持久化", it)
            }
        // long: commit 成功后才发布新内存视图，失败既不能让游标前进，也不能让旧待确认身份被覆盖。
        checkpoints.clear()
        checkpoints.putAll(updated)
    }
}

/** long: 账本采用严格字段集合，损坏时整个注册表不可用，避免把旧游标误当成空状态继续收件。 */
object RemoteChannelPullCheckpointJsonCodec {
    private const val SCHEMA_VERSION = 1
    private const val MAX_STATE_BYTES = 128 * 1024

    fun encode(checkpoints: List<RemoteChannelPullCheckpoint>): String {
        require(checkpoints.size <= RemoteChannelPullReliabilityPolicy.MAX_CHECKPOINTS) { "收件可靠性账本过大" }
        require(checkpoints.map { it.sourceIdentity }.distinct().size == checkpoints.size) { "收件来源身份重复" }
        checkpoints.forEach(RemoteChannelPullReliabilityPolicy::validate)
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("checkpoints", JSONArray().apply {
                checkpoints.forEach { checkpoint ->
                    put(JSONObject()
                        .put("sourceIdentity", checkpoint.sourceIdentity)
                        .put("cursor", checkpoint.cursor ?: JSONObject.NULL)
                        .put("pendingAckMessageIds", JSONArray(checkpoint.pendingAckMessageIds))
                        .put("consecutiveFailures", checkpoint.consecutiveFailures)
                        .put("nextAttemptAtMillis", checkpoint.nextAttemptAtMillis)
                        .put("lastMessageId", checkpoint.lastMessageId ?: JSONObject.NULL)
                        .put("lastErrorCode", checkpoint.lastErrorCode ?: JSONObject.NULL)
                        .put("updatedAtMillis", checkpoint.updatedAtMillis))
                }
            })
            .toString()
            .also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) { "收件可靠性账本过大" } }
    }

    fun decode(raw: String?): List<RemoteChannelPullCheckpoint> {
        if (raw == null) return emptyList()
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) { "收件可靠性账本过大" }
        val root = JSONObject(raw)
        require(root.keys().asSequence().toSet() == setOf("schemaVersion", "checkpoints")) { "收件可靠性账本字段无效" }
        require(root.opt("schemaVersion") == SCHEMA_VERSION) { "收件可靠性账本版本无效" }
        val array = root.getJSONArray("checkpoints")
        require(array.length() <= RemoteChannelPullReliabilityPolicy.MAX_CHECKPOINTS) { "收件可靠性账本来源过多" }
        val result = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            require(item.keys().asSequence().toSet() == setOf(
                "sourceIdentity", "cursor", "pendingAckMessageIds", "consecutiveFailures",
                "nextAttemptAtMillis", "lastMessageId", "lastErrorCode", "updatedAtMillis",
            )) { "收件可靠性账本条目字段无效" }
            val pending = item.getJSONArray("pendingAckMessageIds")
            require(pending.length() <= RemoteChannelPullReliabilityPolicy.MAX_ACK_MESSAGE_IDS) { "待 ACK 消息过多" }
            fun string(name: String): String = (item.opt(name) as? String)
                ?: throw IllegalArgumentException("收件可靠性账本 $name 必须是字符串")
            fun nullableString(name: String): String? = if (item.isNull(name)) null else string(name)
            fun integer(name: String): Long {
                val value = item.opt(name)
                require(value is Int || value is Long) { "收件可靠性账本 $name 必须是整数" }
                return (value as Number).toLong()
            }
            val failures = integer("consecutiveFailures")
            require(failures in 0..Int.MAX_VALUE.toLong()) { "失败次数无效" }
            val checkpoint = RemoteChannelPullCheckpoint(
                sourceIdentity = string("sourceIdentity"),
                cursor = nullableString("cursor"),
                pendingAckMessageIds = (0 until pending.length()).map {
                    pending.opt(it) as? String ?: throw IllegalArgumentException("待 ACK 消息身份必须是字符串")
                },
                consecutiveFailures = failures.toInt(),
                nextAttemptAtMillis = integer("nextAttemptAtMillis"),
                lastMessageId = nullableString("lastMessageId"),
                lastErrorCode = nullableString("lastErrorCode"),
                updatedAtMillis = integer("updatedAtMillis"),
            )
            RemoteChannelPullReliabilityPolicy.validate(checkpoint)
            checkpoint
        }
        require(result.map { it.sourceIdentity }.distinct().size == result.size) { "收件来源身份重复" }
        return result
    }
}
