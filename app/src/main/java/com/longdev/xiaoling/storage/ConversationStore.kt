package com.longdev.xiaoling.storage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class StoredConversations(
    val conversations: List<StoredConversation>,
    val selectedConversationId: String,
)

data class StoredConversation(
    val id: String,
    val title: String,
    val summary: String,
    val summaryUntilMessageId: String?,
    val summaryUpdatedAt: Long?,
    val summaryModel: String?,
    val messages: List<StoredConversationMessage>,
    val createdAt: Long,
    val updatedAt: Long,
)

data class StoredConversationMessage(
    val id: String,
    val role: String,
    val text: String,
    val createdAt: Long,
    val origin: String?,
    val verifiedAgentContext: String?,
    val meta: StoredMessageMeta?,
)

data class StoredMessageMeta(
    val providerId: String?,
    val providerName: String?,
    val model: String?,
    val apiMode: String?,
    val streaming: Boolean?,
    val requestUrl: String?,
    val firstTokenLatencyMs: Long?,
    val latencyMs: Long?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val finishReason: String?,
    val errorKind: String?,
    val errorMessage: String?,
)

class ConversationStore(context: Context) {
    private val preferences = context.getSharedPreferences("xiaoling_conversations", Context.MODE_PRIVATE)

    fun load(): StoredConversations {
        val conversations = preferences.getString(KEY_CONVERSATIONS_JSON, null)
            ?.let(::decodeConversations)
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(newConversation())
        val selectedId = preferences.getString(KEY_SELECTED_CONVERSATION_ID, null)
            ?.takeIf { id -> conversations.any { it.id == id } }
            ?: conversations.first().id
        return StoredConversations(conversations, selectedId)
    }

    fun save(conversations: List<StoredConversation>, selectedConversationId: String) {
        val safeConversations = conversations.ifEmpty { listOf(newConversation()) }
        preferences.edit()
            .putString(KEY_CONVERSATIONS_JSON, encodeConversations(safeConversations))
            .putString(
                KEY_SELECTED_CONVERSATION_ID,
                selectedConversationId.takeIf { id -> safeConversations.any { it.id == id } } ?: safeConversations.first().id,
            )
            .apply()
    }

    private fun newConversation(): StoredConversation {
        val now = System.currentTimeMillis()
        return StoredConversation(
            id = "conversation-$now",
            title = "新会话",
            summary = "",
            summaryUntilMessageId = null,
            summaryUpdatedAt = null,
            summaryModel = null,
            messages = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun encodeConversations(conversations: List<StoredConversation>): String {
        val array = JSONArray()
        conversations.forEach { conversation ->
            array.put(
                JSONObject()
                    .put("id", conversation.id)
                    .put("title", conversation.title)
                    .put("summary", conversation.summary)
                    .put("summaryUntilMessageId", conversation.summaryUntilMessageId.orEmpty())
                    .put("summaryUpdatedAt", conversation.summaryUpdatedAt ?: 0L)
                    .put("summaryModel", conversation.summaryModel.orEmpty())
                    .put("createdAt", conversation.createdAt)
                    .put("updatedAt", conversation.updatedAt)
                    .put(
                        "messages",
                        JSONArray().apply {
                            conversation.messages.forEach { message ->
                                put(
                                    JSONObject()
                                        .put("id", message.id)
                                        .put("role", message.role)
                                        .put("text", message.text)
                                        .put("createdAt", message.createdAt)
                                        .put("origin", message.origin.orEmpty())
                                        .put("verifiedAgentContext", message.verifiedAgentContext.orEmpty())
                                        .put("meta", message.meta.toJson()),
                                )
                            }
                        },
                    ),
            )
        }
        return array.toString()
    }

    private fun decodeConversations(raw: String): List<StoredConversation> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val json = array.optJSONObject(index) ?: continue
                val messages = json.optJSONArray("messages").toMessageList()
                add(
                    StoredConversation(
                        id = json.optString("id").ifBlank { "conversation-$index" },
                        title = json.optString("title").ifBlank { messages.firstUserTitle() },
                        summary = json.optString("summary"),
                        summaryUntilMessageId = json.optString("summaryUntilMessageId").takeIf { it.isNotBlank() },
                        summaryUpdatedAt = json.optLong("summaryUpdatedAt").takeIf { it > 0L },
                        summaryModel = json.optString("summaryModel").takeIf { it.isNotBlank() },
                        messages = messages,
                        createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
                    ),
                )
            }
        }
    }.getOrElse { emptyList() }

    private fun JSONArray?.toMessageList(): List<StoredConversationMessage> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val json = optJSONObject(index) ?: continue
                val role = json.optString("role")
                val text = json.optString("text")
                if (role.isBlank() || text.isBlank()) continue
                add(
                    StoredConversationMessage(
                        id = json.optString("id").ifBlank { "message-${System.currentTimeMillis()}-$index" },
                        role = role,
                        text = text,
                        createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                        origin = json.optString("origin").takeIf { it.isNotBlank() },
                        verifiedAgentContext = json.optString("verifiedAgentContext").takeIf { it.isNotBlank() },
                        meta = json.optJSONObject("meta")?.toStoredMessageMeta(),
                    ),
                )
            }
        }
    }

    private fun StoredMessageMeta?.toJson(): JSONObject? {
        if (this == null) return null
        return JSONObject().apply {
            providerId?.let { put("providerId", it) }
            providerName?.let { put("providerName", it) }
            model?.let { put("model", it) }
            apiMode?.let { put("apiMode", it) }
            streaming?.let { put("streaming", it) }
            requestUrl?.let { put("requestUrl", it) }
            firstTokenLatencyMs?.let { put("firstTokenLatencyMs", it) }
            latencyMs?.let { put("latencyMs", it) }
            promptTokens?.let { put("promptTokens", it) }
            completionTokens?.let { put("completionTokens", it) }
            totalTokens?.let { put("totalTokens", it) }
            finishReason?.let { put("finishReason", it) }
            errorKind?.let { put("errorKind", it) }
            errorMessage?.let { put("errorMessage", it) }
        }
    }

    private fun JSONObject.toStoredMessageMeta(): StoredMessageMeta {
        return StoredMessageMeta(
            providerId = optString("providerId").takeIf { it.isNotBlank() },
            providerName = optString("providerName").takeIf { it.isNotBlank() },
            model = optString("model").takeIf { it.isNotBlank() },
            apiMode = optString("apiMode").takeIf { it.isNotBlank() },
            streaming = if (has("streaming")) optBoolean("streaming") else null,
            requestUrl = optString("requestUrl").takeIf { it.isNotBlank() },
            firstTokenLatencyMs = optLong("firstTokenLatencyMs").takeIf { has("firstTokenLatencyMs") },
            latencyMs = optLong("latencyMs").takeIf { has("latencyMs") },
            promptTokens = optInt("promptTokens").takeIf { has("promptTokens") },
            completionTokens = optInt("completionTokens").takeIf { has("completionTokens") },
            totalTokens = optInt("totalTokens").takeIf { has("totalTokens") },
            finishReason = optString("finishReason").takeIf { it.isNotBlank() },
            errorKind = optString("errorKind").takeIf { it.isNotBlank() },
            errorMessage = optString("errorMessage").takeIf { it.isNotBlank() },
        )
    }

    private fun List<StoredConversationMessage>.firstUserTitle(): String {
        return firstOrNull { it.role == "user" }
            ?.text
            ?.trim()
            ?.take(18)
            ?.ifBlank { null }
            ?: "新会话"
    }

    companion object {
        private const val KEY_CONVERSATIONS_JSON = "conversations_json"
        private const val KEY_SELECTED_CONVERSATION_ID = "selected_conversation_id"
    }
}
