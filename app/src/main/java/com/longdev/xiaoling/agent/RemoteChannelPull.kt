package com.longdev.xiaoling.agent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

data class RemoteChannelPullSource(
    val channelId: String,
    val senderId: String,
    val keyId: String,
    val fingerprintSha256: String,
    val url: String,
    val receiverAuthRequired: Boolean = false,
    val receiverAuthorization: RemoteChannelReceiverAuthorization? = null,
    val registered: Boolean = true,
)

interface RemoteChannelPullSourceStateStore {
    fun loadState(): String?
    fun saveState(state: String)
}

/** long: 网络地址只由用户在已配对身份下登记；信封内容不能决定下一次向哪里发请求。 */
object RemoteChannelPullPolicy {
    const val MAX_URL_CHARS = 2_048
    private const val MAX_SOURCES = RemoteChannelPairingPolicy.MAX_PEERS
    private const val MAX_STATE_BYTES = 96 * 1024

    fun endpoint(raw: String): HttpUrl {
        require(raw.length in 1..MAX_URL_CHARS && raw == raw.trim() && raw.none(Char::isWhitespace)) {
            "HTTPS 收件地址无效"
        }
        val url = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("HTTPS 收件地址无效")
        require(url.scheme == "https" && url.username.isEmpty() && url.password.isEmpty() &&
            url.fragment == null && url.query == null
        ) { "收件地址只能使用无账号、无查询参数的 HTTPS URL" }
        require(url.host != "localhost" && !url.host.endsWith(".localhost")) { "收件地址不能指向本机" }
        require(!url.host.matches(Regex("[0-9.]+")) && ':' !in url.host) { "收件地址必须使用 DNS 域名" }
        return url
    }

    fun publicAddresses(addresses: List<InetAddress>): List<InetAddress> {
        require(addresses.isNotEmpty() && addresses.all(::isPublicAddress)) {
            "HTTPS 收件地址解析到本机、私有或保留网络"
        }
        return addresses
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            val c = bytes[2].toInt() and 0xff
            return a in 1..223 && a != 10 && a != 127 &&
                !(a == 100 && b in 64..127) && !(a == 169 && b == 254) &&
                !(a == 172 && b in 16..31) && !(a == 192 && b == 168) &&
                !(a == 192 && b == 0 && c == 0) && !(a == 192 && b == 88 && c == 99) &&
                !(a == 192 && b == 0 && c == 2) &&
                !(a == 198 && b in 18..19) && !(a == 198 && b == 51 && c == 100) &&
                !(a == 203 && b == 0 && c == 113)
        }
        // long: IPv6 的 ULA、文档地址和 IPv4 映射地址都不应成为绕过 IPv4 私网判断的入口。
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        val fourth = bytes[3].toInt() and 0xff
        return first in 0x20..0x3f && !(first == 0x20 && second == 0x02) &&
            !(first == 0x20 && second == 0x01 && (third == 0x00 || (third == 0x0d && fourth == 0xb8)))
    }

    fun encode(sources: List<RemoteChannelPullSource>): String {
        require(sources.size <= MAX_SOURCES && sources.map { it.identity() }.distinct().size == sources.size) {
            "HTTPS 收件来源数量或身份无效"
        }
        val root = JSONObject().put("schemaVersion", 2).put("sources", JSONArray().apply {
            sources.forEach { source ->
                require(source.channelId.length in 1..200 && source.channelId == source.channelId.trim() &&
                    source.senderId.length in 1..200 && source.senderId == source.senderId.trim() &&
                    '\u0000' !in source.channelId && '\u0000' !in source.senderId &&
                    source.keyId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))) {
                    "收件来源身份无效"
                }
                require(source.fingerprintSha256.matches(Regex("[0-9a-f]{64}"))) { "收件来源指纹无效" }
                val url = endpoint(source.url)
                require(url.toString() == source.url) { "收件地址未规范化" }
                require(source.receiverAuthRequired || (source.receiverAuthorization == null && source.registered)) { "收件认证状态无效" }
                source.receiverAuthorization?.let { auth ->
                    RemoteChannelReceiverAuthProtocol.validate(auth)
                    require(auth.channelId == source.channelId && auth.senderId == source.senderId && auth.keyId == source.keyId &&
                        auth.senderFingerprintSha256 == source.fingerprintSha256 && auth.endpointSha256 == RemoteChannelBatchProtocol.endpointHash(source)) {
                        "接收登记与来源不匹配"
                    }
                }
                put(JSONObject().put("channelId", source.channelId).put("senderId", source.senderId)
                    .put("keyId", source.keyId).put("fingerprintSha256", source.fingerprintSha256)
                    .put("url", source.url).put("receiverAuthRequired", source.receiverAuthRequired).put("registered", source.registered)
                    .put("receiverAuthorization", source.receiverAuthorization?.let { JSONObject(RemoteChannelReceiverAuthProtocol.manifest(it)) } ?: JSONObject.NULL))
            }
        })
        return root.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) { "收件来源状态过大" } }
    }

    fun decode(raw: String): List<RemoteChannelPullSource> {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) { "收件来源状态过大" }
        val root = JSONObject(raw)
        require(root.keys().asSequence().toSet() == setOf("schemaVersion", "sources") && root.opt("schemaVersion") in setOf(1, 2)) {
            "收件来源状态版本或字段无效"
        }
        val array = root.getJSONArray("sources")
        require(array.length() <= MAX_SOURCES) { "收件来源过多" }
        val sources = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val version = root.getInt("schemaVersion")
            val fields = setOf("channelId", "senderId", "keyId", "fingerprintSha256", "url")
            require(item.keys().asSequence().toSet() == if (version == 1) fields else fields +
                setOf("receiverAuthRequired", "receiverAuthorization", "registered")) {
                "收件来源字段无效"
            }
            fun field(name: String, max: Int): String = (item.opt(name) as? String)?.takeIf { it.length in 1..max }
                ?: throw IllegalArgumentException("收件来源 $name 无效")
            fun bool(name: String) = item.opt(name) as? Boolean ?: error("收件来源 $name 必须是布尔值")
            RemoteChannelPullSource(field("channelId", 200), field("senderId", 200), field("keyId", 64),
                field("fingerprintSha256", 64), field("url", MAX_URL_CHARS),
                if (version == 1) false else bool("receiverAuthRequired"),
                if (version == 1 || item.isNull("receiverAuthorization")) null else RemoteChannelReceiverAuthProtocol.decodeManifest(item.getJSONObject("receiverAuthorization")),
                if (version == 1) true else bool("registered"))
        }
        require(sources.map { it.identity() }.distinct().size == sources.size) { "收件来源身份重复" }
        // long: 读盘也走写入校验，损坏账本不能被当成空白来源列表继续收件。
        encode(sources)
        return sources
    }

    private fun RemoteChannelPullSource.identity() = Triple(channelId, senderId, keyId)
}

/** long: 地址与公钥指纹一起持久化；轮换 keyId 或公钥后必须重新登记来源。 */
class RemoteChannelPullSourceRegistry(
    private val store: RemoteChannelPullSourceStateStore,
    private val pairings: RemoteChannelPairingRegistry,
) {
    private var unavailable = false
    @Synchronized private fun stored(): List<RemoteChannelPullSource> {
        check(!unavailable) { "HTTPS 来源账本已冻结" }
        return store.loadState()?.let(RemoteChannelPullPolicy::decode).orEmpty()
    }

    @Synchronized fun list(): List<RemoteChannelPullSource> = stored().filter { it.registered }

    @Synchronized fun sourceFor(peer: RemoteChannelPeerCandidate): RemoteChannelPullSource? =
        list().firstOrNull { it.channelId == peer.channelId && it.senderId == peer.senderId &&
            it.keyId == peer.keyId && it.fingerprintSha256 == peer.fingerprintSha256 }

    @Synchronized fun save(peer: RemoteChannelPeerCandidate, rawUrl: String): RemoteChannelPullSource {
        require(pairings.list().any { it.candidate == peer }) { "配对身份已变化，请重新核对公钥" }
        val url = RemoteChannelPullPolicy.endpoint(rawUrl).toString()
        val all = stored()
        val previous = all.singleOrNull { it.channelId == peer.channelId && it.senderId == peer.senderId && it.keyId == peer.keyId }
        // long: 认证地址和公钥不能被登记操作降级；换来源须使用新的发送方 keyId 并重新核对配对。
        require(previous?.receiverAuthRequired != true || (previous.url == url && previous.fingerprintSha256 == peer.fingerprintSha256)) {
            "认证来源不能更换地址或公钥，请使用新的 keyId 重新配对"
        }
        val next = if (previous?.url == url && previous.fingerprintSha256 == peer.fingerprintSha256) previous.copy(registered = true)
            else RemoteChannelPullSource(peer.channelId, peer.senderId, peer.keyId, peer.fingerprintSha256, url)
        val current = all.filterNot { it.channelId == peer.channelId && it.senderId == peer.senderId && it.keyId == peer.keyId }
        persist(current + next)
        return next
    }

    @Synchronized fun remove(peer: RemoteChannelPeerCandidate): Boolean {
        val current = stored()
        val source = current.singleOrNull { it.registered && it.channelId == peer.channelId && it.senderId == peer.senderId && it.keyId == peer.keyId } ?: return false
        // long: 移除只停止网络登记，认证标记保留；重新登记同一身份不能无意绕过已有接收公钥绑定。
        val next = if (source.receiverAuthRequired) current.map { if (it == source) it.copy(registered = false) else it }
            else current - source
        persist(next)
        return true
    }

    @Synchronized fun requireReceiverAuth(peer: RemoteChannelPeerCandidate): RemoteChannelPullSource {
        val source = sourceFor(peer) ?: error("请先登记 HTTPS 来源")
        val next = source.copy(receiverAuthRequired = true)
        persist(stored().map { if (it == source) next else it })
        return next
    }

    @Synchronized fun registerReceiver(peer: RemoteChannelPeerCandidate, expected: RemoteChannelPullSource,
                                      auth: RemoteChannelReceiverAuthorization): RemoteChannelPullSource {
        require(pairings.list().any { it.candidate == peer }) { "配对身份已变化" }
        require(sourceFor(peer) == expected && expected.receiverAuthRequired) { "HTTPS 认证来源已变化" }
        require(expected.receiverAuthorization == null || expected.receiverAuthorization == auth) { "接收公钥不能替换原登记" }
        val next = expected.copy(receiverAuthorization = auth)
        persist(stored().map { if (it == expected) next else it })
        return next
    }

    private fun persist(sources: List<RemoteChannelPullSource>) {
        val raw = RemoteChannelPullPolicy.encode(sources)
        try { store.saveState(raw) } catch (error: Exception) {
            // long: 落盘失败不能继续使用旧裸来源；冻结直到重建后重新核对磁盘，避免创建密钥与配置分歧。
            unavailable = true
            throw IllegalStateException("HTTPS 来源无法提交，已冻结", error)
        }
    }
}

/** long: DNS 校验置于 OkHttp 真正建连时，避免先解析校验、再解析连接之间被 DNS rebinding 替换。 */
class RemoteChannelHttpsPuller(
    dns: Dns = Dns.SYSTEM,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                RemoteChannelPullPolicy.publicAddresses(dns.lookup(hostname))
        })
        .cookieJar(CookieJar.NO_COOKIES)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun fetch(url: String): RemoteChannelEnvelope {
        val endpoint = RemoteChannelPullPolicy.endpoint(url)
        val request = Request.Builder().url(endpoint).get()
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .build()
        return RemoteChannelSignedEnvelopeCodec.decode(executeJson(request))
    }

    suspend fun fetchBatchOrSingle(url: String, state: RemoteChannelBatchState): RemoteChannelPullResponse {
        val request = Request.Builder().url(RemoteChannelPullPolicy.endpoint(url)).get()
            .header("Accept", "application/json").header("Cache-Control", "no-store")
            .header("X-Xiaoling-Protocol", "1").header("X-Xiaoling-Receiver", state.receiverId)
            .apply { state.cursor?.let { header("X-Xiaoling-Cursor", Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))) } }
            .build()
        return RemoteChannelBatchProtocol.decodeResponse(executeJson(request))
    }

    suspend fun acknowledge(url: String, peer: RemoteChannelPeerCandidate, state: RemoteChannelBatchState) {
        // long: ACK 固定发往用户登记的同一地址，回执或消息正文不能提供新的网络目标。
        val request = Request.Builder().url(RemoteChannelPullPolicy.endpoint(url))
            .header("Accept", "application/json").header("Cache-Control", "no-store")
            .post(RemoteChannelBatchProtocol.ackRequest(peer, state).toRequestBody("application/json".toMediaType())).build()
        RemoteChannelBatchProtocol.verifyAck(executeJson(request), peer, state)
    }

    suspend fun authenticatedPull(url: String, envelope: String): RemoteChannelPullResponse {
        val response = RemoteChannelBatchProtocol.decodeResponse(executeJson(authenticatedRequest(url, envelope)))
        // long: 认证发送队列只返回签名批次；拒绝九字段响应，避免服务端错误或降级混入兼容路径。
        require(response is RemoteChannelPullResponse.Batch) { "认证来源必须返回签名批次" }
        return response
    }

    suspend fun authenticatedAck(url: String, envelope: String, peer: RemoteChannelPeerCandidate, state: RemoteChannelBatchState) {
        RemoteChannelBatchProtocol.verifyAck(executeJson(authenticatedRequest(url, envelope)), peer, state)
    }

    private fun authenticatedRequest(url: String, envelope: String): Request =
        Request.Builder().url(RemoteChannelPullPolicy.endpoint(url))
            .header("Accept", "application/json").header("Cache-Control", "no-store").header("X-Xiaoling-Receiver-Auth", "1")
            .post(envelope.toRequestBody("application/json".toMediaType())).build()

    private suspend fun executeJson(request: Request): String {
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            // long: 取消覆盖响应头和正文读取整个阶段；停滞正文不能占住已退出的前台拉取任务。
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // long: 保留原始异常类型供退避账本区分超时与断网，避免包装后所有故障都退化成 NETWORK。
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use { readJson(it) { continuation.isActive } }
                    }
                    continuation.resumeWith(result)
                }
            })
        }
    }

    private fun readJson(response: Response, isActive: () -> Boolean): String {
        if (response.code != 200) throw RemoteChannelPullHttpException(response.code)
        require(response.header("Content-Type")?.substringBefore(';')?.trim()?.equals("application/json", true) == true) {
            "HTTPS 收件响应不是 JSON"
        }
        val body = response.body ?: throw IllegalArgumentException("HTTPS 收件响应为空")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        body.byteStream().use { stream ->
            while (true) {
                if (!isActive()) throw IOException("HTTPS 收件已取消")
                val read = stream.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                require(output.size() <= RemoteChannelSignedEnvelopeCodec.MAX_JSON_BYTES) { "HTTPS 收件响应过大" }
            }
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray())).toString()
        return text
    }
}

/** long: 拉取结果仍须与登记公钥身份一致，然后交给唯一的加密收件入口二次验签与去重。 */
class RemoteChannelPullService(
    private val pairings: RemoteChannelPairingRegistry,
    private val sources: RemoteChannelPullSourceRegistry,
    private val inbox: RemoteChannelInboxGateway,
    private val puller: RemoteChannelHttpsPuller = RemoteChannelHttpsPuller(),
    private val checkpoints: RemoteChannelPullCheckpointRegistry? = null,
    private val receiverIdentity: RemoteChannelReceiverIdentity? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val pullMutex = Mutex()

    suspend fun createReceiver(peer: RemoteChannelPeerCandidate): RemoteChannelReceiverAuthorization = pullMutex.withLock {
        val identity = receiverIdentity ?: error("接收签名器不可用")
        require(pairings.list().any { it.candidate == peer }) { "配对身份已变化" }
        // long: 先落盘认证标记再生成密钥；中断前已有私钥时只能显式导出修复，无钥也须重新确认创建，不能裸请求或自动换钥。
        val source = sources.requireReceiverAuth(peer)
        require(source.receiverAuthorization == null) { "接收身份已登记，请导出原公钥" }
        val auth = identity.create(peer, source, inbox.batchState(source, peer))
        sources.registerReceiver(peer, source, auth)
        auth
    }

    suspend fun exportReceiver(peer: RemoteChannelPeerCandidate): RemoteChannelReceiverAuthorization = pullMutex.withLock {
        val identity = receiverIdentity ?: error("接收签名器不可用")
        val source = sources.sourceFor(peer) ?: error("请先登记 HTTPS 来源")
        require(source.receiverAuthRequired) { "请先显式创建接收身份" }
        require(pairings.list().any { it.candidate == peer }) { "配对身份已变化" }
        val state = inbox.existingBatchState(source, peer) ?: error("原接收队列不可用")
        val auth = identity.export(peer, source, state)
        sources.registerReceiver(peer, source, auth)
        auth
    }

    // long: 前台服务串行消费单条响应，后一个请求必须看到前一个请求已提交的退避，避免并发点击绕过账本。
    suspend fun pull(peer: RemoteChannelPeerCandidate): RemoteChannelReceiveResult = pullMutex.withLock {
        val source = sources.sourceFor(peer) ?: throw IllegalArgumentException("该身份尚未登记 HTTPS 来源")
        require(!source.receiverAuthRequired) { "认证来源请使用同步收件" }
        val sourceIdentity = RemoteChannelPullReliabilityPolicy.sourceIdentity(peer)
        checkpoints?.beforeAttempt(sourceIdentity)
        require(pairings.active(peer.channelId, peer.senderId, peer.keyId) == peer) { "请先启用当前配对身份" }
        // long: 旧单条调用者也不能绕过已启用批次的 cursor/outbox；批次来源统一从 synchronize 恢复。
        val batchState = inbox.existingBatchState(source, peer)
        require(batchState?.cursor == null && batchState?.pendingAck == null) { "此来源已启用批次协议，请同步收件" }
        val envelope = try {
            val envelope = puller.fetch(source.url)
            currentCoroutineContext().ensureActive()
            require(sources.sourceFor(peer) == source) { "HTTPS 来源在拉取期间已变化" }
            if (envelope.channelId != peer.channelId || envelope.senderId != peer.senderId || envelope.keyId != peer.keyId) {
                throw RemoteChannelPullIdentityMismatchException()
            }
            envelope
        } catch (cancelled: CancellationException) {
            // long: 用户离开或取消不等于发送方故障，不能污染跨重启退避或阻止下一次主动收件。
            throw cancelled
        } catch (error: Exception) {
            val code = RemoteChannelPullReliabilityPolicy.classify(error)
            if (code == RemoteChannelPullErrorCode.NETWORK || code == RemoteChannelPullErrorCode.TIMEOUT ||
                code == RemoteChannelPullErrorCode.SERVER_5XX
            ) {
                checkpoints?.recordRetryableFailure(sourceIdentity, error)
            } else {
                checkpoints?.recordNonRetryableFailure(sourceIdentity, code)
            }
            throw error
        }
        // long: 入箱与 checkpoint 属于两份持久存储；账本写失败直接暴露给调用方，不伪造成发送方协议失败再写一次。
        val result = inbox.receive(envelope)
        when (result) {
            // long: 兼容单条接口没有 ACK 协议，成功只清退避并保存最近消息；不能积累永远无法发送的 ACK 队列堵住旧入口。
            is RemoteChannelReceiveResult.Accepted -> checkpoints?.recordAccepted(sourceIdentity, envelope.messageId.trim(), awaitingAck = false)
            is RemoteChannelReceiveResult.Rejected -> if (result.reason == RemoteChannelRejectionReason.DUPLICATE_MESSAGE) {
                // long: 重复 nonce 也会返回 DUPLICATE_MESSAGE，因此不能把这次消息 ID 新增为已安全入箱的 ACK 身份。
                checkpoints?.recordDuplicate(sourceIdentity, envelope.messageId.trim())
            } else {
                checkpoints?.recordNonRetryableFailure(sourceIdentity,
                    if (result.reason == RemoteChannelRejectionReason.DEDUPE_PERSISTENCE_FAILURE) RemoteChannelPullErrorCode.STORAGE
                    else RemoteChannelPullErrorCode.REJECTED_MESSAGE)
            }
        }
        result
    }

    suspend fun synchronize(peer: RemoteChannelPeerCandidate, operationAllowed: () -> Boolean = { true }): RemoteChannelPullSyncResult = pullMutex.withLock {
        val source = sources.sourceFor(peer) ?: throw IllegalArgumentException("该身份尚未登记 HTTPS 来源")
        val identity = RemoteChannelPullReliabilityPolicy.sourceIdentity(peer)
        checkpoints?.beforeAttempt(identity)
        requireCurrentSource(source, peer, operationAllowed)
        val state = inbox.batchState(source, peer)
        try {
            receiverAuthorization(source, peer, state)
            if (state.pendingAck != null) {
                // long: 恢复时先补已持久入箱的 ACK，不拉新批次；请求次数有界，也不会再次展示旧正文。
                acknowledge(source, peer, state, operationAllowed)
                checkpoints?.recordDuplicate(identity, state.pendingAck.messageIds.lastOrNull() ?: state.pendingAck.batchId)
                return@withLock RemoteChannelPullSyncResult.Batch(0, 0, false)
            }
            val signedPull = signedRequest(source, peer, state, ack = false)
            requireCurrentSource(source, peer, operationAllowed)
            when (val response = if (signedPull == null) puller.fetchBatchOrSingle(source.url, state)
                else puller.authenticatedPull(source.url, signedPull)) {
                is RemoteChannelPullResponse.Single -> {
                    require(state.cursor == null) { "已启用批次游标的来源不能退回单条协议" }
                    requireCurrentSource(source, peer, operationAllowed)
                    val envelope = response.envelope
                    if (envelope.channelId != peer.channelId || envelope.senderId != peer.senderId || envelope.keyId != peer.keyId) {
                        throw RemoteChannelPullIdentityMismatchException()
                    }
                    val result = inbox.receive(envelope)
                    when (result) {
                        is RemoteChannelReceiveResult.Accepted -> checkpoints?.recordAccepted(identity, envelope.messageId.trim(), awaitingAck = false)
                        is RemoteChannelReceiveResult.Rejected -> if (result.reason == RemoteChannelRejectionReason.DUPLICATE_MESSAGE) {
                            checkpoints?.recordDuplicate(identity, envelope.messageId.trim())
                        } else checkpoints?.recordNonRetryableFailure(identity, RemoteChannelPullErrorCode.REJECTED_MESSAGE)
                    }
                    RemoteChannelPullSyncResult.Single(result)
                }
                is RemoteChannelPullResponse.Batch -> {
                    requireCurrentSource(source, peer, operationAllowed)
                    val result = inbox.receiveBatch(source, peer, response.batch) {
                        commitAllowed(source, peer, state, operationAllowed)
                    }
                    val committed = inbox.batchState(source, peer)
                    try {
                        acknowledge(source, peer, committed, operationAllowed)
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (error: Exception) {
                        recordFailure(identity, error)
                        // long: 网络 ACK 失败不能把已提交正文伪装成未入箱；outbox 保留，下次主动同步优先补回执。
                        return@withLock RemoteChannelPullSyncResult.Batch(result.acceptedCount, result.duplicateCount, true)
                    }
                    checkpoints?.recordDuplicate(identity, response.batch.messages.lastOrNull()?.messageId?.trim() ?: response.batch.batchId)
                    RemoteChannelPullSyncResult.Batch(result.acceptedCount, result.duplicateCount, false)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            if (checkpoints?.isAvailable() != false) recordFailure(identity, error)
            throw error
        }
    }

    private suspend fun acknowledge(source: RemoteChannelPullSource, peer: RemoteChannelPeerCandidate, state: RemoteChannelBatchState, operationAllowed: () -> Boolean) {
        requireCurrentSource(source, peer, operationAllowed)
        val signedAck = signedRequest(source, peer, state, ack = true)
        requireCurrentSource(source, peer, operationAllowed)
        if (signedAck == null) puller.acknowledge(source.url, peer, state)
        else puller.authenticatedAck(source.url, signedAck, peer, state)
        requireCurrentSource(source, peer, operationAllowed)
        inbox.acknowledgeBatch(source, peer, state.pendingAck ?: error("没有待 ACK 批次")) {
            commitAllowed(source, peer, state, operationAllowed)
        }
    }

    private fun commitAllowed(source: RemoteChannelPullSource, peer: RemoteChannelPeerCandidate, state: RemoteChannelBatchState,
                              operationAllowed: () -> Boolean): Boolean {
        if (!operationAllowed() || sources.sourceFor(peer) != source) return false
        receiverAuthorization(source, peer, state)
        return true
    }

    private suspend fun requireCurrentSource(source: RemoteChannelPullSource, peer: RemoteChannelPeerCandidate, operationAllowed: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        // long: 后台许可可能在响应读取期间被撤销；网络返回、入箱和 ACK 清除前都重新核对，不因排队时有许可而继续。
        if (!operationAllowed()) throw CancellationException("后台收件许可已撤销")
        require(sources.sourceFor(peer) == source) { "HTTPS 来源在同步期间已变化" }
        require(pairings.active(peer.channelId, peer.senderId, peer.keyId) == peer) { "配对身份已停用或变化" }
        if (source.receiverAuthRequired) {
            val state = inbox.existingBatchState(source, peer) ?: error("原接收队列不可用")
            receiverAuthorization(source, peer, state)
        }
    }

    private fun receiverAuthorization(source: RemoteChannelPullSource, peer: RemoteChannelPeerCandidate,
                                      state: RemoteChannelBatchState): RemoteChannelReceiverAuthorization? {
        if (!source.receiverAuthRequired) return null
        val auth = source.receiverAuthorization ?: error("接收公钥尚未完成登记，请显式导出原公钥")
        val identity = receiverIdentity ?: error("接收签名器不可用")
        require(RemoteChannelReceiverAuthProtocol.authorization(peer, source, state, auth.publicKeySpkiBase64) == auth &&
            identity.export(peer, source, state) == auth) { "接收公钥与原登记不匹配" }
        return auth
    }

    private fun signedRequest(source: RemoteChannelPullSource, peer: RemoteChannelPeerCandidate,
                              state: RemoteChannelBatchState, ack: Boolean): String? {
        val auth = receiverAuthorization(source, peer, state) ?: return null
        val request = if (ack) RemoteChannelReceiverAuthProtocol.ackRequest(peer, state) else RemoteChannelReceiverAuthProtocol.pullRequest(peer, state)
        return receiverIdentity!!.sign(auth, request, clock())
    }

    private fun recordFailure(identity: String, error: Exception) {
        val code = RemoteChannelPullReliabilityPolicy.classify(error)
        if (code in setOf(RemoteChannelPullErrorCode.NETWORK, RemoteChannelPullErrorCode.TIMEOUT, RemoteChannelPullErrorCode.SERVER_5XX)) {
            checkpoints?.recordRetryableFailure(identity, error)
        } else checkpoints?.recordNonRetryableFailure(identity, code)
    }
}
