package com.longdev.xiaoling.genui

import java.security.MessageDigest
import java.util.UUID

object GenUiActionPolicy {
    const val TTL_MS = 300_000L
    val TOOLS = setOf("app.current_time", "app.get_info", "app.get_battery", "app.get_connectivity", "app.get_storage", "app.list_conversations")
    fun validateArguments(tool: String, arguments: Map<String, String>) {
        require(tool in TOOLS && arguments.keys.all { tool == "app.list_conversations" && it == "limit" }) { "卡片只支持固定只读工具" }
        arguments["limit"]?.let { require(it.matches(Regex("[1-9]|10"))) { "会话条数须为 1–10" } }
    }
    fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

data class GenUiActionLease internal constructor(val token: String, val expiresAt: Long)

/** long: 只有本进程 Completed 事件签发引用；历史加载、重组和用户点击都不能补发执行许可。 */
class GenUiActionLeases(private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val conversation: String, val message: String, val hash: String,
        val document: GenUiDocument, val lease: GenUiActionLease, var consumed: Boolean = false)
    private val entries = linkedMapOf<String, Entry>()
    @Synchronized fun issue(conversation: String, message: String, text: String): GenUiActionLease? {
        val document = GenUiPolicy.projectAssistantText(text)?.document ?: return null
        if (document.version != 2 || document.items.none { it is GenUiItem.Action }) return null
        require(conversation.isNotBlank() && message.isNotBlank())
        entries.entries.removeAll { now() >= it.value.lease.expiresAt }
        while (entries.size >= 32) entries.remove(entries.keys.first())
        val lease = GenUiActionLease(UUID.randomUUID().toString(), now() + GenUiActionPolicy.TTL_MS)
        entries[lease.token] = Entry(conversation, message, GenUiActionPolicy.hash(text), document, lease)
        return lease
    }
    @Synchronized fun select(lease: GenUiActionLease, conversation: String, message: String, text: String,
        actionId: String, drafts: Map<String, String>): GenUiActionSelection {
        val entry = entries[lease.token]
        require(entry != null && entry.lease == lease && !entry.consumed && now() < lease.expiresAt &&
            entry.conversation == conversation && entry.message == message && entry.hash == GenUiActionPolicy.hash(text)) {
            "卡片引用已失效，请在当前会话生成新卡片"
        }
        val action = entry.document.items.filterIsInstance<GenUiItem.Action>().single { it.id == actionId }
        val inputs = entry.document.items.filterIsInstance<GenUiItem.Input>().associateBy { it.id }
        require(drafts.keys.all { it in inputs }) { "卡片草稿来源无效" }
        val args = action.arguments + action.fields.mapValues { (_, field) ->
            val value = drafts[field].orEmpty(); require(value.length <= inputs.getValue(field).maxLength)
            value.trim()
        }
        // long: 草稿提交只开放数字条数，任意正文/密钥不会通过参数边界进入审批或 Tool Ledger。
        GenUiActionPolicy.validateArguments(action.tool, args)
        entry.consumed = true
        return GenUiActionSelection(conversation, message, entry.hash, entry.document.id, entry.document.revision,
            action.id, action.tool, args.toMap()) { synchronized(this) { entries[lease.token] === entry && now() < lease.expiresAt } }
    }
    @Synchronized fun revokeAll() { entries.clear() }
    @Synchronized fun available(conversation: String): Map<String, GenUiActionLease> = entries.values
        .filter { it.conversation == conversation && !it.consumed && now() < it.lease.expiresAt }
        .associate { it.message to it.lease }
}

class GenUiActionSelection internal constructor(
    val conversationId: String, val messageId: String, val messageSha256: String,
    val documentId: String, val revision: Int, val actionId: String, val tool: String,
    val arguments: Map<String, String>, private val current: () -> Boolean,
) {
    private var claimed = false
    fun isCurrent() = current()
    @Synchronized fun claim() {
        check(!claimed && isCurrent()) { "卡片选择已使用或已过期" }; claimed = true
    }
}
