package com.longdev.xiaoling.genui

import org.json.JSONArray
import org.json.JSONObject

/**
 * 受限 GenUI 的领域模型。
 *
 * long：模型输出始终是外部不可信数据；v1 仅回填草稿，v2 声明固定只读工具与版本化单层组件，
 * 执行许可由本进程完成事件和前台审批签发，JSON 本身不能声明代码、网络或任意 Compose 类型。
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

    data class Facts(override val id: String, val entries: List<Pair<String, String>>) : GenUiItem
    data class Input(override val id: String, val label: String, val maxLength: Int) : GenUiItem
    data class Action(override val id: String, val label: String, val tool: String,
        val arguments: Map<String, String>, val fields: Map<String, String>) : GenUiItem
}

data class GenUiDocument(
    val version: Int,
    val title: String?,
    val items: List<GenUiItem>,
    val id: String = "legacy",
    val revision: Int = 1,
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
        val document = runCatching { parseDocument(com.longdev.xiaoling.json.StrictJsonObject.parse(json, MAX_JSON_CHARS)) }.getOrNull() ?: return null
        val before = text.substring(0, start).trim()
        val after = text.substring(end + FENCE_END.length).trim()
        val remaining = listOf(before, after).filter(String::isNotBlank).joinToString("\n\n")
        return GenUiProjection(document = document, textWithoutSpec = remaining)
    }

    private fun parseDocument(root: JSONObject): GenUiDocument {
        if (root.opt("version") == 2) return parseV2(root)
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

    // long: v2 的组件和动作分别版本化；未知字段也降级，避免新版声明被旧客户端误解释成执行许可。
    private fun parseV2(root: JSONObject): GenUiDocument {
        exactKeys(root, setOf("type", "version", "id", "revision", "title", "items"))
        require(root.get("type") == "card" && integer(root, "version") == 2)
        val id = identifier(root, "id")
        val revision = integer(root, "revision").also { require(it in 1..1_000_000) }
        val title = if (root.has("title")) string(root, "title", MAX_TITLE_CHARS) else null
        val array = root.getJSONArray("items")
        require(array.length() in 1..MAX_ITEM_COUNT)
        val ids = mutableSetOf<String>()
        val items = (0 until array.length()).map { index ->
            val raw = array.getJSONObject(index)
            val itemId = identifier(raw, "id").also { require(ids.add(it)) }
            require(integer(raw, "version") == 1)
            when (raw.get("type")) {
                "text" -> { exactKeys(raw, setOf("type", "id", "version", "text")); GenUiItem.Text(itemId, string(raw, "text")) }
                "facts" -> {
                    exactKeys(raw, setOf("type", "id", "version", "entries"))
                    val entries = raw.getJSONArray("entries"); require(entries.length() in 1..8)
                    GenUiItem.Facts(itemId, (0 until entries.length()).map { n ->
                        val entry = entries.getJSONObject(n); exactKeys(entry, setOf("label", "value"))
                        string(entry, "label", 120) to string(entry, "value", 500)
                    })
                }
                "input" -> {
                    exactKeys(raw, setOf("type", "id", "version", "label", "maxLength", "sensitive"))
                    // long: 远端不能预填秘密或要求密码输入；草稿只由用户在本机填写，不进入消息正文。
                    require(raw.get("sensitive") == false)
                    GenUiItem.Input(itemId, string(raw, "label", 120), integer(raw, "maxLength").also { require(it in 1..120) })
                }
                "action" -> {
                    exactKeys(raw, setOf("type", "id", "version", "label", "action"))
                    val action = raw.getJSONObject("action")
                    exactKeys(action, setOf("type", "version", "tool", "arguments", "fields"))
                    require(action.get("type") == "host_read" && integer(action, "version") == 1)
                    val tool = string(action, "tool", 80).also { require(it in GenUiActionPolicy.TOOLS) }
                    GenUiItem.Action(itemId, string(raw, "label", 120), tool,
                        stringMap(action.getJSONObject("arguments")), stringMap(action.getJSONObject("fields")))
                }
                else -> error("未知 GenUI 组件")
            }
        }
        val inputs = items.filterIsInstance<GenUiItem.Input>().associateBy { it.id }
        items.filterIsInstance<GenUiItem.Action>().forEach { action ->
            require(action.arguments.keys.intersect(action.fields.keys).isEmpty())
            require(action.fields.values.all { it in inputs })
            require((action.arguments.keys + action.fields.keys).all { action.tool == "app.list_conversations" && it == "limit" })
            GenUiActionPolicy.validateArguments(action.tool, action.arguments)
        }
        return GenUiDocument(2, title, items, id, revision)
    }

    private fun exactKeys(json: JSONObject, allowed: Set<String>) {
        require(json.keys().asSequence().all { it in allowed })
    }
    private fun identifier(json: JSONObject, key: String) = string(json, key, 64).also { require(it.matches(ID_PATTERN)) }
    private fun integer(json: JSONObject, key: String): Int {
        val value = json.get(key); require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it in 1..Int.MAX_VALUE.toLong()) }.toInt()
    }
    private fun string(json: JSONObject, key: String, max: Int = MAX_ITEM_TEXT_CHARS): String {
        val value = json.get(key); require(value is String)
        return boundedText(value, key).also { require(it.length <= max) }
    }
    private fun stringMap(json: JSONObject): Map<String, String> {
        require(json.length() <= 1)
        return json.keys().asSequence().associateWith { string(json, it, 120) }
    }

    private fun boundedText(value: String, label: String): String {
        val text = value.trim()
        require(text.isNotBlank() && text.length <= MAX_ITEM_TEXT_CHARS) { "GenUI $label 无效" }
        require(text.none(Char::isISOControl)) { "GenUI $label 包含控制字符" }
        return text
    }

    private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
}
