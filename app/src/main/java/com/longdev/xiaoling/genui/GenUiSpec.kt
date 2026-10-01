package com.longdev.xiaoling.genui

import org.json.JSONArray
import org.json.JSONObject

/**
 * 受限 GenUI 的领域模型。
 *
 * long：模型输出始终是外部不可信数据；第一版只接受单层 card/text/button，
 * 不允许通过 JSON 声明网络请求、代码、任意 Compose 类型或系统动作。
 */
sealed interface GenUiItem {
    val id: String

    data class Text(
        override val id: String,
        val text: String,
    ) : GenUiItem

    data class Button(
        override val id: String,
        val label: String,
        val action: String,
    ) : GenUiItem
}

data class GenUiDocument(
    val version: Int,
    val title: String?,
    val items: List<GenUiItem>,
)

data class GenUiProjection(
    val document: GenUiDocument,
    val textWithoutSpec: String,
)

object GenUiPolicy {
    const val FENCE_START = "```xiaoling-ui"
    private const val FENCE_END = "```"
    private const val MAX_JSON_CHARS = 24_000
    private const val MAX_TITLE_CHARS = 120
    private const val MAX_ITEM_COUNT = 12
    private const val MAX_ITEM_TEXT_CHARS = 2_000
    private const val MAX_ACTION_CHARS = 160

    /**
     * 只从 assistant 文本中读取第一段受限 GenUI；解析失败返回 null，让原始 Markdown 正常展示。
     */
    fun projectAssistantText(text: String): GenUiProjection? {
        val start = text.indexOf(FENCE_START)
        if (start < 0) return null
        val jsonStart = start + FENCE_START.length
        val end = text.indexOf(FENCE_END, jsonStart)
        if (end < 0) return null
        val json = text.substring(jsonStart, end).trim()
        if (json.length > MAX_JSON_CHARS) return null
        val document = runCatching { parseDocument(JSONObject(json)) }.getOrNull() ?: return null
        val before = text.substring(0, start).trim()
        val after = text.substring(end + FENCE_END.length).trim()
        val remaining = listOf(before, after).filter(String::isNotBlank).joinToString("\n\n")
        return GenUiProjection(document = document, textWithoutSpec = remaining)
    }

    private fun parseDocument(root: JSONObject): GenUiDocument {
        require(root.optString("type") == "card") { "GenUI 根节点类型不受支持" }
        val version = root.optInt("version", -1)
        require(version == 1) { "GenUI schema 版本不受支持" }
        val title = root.optString("title").trim().takeIf(String::isNotBlank)?.also {
            require(it.length <= MAX_TITLE_CHARS) { "GenUI 标题过长" }
            require(it.none(Char::isISOControl)) { "GenUI 标题包含控制字符" }
        }
        val rawItems = root.optJSONArray("items") ?: JSONArray()
        require(rawItems.length() in 1..MAX_ITEM_COUNT) { "GenUI item 数量无效" }
        val ids = linkedSetOf<String>()
        val items = buildList(rawItems.length()) {
            for (index in 0 until rawItems.length()) {
                val item = rawItems.getJSONObject(index)
                val id = item.optString("id").trim()
                require(id.matches(ID_PATTERN)) { "GenUI item id 无效" }
                require(ids.add(id)) { "GenUI item id 重复" }
                when (item.optString("type")) {
                    "text" -> add(GenUiItem.Text(id, boundedText(item.optString("text"), "文本")))
                    "button" -> {
                        val label = boundedText(item.optString("label"), "按钮标签")
                        val action = item.optString("action").trim()
                        require(action.length in 1..MAX_ACTION_CHARS) { "GenUI action 无效" }
                        require(action.none(Char::isISOControl)) { "GenUI action 包含控制字符" }
                        add(GenUiItem.Button(id, label, action))
                    }
                    else -> throw IllegalArgumentException("GenUI item 类型不受支持")
                }
            }
        }
        return GenUiDocument(version = version, title = title, items = items)
    }

    private fun boundedText(value: String, label: String): String {
        val text = value.trim()
        require(text.isNotBlank() && text.length <= MAX_ITEM_TEXT_CHARS) { "GenUI $label 无效" }
        require(text.none(Char::isISOControl)) { "GenUI $label 包含控制字符" }
        return text
    }

    private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
}
