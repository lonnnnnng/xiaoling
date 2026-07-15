package com.longdev.endpointtester.storage

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
    val messages: List<StoredConversationMessage>,
    val createdAt: Long,
    val updatedAt: Long,
)

data class StoredConversationMessage(
    val role: String,
    val text: String,
    val footer: String?,
)

class ConversationStore(context: Context) {
    private val preferences = context.getSharedPreferences("endpoint_tester_conversations", Context.MODE_PRIVATE)

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
                    .put("createdAt", conversation.createdAt)
                    .put("updatedAt", conversation.updatedAt)
                    .put(
                        "messages",
                        JSONArray().apply {
                            conversation.messages.forEach { message ->
                                put(
                                    JSONObject()
                                        .put("role", message.role)
                                        .put("text", message.text)
                                        .put("footer", message.footer.orEmpty()),
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
                        role = role,
                        text = text,
                        footer = json.optString("footer").takeIf { it.isNotBlank() },
                    ),
                )
            }
        }
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
