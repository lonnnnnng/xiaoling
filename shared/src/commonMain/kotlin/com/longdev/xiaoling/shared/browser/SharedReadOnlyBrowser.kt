package com.longdev.xiaoling.shared.browser

import com.longdev.xiaoling.shared.agent.*
import com.longdev.xiaoling.shared.http.SharedHttpContract
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

object SharedBrowserContract {
    const val VERSION = 1
    const val MAX_BODY_BYTES = 512 * 1024
    const val DEFAULT_MAX_CHARS = 12_000
    const val MAX_CHARS = 50_000
    fun validPageId(id: String) = id.matches(Regex("[a-z][a-z0-9_-]{0,31}"))
}

/** long: 模型只能选择宿主预先登记的完整页面，不能把HTTP origin许可扩展成任意路径或链接访问。 */
class SharedBrowserPageRegistry private constructor(pages: Map<String, String>) {
    private val urls = pages.toMap()
    val pageIds: List<String> get() = urls.keys.sorted()
    internal fun urlFor(id: String): String = urls[id] ?: throw SharedBrowserException(SharedBrowserFailure.PAGE_NOT_REGISTERED)
    override fun toString() = "SharedBrowserPageRegistry(pageCount=${urls.size})"
    companion object {
        fun https(pages: Map<String, String>): SharedBrowserPageRegistry {
            validate(pages)
            for (url in pages.values) {
                val origin = SharedHttpContract.origin(url)
                require(origin.startsWith("https://") && origin.endsWith(":443"))
                val host = origin.removePrefix("https://").removeSuffix(":443")
                // long: 这里只筛除明显本机/数字地址，不能声称已验证DNS或实际建连IP；固定目标仍须宿主信任。
                require('.' in host && host.substringAfterLast('.').all { it in 'a'..'z' })
                require(host != "localhost" && listOf(".localhost", ".local", ".internal", ".test", ".invalid").none(host::endsWith))
            }
            return SharedBrowserPageRegistry(pages)
        }
        internal fun loopbackTest(pages: Map<String, String>): SharedBrowserPageRegistry {
            validate(pages)
            require(pages.values.all { SharedHttpContract.origin(it).startsWith("http://127.0.0.1:") })
            return SharedBrowserPageRegistry(pages)
        }
        private fun validate(pages: Map<String, String>) {
            require(pages.size in 1..8 && pages.keys.all(SharedBrowserContract::validPageId))
            require(pages.values.all { it.length in 1..512 && '?' !in it && '#' !in it && '@' !in it })
            pages.values.forEach(SharedHttpContract::origin)
        }
    }
}

enum class SharedBrowserFailure { PAGE_NOT_REGISTERED, READER_CLOSED, HTTP_REJECTED, CONTENT_REJECTED, INVALID_TEXT, PARSE_LIMIT, PARSE_FAILED }
class SharedBrowserException(val failure: SharedBrowserFailure) : IllegalStateException("页面读取失败：${failure.name}")

/** long: 快照只记录静态可读正文；没有可执行ref、表单值或提交回执，页面文本始终是不可信外部内容。 */
class SharedBrowserPage(
    val pageId: String, val title: String, val text: String, val truncated: Boolean,
    val snapshotId: String, val contentHash: String, val sourceRef: String,
) {
    init {
        require(SharedBrowserContract.validPageId(pageId) && title.length <= 512 && text.length <= SharedBrowserContract.MAX_CHARS)
        SharedToolReadableEvidence(SharedToolReadableEvidenceKind.BROWSER_PAGE, "validation", snapshotId, contentHash, sourceRef)
    }
    fun content(): String = buildJsonObject {
        put("page_id", pageId); put("mode", "STATIC_TEXT"); put("title", title); put("text", text); put("truncated", truncated)
    }.toString()
    override fun toString() = "SharedBrowserPage(textChars=${text.length}, truncated=$truncated)"
}

interface SharedReadOnlyBrowser {
    val pageIds: List<String>
    suspend fun readPage(pageId: String, maxChars: Int = SharedBrowserContract.DEFAULT_MAX_CHARS): SharedBrowserPage
    fun close()
}

/** long: 宿主须绑定真实Run/前台owner；只读目录不是后台许可，撤销后不能发布迟到的外部正文。 */
class SharedReadOnlyBrowserExecutor(
    private val browser: SharedReadOnlyBrowser,
    private val isForegroundOwnerActive: () -> Boolean,
) : SharedToolExecutor {
    val catalog = SharedToolCatalog.from(listOf(
        SharedToolDefinition("browser.list_pages", "列出宿主登记的只读页面ID", false, false),
        SharedToolDefinition("browser.read_page", "按page_id读取登记页面静态正文；无链接或表单动作", false, false),
    ))
    override suspend fun execute(toolCall: SharedToolCall): SharedToolExecutionResult = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        if (!isForegroundOwnerActive()) return@withContext failure("FOREGROUND_OWNER_REQUIRED")
        try {
            require(toolCall.id.length in 1..256 && toolCall.id.isNotBlank())
            var evidence: SharedToolReadableEvidence? = null
            val content = when (toolCall.name) {
                "browser.list_pages" -> {
                    require(toolCall.arguments.isEmpty())
                    currentCoroutineContext().ensureActive()
                    buildJsonObject { put("page_ids", JsonArray(browser.pageIds.map(::JsonPrimitive))) }.toString()
                }
                "browser.read_page" -> {
                    require(toolCall.arguments.keys.all { it in setOf("page_id", "max_chars") })
                    val id = requireNotNull(toolCall.arguments["page_id"])
                    require(SharedBrowserContract.validPageId(id))
                    val max = toolCall.arguments["max_chars"]?.let {
                        require(it.matches(Regex("[1-9][0-9]*"))); it.toInt()
                    } ?: SharedBrowserContract.DEFAULT_MAX_CHARS
                    require(max in 1..SharedBrowserContract.MAX_CHARS)
                    currentCoroutineContext().ensureActive()
                    val page = browser.readPage(id, max)
                    require(page.pageId == id && page.text.length <= max)
                    evidence = SharedToolReadableEvidence(SharedToolReadableEvidenceKind.BROWSER_PAGE, toolCall.id, page.snapshotId, page.contentHash, page.sourceRef)
                    page.content()
                }
                else -> return@withContext failure("UNSUPPORTED_BROWSER_TOOL")
            }
            currentCoroutineContext().ensureActive()
            if (!isForegroundOwnerActive()) failure("FOREGROUND_OWNER_REVOKED") else {
                currentCoroutineContext().ensureActive()
                SharedToolExecutionResult(true, content, readableEvidence = evidence)
            }
        } catch (error: SharedBrowserException) { failure(error.failure.name) }
        catch (_: IllegalArgumentException) { failure("INVALID_BROWSER_ARGUMENTS") }
    }
    private fun failure(code: String) = SharedToolExecutionResult(false, "浏览器工具拒绝：$code")
}
