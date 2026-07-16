package com.longdev.xiaoling.storage

import android.content.Context

class RoomStateStore(context: Context) {
    private val preferences = context.getSharedPreferences("xiaoling_room_state", Context.MODE_PRIVATE)

    fun providersMigrated(): Boolean = preferences.getBoolean(KEY_PROVIDERS_MIGRATED, false)

    fun markProvidersMigrated() {
        preferences.edit().putBoolean(KEY_PROVIDERS_MIGRATED, true).apply()
    }

    fun conversationsMigrated(): Boolean = preferences.getBoolean(KEY_CONVERSATIONS_MIGRATED, false)

    fun markConversationsMigrated() {
        preferences.edit().putBoolean(KEY_CONVERSATIONS_MIGRATED, true).apply()
    }

    fun selectedProviderId(): String? = preferences.getString(KEY_SELECTED_PROVIDER_ID, null)

    fun saveSelectedProviderId(id: String) {
        preferences.edit().putString(KEY_SELECTED_PROVIDER_ID, id).apply()
    }

    fun selectedConversationId(): String? = preferences.getString(KEY_SELECTED_CONVERSATION_ID, null)

    fun saveSelectedConversationId(id: String) {
        preferences.edit().putString(KEY_SELECTED_CONVERSATION_ID, id).apply()
    }

    companion object {
        private const val KEY_PROVIDERS_MIGRATED = "providers_migrated"
        private const val KEY_CONVERSATIONS_MIGRATED = "conversations_migrated"
        private const val KEY_SELECTED_PROVIDER_ID = "selected_provider_id"
        private const val KEY_SELECTED_CONVERSATION_ID = "selected_conversation_id"
    }
}
