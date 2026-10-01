package com.longdev.xiaoling.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.BufferedSource
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URI
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
)

data class BrowserLinkReference(
    val ref: String,
    val href: String,
)

data class BrowserSession(
    val id: String,
    val snapshotId: String,
    val page: BrowserPage,
)

internal fun BrowserPage.toReadableEvidence(toolCallId: String, snapshotId: String): ToolReadableEvidence {
    val fields = listOf(
        url,
        title.orEmpty(),
        text,
        links.joinToString("\u0000"),
        truncated.toString(),
    )
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
    suspend fun openSession(url: String, maxChars: Int = BrowserUrlPolicy.DEFAULT_MAX_CHARS): BrowserSession
    suspend fun readSession(sessionId: String): BrowserSession
    suspend fun navigateSession(sessionId: String, url: String, maxChars: Int = BrowserUrlPolicy.DEFAULT_MAX_CHARS): BrowserSession
    suspend fun clickLink(sessionId: String, snapshotId: String, ref: String): BrowserSession
    suspend fun closeSession(sessionId: String): Boolean
}

object DisabledBrowserPageReader : BrowserPageReader {
    override suspend fun read(url: String, maxChars: Int): BrowserPage =
        error("浏览器尚未初始化")
    override suspend fun openSession(url: String, maxChars: Int): BrowserSession = error("浏览器尚未初始化")
    override suspend fun readSession(sessionId: String): BrowserSession = error("浏览器尚未初始化")
    override suspend fun navigateSession(sessionId: String, url: String, maxChars: Int): BrowserSession = error("浏览器尚未初始化")
    override suspend fun clickLink(sessionId: String, snapshotId: String, ref: String): BrowserSession = error("浏览器尚未初始化")
    override suspend fun closeSession(sessionId: String): Boolean = error("浏览器尚未初始化")
}

/**
 * long: 浏览器工具先落在“读取网页事实”这条低风险闭环；不把页面脚本、Cookie、表单或登录态暴露给模型。
 */
class OkHttpBrowserPageReader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build(),
) : BrowserPageReader {
    private val sessions = ConcurrentHashMap<String, ManagedBrowserSession>()

    override suspend fun read(url: String, maxChars: Int): BrowserPage = withContext(Dispatchers.IO) {
        val normalizedUrl = BrowserUrlPolicy.validate(url)
        require(maxChars in BrowserUrlPolicy.MIN_MAX_CHARS..BrowserUrlPolicy.MAX_MAX_CHARS) {
            "max_chars 必须在 ${BrowserUrlPolicy.MIN_MAX_CHARS} 到 ${BrowserUrlPolicy.MAX_MAX_CHARS} 之间"
        }
        val request = Request.Builder()
            .url(normalizedUrl)
            .header("Accept", "text/html, text/plain, application/xhtml+xml;q=0.9")
            .header("User-Agent", "XiaoLingBrowser/0.1")
            .get()
            .build()
        val call = client.newCall(request)
        val cancellation = currentCoroutineContext().job.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            call.execute().use { response ->
                currentCoroutineContext().ensureActive()
                require(response.isSuccessful) { "网页请求失败：HTTP ${response.code}" }
                require(response.code !in 300..399) { "网页请求被重定向，出于安全原因未自动跟随" }
                val bodyBytes = response.body?.source()?.let { readLimited(it, BrowserUrlPolicy.MAX_RESPONSE_BYTES) }
                    ?: ByteArray(0)
                require(bodyBytes.size <= BrowserUrlPolicy.MAX_RESPONSE_BYTES) { "网页响应超过大小限制" }
                val body = bodyBytes.toString(Charsets.UTF_8)
                val extracted = HtmlTextExtractor.extract(body, normalizedUrl, maxChars)
                BrowserPage(
                    url = response.request.url.toString(),
                    title = extracted.title,
                    text = extracted.text,
                    links = extracted.links,
                    truncated = extracted.truncated,
                )
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw IOException("网页读取失败：${error.message ?: "网络错误"}", error)
        } finally {
            cancellation.dispose()
        }
    }

    override suspend fun openSession(url: String, maxChars: Int): BrowserSession {
        cleanupExpiredSessions()
        require(sessions.size < BrowserUrlPolicy.MAX_SESSIONS) {
            "浏览器会话数量已达到 ${BrowserUrlPolicy.MAX_SESSIONS} 个上限"
        }
        val session = BrowserSession(
            id = UUID.randomUUID().toString().replace("-", "").take(16),
            snapshotId = newSnapshotId(),
            page = read(url, maxChars),
        )
        val withRefs = session.copy(page = session.page.withLinkRefs(session.snapshotId))
        sessions[withRefs.id] = ManagedBrowserSession(withRefs)
        return withRefs
    }

    override suspend fun readSession(sessionId: String): BrowserSession {
        cleanupExpiredSessions()
        val session = sessions[sessionId] ?: throw IllegalArgumentException("浏览器会话不存在或已关闭")
        session.lastAccessAt = System.currentTimeMillis()
        return session.session
    }

    override suspend fun navigateSession(sessionId: String, url: String, maxChars: Int): BrowserSession {
        cleanupExpiredSessions()
        require(sessions.containsKey(sessionId)) { "浏览器会话不存在或已关闭" }
        val session = BrowserSession(sessionId, newSnapshotId(), read(url, maxChars))
        val withRefs = session.copy(page = session.page.withLinkRefs(session.snapshotId))
        sessions[sessionId] = ManagedBrowserSession(withRefs)
        return withRefs
    }

    override suspend fun clickLink(sessionId: String, snapshotId: String, ref: String): BrowserSession {
        cleanupExpiredSessions()
        val current = sessions[sessionId] ?: throw IllegalArgumentException("浏览器会话不存在或已关闭")
        require(current.session.snapshotId == snapshotId) { "浏览器快照已过期，请先重新读取当前页面" }
        val link = current.session.page.linkRefs.firstOrNull { it.ref == ref }
            ?: throw IllegalArgumentException("浏览器链接引用不存在或已失效")
        // long: click 只消费当前快照已公开的 HTTP(S) 链接，再走同一 URL、私网和重定向校验，不能把模型传入的 ref 变成任意导航地址。
        val targetUrl = BrowserUrlPolicy.validate(link.href)
        val nextSnapshotId = newSnapshotId()
        val loaded = read(targetUrl)
        val next = BrowserSession(sessionId, nextSnapshotId, loaded.withLinkRefs(nextSnapshotId))
        sessions[sessionId] = ManagedBrowserSession(next)
        return next
    }

    override suspend fun closeSession(sessionId: String): Boolean = sessions.remove(sessionId) != null

    private fun cleanupExpiredSessions() {
        val cutoff = System.currentTimeMillis() - BrowserUrlPolicy.SESSION_TTL_MILLIS
        sessions.forEach { (id, session) ->
            if (session.lastAccessAt < cutoff) sessions.remove(id, session)
        }
    }

    // long: 页面导航会替换可读事实，必须生成新快照引用，避免上层把旧页面结果当成当前页面继续使用。
    private fun newSnapshotId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun BrowserPage.withLinkRefs(snapshotId: String): BrowserPage = copy(
        linkRefs = links.mapIndexed { index, href ->
            BrowserLinkReference(
                ref = "link-${snapshotId}-${index.toString(36)}",
                href = href,
            )
        },
    )

    private data class ManagedBrowserSession(
        val session: BrowserSession,
        @Volatile var lastAccessAt: Long = System.currentTimeMillis(),
    )
}

private fun readLimited(source: BufferedSource, maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8_192)
    while (output.size() <= maxBytes) {
        val count = source.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
        if (count < 0) break
        if (count > 0) output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

object BrowserUrlPolicy {
    const val DEFAULT_MAX_CHARS = 12_000
    const val MIN_MAX_CHARS = 500
    const val MAX_MAX_CHARS = 50_000
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    const val MAX_SESSIONS = 4
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
        require(addresses.isNotEmpty()) { "网页域名无法解析" }
        require(addresses.none(::isPrivateAddress)) { "网页地址解析到了本机或私有网络地址" }
        return parsed.toString()
    }

    fun readableSourceRef(raw: String): String {
        val parsed = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("网页来源引用无效")
        val defaultPort = (parsed.scheme == "http" && parsed.port == 80) ||
            (parsed.scheme == "https" && parsed.port == 443)
        val port = if (defaultPort) "" else ":${parsed.port}"
        return "${parsed.scheme}://${parsed.host}$port${parsed.encodedPath.ifBlank { "/" }}"
    }

    private fun isPrivateAddress(address: InetAddress): Boolean {
        return address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.hostAddress == "0.0.0.0" || address.hostAddress == "::"
    }
}

internal data class ExtractedHtml(
    val title: String?,
    val text: String,
    val links: List<String>,
    val truncated: Boolean,
)

internal object HtmlTextExtractor {
    private val scriptOrStyle = Regex("(?is)<(script|style|noscript|template)[^>]*>.*?</\\1>")
    private val comments = Regex("(?s)<!--.*?-->")
    private val titlePattern = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val linkPattern = Regex("(?is)<a[^>]+href\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]")
    private val tags = Regex("(?s)<[^>]+>")
    private val whitespace = Regex("\\s+")

    fun extract(rawHtml: String, baseUrl: String, maxChars: Int): ExtractedHtml {
        val title = titlePattern.find(rawHtml)?.groupValues?.getOrNull(1)?.let(::decodeEntities)?.let(::clean)
        val links = linkPattern.findAll(rawHtml).mapNotNull { match ->
            val href = decodeEntities(match.groupValues[1].trim())
            URI(baseUrl).resolve(href).toString().takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }.distinct().take(100).toList()
        val text = clean(decodeEntities(rawHtml.replace(scriptOrStyle, " ").replace(comments, " ").replace(tags, " ")))
        return ExtractedHtml(
            title = title,
            text = text.take(maxChars),
            links = links,
            truncated = text.length > maxChars,
        )
    }

    private fun clean(value: String): String = whitespace.replace(value, " ").trim()

    private fun decodeEntities(value: String): String = value
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
}
