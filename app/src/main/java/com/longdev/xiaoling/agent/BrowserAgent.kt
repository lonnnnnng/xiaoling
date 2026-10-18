package com.longdev.xiaoling.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.BufferedSource
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class BrowserPage(
    val url: String,
    val title: String?,
    val text: String,
    val links: List<String>,
    val truncated: Boolean,
    val linkRefs: List<BrowserLinkReference> = emptyList(),
    val dom: BrowserDomSnapshot? = null,
)

data class BrowserLinkReference(
    val ref: String,
    val href: String,
)

data class BrowserSession(
    val id: String,
    val snapshotId: String,
    val page: BrowserPage,
    val owner: BrowserSessionOwner? = null,
    val lastAction: BrowserActionReceipt? = null,
)

data class BrowserSessionOwner(val runId: String, val conversationId: String, val userMessageId: String, val processSessionId: String) {
    companion object {
        fun from(context: AgentToolExecutionContext) = BrowserSessionOwner(context.runId, context.conversationId,
            context.userMessageId, context.processSessionId)
    }
}

typealias BrowserActionReceipt = com.longdev.xiaoling.shared.agent.SharedBrowserActionReceipt

internal const val BROWSER_CLICK_TOOL_NAME = "browser.click"

internal fun BrowserPage.toReadableEvidence(toolCallId: String, snapshotId: String): ToolReadableEvidence {
    val fields = listOf(
        url,
        title.orEmpty(),
        text,
        links.joinToString("\u0000"),
        truncated.toString(),
    ) + (dom?.let { snapshot -> listOf(snapshot.schemaVersion.toString(), snapshot.source, snapshot.truncated.toString()) +
        snapshot.nodes.flatMap { node -> listOf(node.nodeId, node.parentId.orEmpty(), node.tag, node.role, node.name, node.text,
            node.href.orEmpty(), node.actions.sortedBy { it.name }.joinToString(), node.draftLength.toString(), node.draftSha256.orEmpty()) }
    } ?: emptyList())
    val canonical = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { output ->
            output.writeInt(fields.size)
            fields.forEach { field ->
                val bytes = field.toByteArray(Charsets.UTF_8)
                output.writeInt(bytes.size)
                output.write(bytes)
            }
        }
        buffer.toByteArray()
    }
    val contentHash = MessageDigest.getInstance("SHA-256").digest(canonical).joinToString("") { byte ->
        "%02x".format(byte)
    }
    return ToolReadableEvidence(
        kind = ToolReadableEvidenceKind.BROWSER_PAGE,
        toolCallId = toolCallId,
        snapshotId = snapshotId,
        contentHash = contentHash,
        sourceRef = BrowserUrlPolicy.readableSourceRef(url),
    )
}

interface BrowserPageReader {
    suspend fun read(url: String, maxChars: Int = BrowserUrlPolicy.DEFAULT_MAX_CHARS): BrowserPage
    suspend fun openSession(url: String, maxChars: Int = BrowserUrlPolicy.DEFAULT_MAX_CHARS, owner: BrowserSessionOwner? = null): BrowserSession
    suspend fun readSession(sessionId: String, owner: BrowserSessionOwner? = null): BrowserSession
    suspend fun navigateSession(sessionId: String, url: String, maxChars: Int = BrowserUrlPolicy.DEFAULT_MAX_CHARS, owner: BrowserSessionOwner? = null): BrowserSession
    suspend fun clickLink(sessionId: String, snapshotId: String, ref: String, owner: BrowserSessionOwner? = null, toolCallId: String = ""): BrowserSession
    suspend fun typeDraft(sessionId: String, snapshotId: String, ref: String, text: String, owner: BrowserSessionOwner? = null, toolCallId: String = ""): BrowserSession = error("浏览器草稿输入尚未初始化")
    suspend fun closeSession(sessionId: String, owner: BrowserSessionOwner? = null): Boolean
    /** long: Run 切换/结束撤销全部内存会话及在途请求，持久证据不能当成可继续执行的引用。 */
    fun clearSessions() = Unit
}

object DisabledBrowserPageReader : BrowserPageReader {
    override suspend fun read(url: String, maxChars: Int): BrowserPage = error("浏览器尚未初始化")
    override suspend fun openSession(url: String, maxChars: Int, owner: BrowserSessionOwner?): BrowserSession = error("浏览器尚未初始化")
    override suspend fun readSession(sessionId: String, owner: BrowserSessionOwner?): BrowserSession = error("浏览器尚未初始化")
    override suspend fun navigateSession(sessionId: String, url: String, maxChars: Int, owner: BrowserSessionOwner?): BrowserSession = error("浏览器尚未初始化")
    override suspend fun clickLink(sessionId: String, snapshotId: String, ref: String, owner: BrowserSessionOwner?, toolCallId: String): BrowserSession = error("浏览器尚未初始化")
    override suspend fun closeSession(sessionId: String, owner: BrowserSessionOwner?): Boolean = error("浏览器尚未初始化")
}

/** long: 服务端 HTML 语义快照与本机表单草稿共用受控引用；没有 WebView、脚本执行、Cookie 或表单提交。 */
class OkHttpBrowserPageReader(client: OkHttpClient = OkHttpClient.Builder().build(),
    connectionDns: okhttp3.Dns = BrowserPublicDns(),
) : BrowserPageReader {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).cache(null)
        .dns(connectionDns)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    private val sessions = ConcurrentHashMap<String, ManagedBrowserSession>()
    private val calls = ConcurrentHashMap<Call, ManagedBrowserSession?>()
    private val gate = Any()
    private val consumedCalls = mutableSetOf<String>()
    private var generation = 0L
    private val localOwner = BrowserSessionOwner("local-${UUID.randomUUID()}", "", "", "")

    override suspend fun read(url: String, maxChars: Int): BrowserPage = load(url, maxChars, null)

    private suspend fun load(url: String, maxChars: Int, control: ManagedBrowserSession?): BrowserPage {
        val epoch = synchronized(gate) { generation }
        val normalized = withContext(Dispatchers.IO) { BrowserUrlPolicy.validate(url) }
        require(maxChars in BrowserUrlPolicy.MIN_MAX_CHARS..BrowserUrlPolicy.MAX_MAX_CHARS) { "max_chars 超出限制" }
        val request = Request.Builder().url(normalized).header("Accept", "text/html, text/plain, application/xhtml+xml;q=0.9")
            .header("User-Agent", "XiaoLingBrowser/0.1").get().build()
        val call = client.newCall(request)
        synchronized(gate) {
            require(generation == epoch) { "浏览器 Run 已切换" }
            require(control == null || sessions[control.id] === control) { "浏览器会话已撤销" }
            calls[call] = control ?: STANDALONE
            control?.call = call
        }
        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                // long: 取消覆盖响应头和正文，不能只在阻塞 execute 返回后取消，否则停滞页面会占住已退出的 Run。
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(e)) }
                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching { response.use {
                            require(response.code == 200) { "网页请求失败：HTTP ${response.code}（不跟随重定向）" }
                            val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                            require(contentType == null || contentType in setOf("text/html", "text/plain", "application/xhtml+xml")) { "浏览器不接受下载或未知响应类型" }
                            require(response.header("Content-Disposition") == null) { "浏览器不接受下载响应" }
                            val bytes = response.body?.source()?.let { source -> readLimited(source, BrowserUrlPolicy.MAX_RESPONSE_BYTES) { continuation.isActive } } ?: ByteArray(0)
                            val extracted = HtmlTextExtractor.extract(bytes.toString(Charsets.UTF_8), normalized, maxChars)
                            synchronized(gate) {
                                require(generation == epoch && (control == null || sessions[control.id] === control)) { "浏览器会话已撤销" }
                            }
                            BrowserPage(normalized, extracted.title, extracted.text, extracted.links, extracted.truncated, dom = extracted.dom)
                        } }
                        continuation.resumeWith(result)
                    }
                })
            }
        } finally {
            calls.remove(call)
            synchronized(gate) { if (control?.call === call) control.call = null }
        }
    }

    override suspend fun openSession(url: String, maxChars: Int, owner: BrowserSessionOwner?): BrowserSession {
        val identity = owner ?: localOwner
        require(identity.runId.isNotBlank() && listOf(identity.runId, identity.conversationId, identity.userMessageId, identity.processSessionId)
            .all { it.length <= 256 && !it.contains('\u0000') }) { "浏览器 owner 无效" }
        val control = synchronized(gate) {
            cleanupExpired()
            require(sessions.size < BrowserUrlPolicy.MAX_SESSIONS) { "浏览器会话数量已达到 4 个上限" }
            ManagedBrowserSession(id(), identity).also { sessions[it.id] = it }
        }
        try {
            val page = load(url, maxChars, control)
            currentCoroutineContext().ensureActive()
            return synchronized(gate) {
                require(sessions[control.id] === control) { "浏览器会话已撤销" }
                snapshot(control, page).also { control.session = it }
            }
        } catch (error: Throwable) {
            synchronized(gate) { if (sessions[control.id] === control) sessions.remove(control.id) }
            control.call?.cancel()
            throw error
        }
    }

    override suspend fun readSession(sessionId: String, owner: BrowserSessionOwner?): BrowserSession = synchronized(gate) {
        val control = owned(sessionId, owner)
        control.lastAccessAt = System.currentTimeMillis()
        requireNotNull(control.session) { "浏览器页面尚未就绪" }
    }

    override suspend fun navigateSession(sessionId: String, url: String, maxChars: Int, owner: BrowserSessionOwner?): BrowserSession {
        val control = synchronized(gate) {
            val current = owned(sessionId, owner)
            require(!current.busy) { "浏览器已有动作执行中" }
            current.busy = true
            current
        }
        try {
            val page = load(url, maxChars, control)
            currentCoroutineContext().ensureActive()
            return synchronized(gate) {
                require(sessions[sessionId] === control) { "浏览器会话已撤销" }
                control.drafts.clear()
                snapshot(control, page).also { control.session = it }
            }
        } finally { synchronized(gate) { control.busy = false } }
    }

    override suspend fun clickLink(sessionId: String, snapshotId: String, ref: String, owner: BrowserSessionOwner?, toolCallId: String): BrowserSession {
        val control = synchronized(gate) { owned(sessionId, owner) }
        val url = synchronized(gate) {
            val current = requireNotNull(control.session)
            require(current.snapshotId == snapshotId) { "浏览器快照已过期" }
            current.page.linkRefs.firstOrNull { it.ref == ref }?.href ?: throw IllegalArgumentException("浏览器链接引用不存在")
        }
        // long: URL 校验失败不会发起请求；通过后在网络前原子消费 ref/callId，失败也不能复用同一份审批重复请求。
        withContext(Dispatchers.IO) { BrowserUrlPolicy.validate(url) }
        synchronized(gate) { claim(control, snapshotId, ref, toolCallId, BrowserDomAction.NAVIGATE_LINK) }
        try {
            val page = load(url, BrowserUrlPolicy.DEFAULT_MAX_CHARS, control)
            currentCoroutineContext().ensureActive()
            return synchronized(gate) {
                require(sessions[sessionId] === control) { "浏览器会话已撤销" }
                control.drafts.clear()
                actionSnapshot(control, page, snapshotId, ref, toolCallId, BrowserDomAction.NAVIGATE_LINK).also { control.session = it }
            }
        } finally { synchronized(gate) { control.busy = false } }
    }

    override suspend fun typeDraft(sessionId: String, snapshotId: String, ref: String, text: String, owner: BrowserSessionOwner?, toolCallId: String): BrowserSession {
        require(text.length in 1..BrowserUrlPolicy.MAX_DRAFT_CHARS && text.none { it.isISOControl() }) { "草稿文本为空、过长或包含控制字符" }
        currentCoroutineContext().ensureActive()
        return synchronized(gate) {
            val control = owned(sessionId, owner)
            val node = claim(control, snapshotId, ref, toolCallId, BrowserDomAction.TYPE_DRAFT)
            try {
                val current = requireNotNull(control.session)
                // long: 原文只进入本会话内存草稿，不写 HTTP、Cookie、Room 或页面正文；回读摘要只证明本机草稿更新。
                control.drafts[node.nodeId] = text
                val dom = requireNotNull(current.page.dom).copy(nodes = current.page.dom.nodes.map {
                    if (it.nodeId == node.nodeId) it.copy(draftLength = text.length, draftSha256 = BrowserTypeTextAuditPolicy.fingerprint(text)) else it
                })
                actionSnapshot(control, current.page.copy(dom = dom), snapshotId, ref, toolCallId, BrowserDomAction.TYPE_DRAFT)
                    .also { control.session = it }
            } finally { control.busy = false }
        }
    }

    private fun claim(control: ManagedBrowserSession, snapshotId: String, ref: String, callId: String, action: BrowserDomAction): BrowserDomNode {
        require(sessions[control.id] === control && !control.busy) { "浏览器会话已撤销或已有动作执行中" }
        val current = requireNotNull(control.session)
        require(current.snapshotId == snapshotId) { "浏览器快照已过期" }
        val node = current.page.dom?.nodes?.firstOrNull { it.ref == ref && action in it.actions }
            ?: throw IllegalArgumentException("浏览器动作引用不存在或不可操作")
        require(control.consumed.size < BrowserUrlPolicy.MAX_ACTIONS_PER_SESSION) { "浏览器会话动作容量已满" }
        require("$snapshotId:$ref" !in control.consumed && (callId.isBlank() || callId !in consumedCalls)) { "浏览器动作已经消费，不能重放" }
        require(consumedCalls.size < BrowserUrlPolicy.MAX_SESSIONS * BrowserUrlPolicy.MAX_ACTIONS_PER_SESSION) { "当前浏览器 Run 动作容量已满" }
        require(callId.length <= 256)
        control.consumed += "$snapshotId:$ref"
        if (callId.isNotBlank()) consumedCalls += callId
        control.busy = true
        return node
    }

    private fun actionSnapshot(control: ManagedBrowserSession, page: BrowserPage, before: String, ref: String, callId: String, action: BrowserDomAction): BrowserSession {
        val beforeHash = requireNotNull(control.session).page.toReadableEvidence(callId.ifBlank { "local-action" }, before).contentHash
        val next = snapshot(control, page)
        return next.copy(lastAction = BrowserActionReceipt(id() + id(), callId, action, before, next.snapshotId, ref, control.id, beforeHash))
    }
    private fun snapshot(control: ManagedBrowserSession, page: BrowserPage): BrowserSession {
        val snapshotId = id()
        val links = page.links.mapIndexed { index, href -> BrowserLinkReference("link-$snapshotId-${index.toString(36)}", href) }
        val dom = page.dom?.copy(nodes = page.dom.nodes.map { node ->
            node.copy(ref = if (BrowserDomAction.NAVIGATE_LINK in node.actions) links.firstOrNull { it.href == node.href }?.ref.orEmpty()
                else if (BrowserDomAction.TYPE_DRAFT in node.actions) "input-$snapshotId-${node.nodeId}" else "")
        })
        control.lastAccessAt = System.currentTimeMillis()
        return BrowserSession(control.id, snapshotId, page.copy(linkRefs = links, dom = dom), control.owner)
    }
    override suspend fun closeSession(sessionId: String, owner: BrowserSessionOwner?): Boolean = synchronized(gate) {
        val control = sessions[sessionId] ?: return@synchronized false
        require(control.owner == (owner ?: localOwner)) { "浏览器会话不属于当前 Run" }
        remove(control); true
    }
    override fun clearSessions() = synchronized(gate) {
        generation++
        consumedCalls.clear()
        sessions.values.toList().forEach(::remove)
        calls.keys.forEach { it.cancel() }
    }
    private fun owned(id: String, owner: BrowserSessionOwner?): ManagedBrowserSession {
        cleanupExpired()
        val control = sessions[id] ?: throw IllegalArgumentException("浏览器会话不存在或已关闭")
        require(control.owner == (owner ?: localOwner)) { "浏览器会话不属于当前 Run" }
        return control
    }
    private fun cleanupExpired() {
        val cutoff = System.currentTimeMillis() - BrowserUrlPolicy.SESSION_TTL_MILLIS
        sessions.values.filter { it.lastAccessAt < cutoff }.forEach(::remove)
    }
    private fun remove(control: ManagedBrowserSession) {
        sessions.remove(control.id); control.call?.cancel(); control.drafts.clear()
    }
    private fun id() = UUID.randomUUID().toString().replace("-", "").take(16)
    private class ManagedBrowserSession(val id: String, val owner: BrowserSessionOwner) {
        var session: BrowserSession? = null
        var busy = false
        var lastAccessAt = System.currentTimeMillis()
        var call: Call? = null
        val consumed = mutableSetOf<String>()
        val drafts = mutableMapOf<String, String>()
    }
    companion object { private val STANDALONE = ManagedBrowserSession("standalone", BrowserSessionOwner("standalone", "", "", "")) }
}

private fun readLimited(source: BufferedSource, maxBytes: Int, active: () -> Boolean): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (output.size() <= maxBytes) {
        if (!active()) throw IOException("网页读取已取消")
        val count = source.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
        if (count < 0) break
        if (count > 0) output.write(buffer, 0, count)
    }
    require(output.size() <= maxBytes) { "网页响应超过大小限制" }
    return output.toByteArray()
}

object BrowserUrlPolicy {
    const val DEFAULT_MAX_CHARS = 12_000
    const val MIN_MAX_CHARS = 500
    const val MAX_MAX_CHARS = 50_000
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    const val MAX_SESSIONS = 4
    const val MAX_DOM_NODES = 128
    const val MAX_LINKS = 100
    const val MAX_DOM_SCAN = 10_000
    const val MAX_DOM_DEPTH = 32
    const val MAX_DRAFT_CHARS = 500
    const val MAX_ACTIONS_PER_SESSION = 32
    const val SESSION_TTL_MILLIS = 10 * 60 * 1_000L

    fun validate(raw: String): String {
        val value = raw.trim()
        require(value.length in 8..2_048) { "网页地址长度无效" }
        val parsed = value.toHttpUrlOrNull() ?: throw IllegalArgumentException("网页地址必须是有效的 HTTP(S) URL")
        require(parsed.scheme == "http" || parsed.scheme == "https") { "网页地址只支持 HTTP 或 HTTPS" }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "网页地址不能包含账号或密码" }
        val host = parsed.host.lowercase()
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) {
            "为避免访问本机服务，网页地址不支持 localhost 或 .local 域名"
        }
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrElse { emptyList() }
        publicAddresses(addresses)
        return parsed.toString()
    }

    fun readableSourceRef(raw: String): String {
        val parsed = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("网页来源引用无效")
        val defaultPort = (parsed.scheme == "http" && parsed.port == 80) ||
            (parsed.scheme == "https" && parsed.port == 443)
        val port = if (defaultPort) "" else ":${parsed.port}"
        return "${parsed.scheme}://${parsed.host}$port${parsed.encodedPath.ifBlank { "/" }}"
    }

    fun publicAddresses(addresses: List<InetAddress>): List<InetAddress> {
        // long: 复用已验证的公网/保留地址策略，并在实际 OkHttp DNS 建连时再次检查，避免预检后 DNS 替换地址。
        return RemoteChannelPullPolicy.publicAddresses(addresses)
    }

}

/** long: 建连 DNS 失败必须转成网络异常；不能把策略拒绝作为未捕获 RuntimeException 抛到 OkHttp 回调线程。 */
internal class BrowserPublicDns(private val delegate: okhttp3.Dns = okhttp3.Dns.SYSTEM) : okhttp3.Dns {
    override fun lookup(hostname: String): List<InetAddress> = try {
        BrowserUrlPolicy.publicAddresses(delegate.lookup(hostname))
    } catch (error: IllegalArgumentException) {
        throw java.net.UnknownHostException("网页地址解析到本机、私有或保留网络").apply { initCause(error) }
    }
}

internal data class ExtractedHtml(val title: String?, val text: String, val links: List<String>, val truncated: Boolean,
    val dom: BrowserDomSnapshot? = null)

internal object HtmlTextExtractor {
    fun extract(rawHtml: String, baseUrl: String, maxChars: Int): ExtractedHtml = BrowserDomExtractor.extract(rawHtml, baseUrl, maxChars)
}
