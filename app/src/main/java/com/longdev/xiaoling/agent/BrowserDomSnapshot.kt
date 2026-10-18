package com.longdev.xiaoling.agent

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.security.MessageDigest

enum class BrowserDomAction { NAVIGATE_LINK, TYPE_DRAFT }

data class BrowserDomNode(
    val nodeId: String,
    val parentId: String?,
    val tag: String,
    val role: String,
    val name: String,
    val text: String,
    val href: String? = null,
    val actions: Set<BrowserDomAction> = emptySet(),
    val ref: String = "",
    val draftLength: Int = 0,
    val draftSha256: String? = null,
)

data class BrowserDomSnapshot(
    val schemaVersion: Int = 1,
    val source: String = "SERVER_HTML",
    val nodes: List<BrowserDomNode>,
    val truncated: Boolean,
)

internal object BrowserDomExtractor {
    fun extract(html: String, baseUrl: String, maxChars: Int): ExtractedHtml {
        val document = Jsoup.parse(html, baseUrl)
        // long: 这里只解析服务端 HTML，不执行脚本或加载子资源；表单原始值不能混进正文、节点名或持久审计。
        document.select("script,style,noscript,template,iframe,object,embed,base").remove()
        document.select("textarea").forEach { it.empty() }
        val sensitiveForms = document.select("form").filter { it.select("input[type=password]").isNotEmpty() }.toSet()
        // long: 先去除已知隐藏子树再聚合文本，避免父节点、label 和正文把隐藏后代重新带回证据；外部 CSS 可见性不属于服务端 HTML 契约。
        document.getAllElements().filter(::hidden).forEach { it.remove() }
        val labels = document.select("label[for]").groupBy { it.attr("for") }
        val elements = document.getAllElements()
        val nodes = mutableListOf<BrowserDomNode>()
        val ids = java.util.IdentityHashMap<Element, String>()
        var truncated = elements.size > BrowserUrlPolicy.MAX_DOM_SCAN
        for (element in elements.take(BrowserUrlPolicy.MAX_DOM_SCAN)) {
            if (nodes.size >= BrowserUrlPolicy.MAX_DOM_NODES) { truncated = true; break }
            val ancestry = mutableListOf<Element>()
            var ancestor: Element? = element
            while (ancestor != null && ancestry.size <= BrowserUrlPolicy.MAX_DOM_DEPTH) {
                ancestry.add(ancestor); ancestor = ancestor.parent()
            }
            // long: 超深节点和 HTML 明确隐藏的节点不进入快照；这里没有渲染树，不能把静态语义当成实际页面可见性。
            if (ancestor != null || ancestry.any(::hidden)) continue
            val tag = element.normalName()
            val role = when (tag) {
                "a" -> "link"
                "h1", "h2", "h3", "h4", "h5", "h6" -> "heading"
                "input", "textarea" -> "textbox"
                "button" -> "button"
                "p" -> "paragraph"
                "ul", "ol" -> "list"
                "li" -> "listitem"
                "form" -> "form"
                else -> continue
            }
            if (tag == "input" && element.attr("type").equals("hidden", true)) continue
            val text = if (role == "textbox" || role == "form") "" else element.text().take(400)
            val label = labels[element.id()]?.singleOrNull()?.text()
                ?: ancestry.firstOrNull { it.normalName() == "label" }?.text()
            val name = element.attr("aria-label").ifBlank { label.orEmpty() }.ifBlank { text }
                .ifBlank { element.attr("placeholder") }.take(160)
            val disabled = ancestry.any { it.hasAttr("disabled") || it.attr("aria-disabled").equals("true", true) || it.hasAttr("readonly") }
            val sensitive = role == "textbox" && (sensitiveWords.containsMatchIn(listOf(element.attr("type"), element.id(),
                element.attr("name"), element.attr("autocomplete"), name).joinToString(" ")) ||
                ancestry.any { it in sensitiveForms })
            val editable = role == "textbox" && name.isNotBlank() && !disabled && !sensitive &&
                (tag == "textarea" || element.attr("type").lowercase() in setOf("", "text", "search"))
            val href = if (tag == "a") runCatching {
                URI(baseUrl).resolve(element.attr("href")).toString().takeIf {
                    element.hasAttr("href") && it.length <= 2_048 && (it.startsWith("https://") || it.startsWith("http://"))
                }
            }.getOrNull() else null
            val actions = when {
                href != null && !disabled -> setOf(BrowserDomAction.NAVIGATE_LINK)
                editable -> setOf(BrowserDomAction.TYPE_DRAFT)
                else -> emptySet()
            }
            val nodeId = "n${nodes.size.toString(36)}"
            ids[element] = nodeId
            nodes += BrowserDomNode(nodeId, ancestry.drop(1).firstNotNullOfOrNull { ids[it] }, tag, role,
                if (sensitive) "敏感输入（不可操作）" else name, text, href, actions)
        }
        // long: 链接来自同一份可操作节点，而不是另一套正则，隐藏元素不能通过旧 link ref 旁路进入点击。
        val allLinks = nodes.filter { BrowserDomAction.NAVIGATE_LINK in it.actions }.mapNotNull { it.href }.distinct()
        val links = allLinks.take(BrowserUrlPolicy.MAX_LINKS)
        if (allLinks.size > links.size) {
            truncated = true
            // long: 超出链接预算的节点仍可阅读，但不得宣称可导航，否则模型会拿到不能消费的空引用。
            nodes.indices.forEach { index ->
                if (nodes[index].href !in links) nodes[index] = nodes[index].copy(actions = nodes[index].actions - BrowserDomAction.NAVIGATE_LINK)
            }
        }
        document.select("input,textarea").remove()
        val title = document.title().take(512).ifBlank { null }
        val text = document.text().trim()
        return ExtractedHtml(title, text.take(maxChars), links, text.length > maxChars,
            BrowserDomSnapshot(nodes = nodes, truncated = truncated))
    }

    private fun hidden(element: Element): Boolean {
        val style = element.attr("style").lowercase().replace(Regex("\\s+"), "")
        return element.hasAttr("hidden") || element.hasAttr("inert") || element.attr("aria-hidden").equals("true", true) ||
            "display:none" in style || "visibility:hidden" in style
    }
    private val sensitiveWords = Regex("password|passwd|secret|token|api.?key|credential|username|one-time|cc-|email|tel|密码|密钥|验证码|身份证|银行卡", RegexOption.IGNORE_CASE)
}

internal object BrowserTypeTextAuditPolicy {
    const val TOOL_NAME = "browser.type"
    fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun toolCallForPersistence(call: ToolCall): ToolCall {
        val baseKeys = setOf("session_id", "snapshot_id", "ref")
        require(baseKeys.all { !call.arguments[it].isNullOrBlank() }) { "浏览器输入审计目标不完整" }
        if (call.arguments.keys == baseKeys + "text") {
            val text = requireNotNull(call.arguments["text"])
            // long: 原文仅供当前进程里的用户确认和草稿更新；任何持久调用方共用这份投影，不能恢复旧输入原文。
            return call.copy(arguments = call.arguments - "text" + mapOf("text_sha256" to fingerprint(text), "text_length" to text.length.toString()))
        }
        require(call.arguments.keys == baseKeys + setOf("text_sha256", "text_length") &&
            call.arguments["text_sha256"].orEmpty().matches(Regex("[0-9a-f]{64}")) &&
            call.arguments["text_length"]?.toIntOrNull()?.let { it in 1..BrowserUrlPolicy.MAX_DRAFT_CHARS } == true) {
            "浏览器输入审计参数不完整"
        }
        return call
    }
}
