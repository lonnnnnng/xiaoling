package com.longdev.xiaoling.agent

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
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
        val root = JSONObject().put("schemaVersion", 1).put("sources", JSONArray().apply {
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
                put(JSONObject().put("channelId", source.channelId).put("senderId", source.senderId)
                    .put("keyId", source.keyId).put("fingerprintSha256", source.fingerprintSha256)
                    .put("url", source.url))
            }
        })
        return root.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) { "收件来源状态过大" } }
    }

    fun decode(raw: String): List<RemoteChannelPullSource> {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_STATE_BYTES) { "收件来源状态过大" }
        val root = JSONObject(raw)
        require(root.keys().asSequence().toSet() == setOf("schemaVersion", "sources") && root.opt("schemaVersion") == 1) {
            "收件来源状态版本或字段无效"
        }
        val array = root.getJSONArray("sources")
        require(array.length() <= MAX_SOURCES) { "收件来源过多" }
        val sources = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            require(item.keys().asSequence().toSet() == setOf("channelId", "senderId", "keyId", "fingerprintSha256", "url")) {
                "收件来源字段无效"
            }
            fun field(name: String, max: Int): String = (item.opt(name) as? String)?.takeIf { it.length in 1..max }
                ?: throw IllegalArgumentException("收件来源 $name 无效")
            RemoteChannelPullSource(field("channelId", 200), field("senderId", 200), field("keyId", 64),
                field("fingerprintSha256", 64), field("url", MAX_URL_CHARS))
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
    @Synchronized fun list(): List<RemoteChannelPullSource> =
        store.loadState()?.let(RemoteChannelPullPolicy::decode).orEmpty()

    @Synchronized fun sourceFor(peer: RemoteChannelPeerCandidate): RemoteChannelPullSource? =
        list().firstOrNull { it.channelId == peer.channelId && it.senderId == peer.senderId &&
            it.keyId == peer.keyId && it.fingerprintSha256 == peer.fingerprintSha256 }

    @Synchronized fun save(peer: RemoteChannelPeerCandidate, rawUrl: String): RemoteChannelPullSource {
        require(pairings.list().any { it.candidate == peer }) { "配对身份已变化，请重新核对公钥" }
        val url = RemoteChannelPullPolicy.endpoint(rawUrl).toString()
        val next = RemoteChannelPullSource(peer.channelId, peer.senderId, peer.keyId, peer.fingerprintSha256, url)
        val current = list().filterNot { it.channelId == peer.channelId && it.senderId == peer.senderId && it.keyId == peer.keyId }
        store.saveState(RemoteChannelPullPolicy.encode(current + next))
        return next
    }

    @Synchronized fun remove(peer: RemoteChannelPeerCandidate): Boolean {
        val current = list()
        val next = current.filterNot { it.channelId == peer.channelId && it.senderId == peer.senderId && it.keyId == peer.keyId }
        if (next.size == current.size) return false
        store.saveState(RemoteChannelPullPolicy.encode(next))
        return true
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
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            // long: 取消覆盖响应头和正文读取整个阶段；停滞正文不能占住已退出的前台拉取任务。
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(IOException("HTTPS 收件失败：${e.message ?: "网络错误"}", e)))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use { readEnvelope(it) { continuation.isActive } }
                    }
                    continuation.resumeWith(result)
                }
            })
        }
    }

    private fun readEnvelope(response: Response, isActive: () -> Boolean): RemoteChannelEnvelope {
        require(response.code == 200) { "HTTPS 收件返回 HTTP ${response.code}" }
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
        return RemoteChannelSignedEnvelopeCodec.decode(text)
    }
}

/** long: 拉取结果仍须与登记公钥身份一致，然后交给唯一的加密收件入口二次验签与去重。 */
class RemoteChannelPullService(
    private val pairings: RemoteChannelPairingRegistry,
    private val sources: RemoteChannelPullSourceRegistry,
    private val inbox: RemoteChannelInboxGateway,
    private val puller: RemoteChannelHttpsPuller = RemoteChannelHttpsPuller(),
) {
    suspend fun pull(peer: RemoteChannelPeerCandidate): RemoteChannelReceiveResult {
        val source = sources.sourceFor(peer) ?: throw IllegalArgumentException("该身份尚未登记 HTTPS 来源")
        require(pairings.active(peer.channelId, peer.senderId, peer.keyId) == peer) { "请先启用当前配对身份" }
        val envelope = puller.fetch(source.url)
        require(sources.sourceFor(peer) == source) { "HTTPS 来源在拉取期间已变化" }
        require(envelope.channelId == peer.channelId && envelope.senderId == peer.senderId && envelope.keyId == peer.keyId) {
            "HTTPS 来源返回了其他身份的签名消息"
        }
        return inbox.receive(envelope)
    }
}
