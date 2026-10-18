@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.longdev.xiaoling.shared.browser

import com.longdev.xiaoling.shared.browser.nativehtml.*
import com.longdev.xiaoling.shared.http.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.Foundation.NSLock
import platform.Foundation.NSUUID

/** long: 固定页面能力由宿主选择，原生HTTP实例只归当前reader持有；关闭不能由旧快照或模型工具恢复。 */
class IosReadOnlyBrowser internal constructor(
    private val registry: SharedBrowserPageRegistry,
    private val client: SharedHttpClient,
) : SharedReadOnlyBrowser {
    constructor(pages: Map<String, String>) : this(SharedBrowserPageRegistry.https(pages))
    private constructor(registry: SharedBrowserPageRegistry) : this(registry,
        IosHttpClient(SharedHttpTargetPolicy.https(registry.pageIds.map(registry::urlFor).toSet())))
    private val lock = NSLock()
    private var closed = false
    override val pageIds: List<String> get() = locked { checkOpen(); registry.pageIds }

    override suspend fun readPage(pageId: String, maxChars: Int): SharedBrowserPage = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        require(SharedBrowserContract.validPageId(pageId) && maxChars in 1..SharedBrowserContract.MAX_CHARS)
        val url = locked { checkOpen(); registry.urlFor(pageId) }
        val response = try {
            client.execute(SharedHttpRequest(url, headers = mapOf("Accept" to "text/html, text/plain", "User-Agent" to "XiaoLing-ReadOnly/1"),
                maxResponseBytes = SharedBrowserContract.MAX_BODY_BYTES))
        } catch (_: SharedHttpException) {
            locked { checkOpen() }
            throw SharedBrowserException(SharedBrowserFailure.HTTP_REJECTED)
        }
        currentCoroutineContext().ensureActive()
        locked { checkOpen() }
        val headers = response.headers.mapKeys { it.key.lowercase() }
        val parts = headers["content-type"]?.split(';')?.map(String::trim) ?: emptyList()
        val type = parts.firstOrNull()?.lowercase()
        val charset = parts.drop(1)
        if (response.statusCode != 200 || "content-disposition" in headers || type !in setOf("text/html", "text/plain") ||
            charset.size > 1 || charset.any { !it.matches(Regex("charset\\s*=\\s*(?:utf-8|us-ascii|\"utf-8\"|\"us-ascii\")", RegexOption.IGNORE_CASE)) })
            throw SharedBrowserException(SharedBrowserFailure.CONTENT_REJECTED)
        val bytes = response.body
        if (bytes.size > SharedBrowserContract.MAX_BODY_BYTES) throw SharedBrowserException(SharedBrowserFailure.CONTENT_REJECTED)
        val input = try { bytes.decodeToString(throwOnInvalidSequence = true) }
            catch (_: CharacterCodingException) { throw SharedBrowserException(SharedBrowserFailure.INVALID_TEXT) }
        if (input.any { it.code in 0..8 || it.code in 11..12 || it.code in 14..31 } ||
            (charset.any { it.contains("us-ascii", ignoreCase = true) } && bytes.any { it < 0 }))
            throw SharedBrowserException(SharedBrowserFailure.INVALID_TEXT)
        val parsed = if (type == "text/plain") TextSink(maxChars).apply { append(input) }.let { Parsed("", it.value(), it.truncated) }
            else parseHtml(bytes, maxChars)
        currentCoroutineContext().ensureActive()
        val content = SharedBrowserPage(pageId, parsed.title, parsed.text, parsed.truncated, "pending", "0".repeat(64), url).content()
        val digest = sha256(content.encodeToByteArray())
        // long: 完成领取与close互斥；close先撤销则连已经解析的正文也不能成为成功快照。
        locked {
            checkOpen()
            SharedBrowserPage(pageId, parsed.title, parsed.text, parsed.truncated,
                NSUUID.UUID().UUIDString.replace("-", "").lowercase(), digest, url)
        }
    }

    override fun close() {
        val first = locked { if (closed) false else { closed = true; true } }
        if (first) client.close()
    }
    private fun checkOpen() { if (closed) throw SharedBrowserException(SharedBrowserFailure.READER_CLOSED) }
    private inline fun <T> locked(block: () -> T): T { lock.lock(); try { return block() } finally { lock.unlock() } }
}

private data class Parsed(val title: String, val text: String, val truncated: Boolean)
private val parserInitLock = NSLock()
private var parserInitialized = false

private suspend fun parseHtml(bytes: ByteArray, maxChars: Int): Parsed {
    if (bytes.isEmpty()) return Parsed("", "", false)
    parserInitLock.lock()
    try { if (!parserInitialized) { xmlInitParser(); parserInitialized = true } } finally { parserInitLock.unlock() }
    val document = bytes.usePinned { xl_html(it.addressOf(0), bytes.size) }
        ?: throw SharedBrowserException(SharedBrowserFailure.PARSE_FAILED)
    try {
        val text = TextSink(maxChars)
        val title = TextSink(512)
        var scanned = 0
        suspend fun visit(first: xmlNodePtr?, depth: Int, inTitle: Boolean = false, inHead: Boolean = false) {
            var node = first
            while (node != null) {
                currentCoroutineContext().ensureActive()
                if (++scanned > 10_000 || depth > 32) throw SharedBrowserException(SharedBrowserFailure.PARSE_LIMIT)
                val current = node
                if (xl_type(current) == 3) {
                    val value = xl_text(current)?.toKString().orEmpty()
                    if (inTitle) title.append(value) else if (!inHead) text.append(value)
                } else if (xl_type(current) == 1) {
                    val tag = xl_name(current)?.toKString().orEmpty().lowercase()
                    // long: 隐藏后代、脚本和输入原值必须在聚合正文之前排除，避免先收集再脱敏遗漏子树。
                    val excluded = tag in setOf("script", "style", "noscript", "template", "iframe", "object", "embed", "base", "input", "textarea", "select", "svg", "math") || hidden(current)
                    if (!excluded) {
                        val isTitle = inTitle || tag == "title"
                        val isHead = inHead || tag == "head"
                        val sink = if (isTitle) title else text
                        val block = tag in setOf("p", "div", "section", "article", "br", "li", "ul", "ol", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "td", "header", "footer")
                        if (block) sink.append(" ")
                        visit(current.pointed.children, depth + 1, isTitle, isHead)
                        if (block) sink.append(" ")
                    }
                }
                node = current.pointed.next
            }
        }
        visit(xmlDocGetRootElement(document), 0)
        return Parsed(title.value(), text.value(), text.truncated || title.truncated)
    } finally { xmlFreeDoc(document) }
}

private fun hidden(node: xmlNodePtr): Boolean {
    fun property(name: String): String? {
        val value = xl_prop(node, name) ?: return null
        try { return value.toKString() } finally { xl_free(value) }
    }
    return property("hidden") != null || property("inert") != null || property("aria-hidden")?.lowercase() == "true" ||
        Regex("(?:^|;)\\s*(?:display\\s*:\\s*none|visibility\\s*:\\s*(?:hidden|collapse))\\s*(?:!important\\s*)?(?:;|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(property("style").orEmpty())
}

private class TextSink(private val max: Int) {
    private val buffer = StringBuilder()
    private var space = false
    var truncated = false; private set
    fun append(value: String) {
        for (char in value) {
            if (char.isWhitespace() || char == '\u00a0') { space = buffer.isNotEmpty(); continue }
            if (space) { add(' '); space = false }
            add(char)
        }
    }
    private fun add(char: Char) { if (buffer.length < max) buffer.append(char) else truncated = true }
    // long: UTF-16预览不能留下孤立高代理，避免同一快照被编码器和模型解释成不同字符。
    fun value(): String = buffer.toString().let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }.trimEnd()
}

internal fun sha256(bytes: ByteArray): String {
    val hash = ByteArray(32)
    bytes.usePinned { input -> hash.usePinned { output -> xl_sha256(if (bytes.isEmpty()) null else input.addressOf(0), bytes.size.toUInt(), output.addressOf(0)) } }
    return hash.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
