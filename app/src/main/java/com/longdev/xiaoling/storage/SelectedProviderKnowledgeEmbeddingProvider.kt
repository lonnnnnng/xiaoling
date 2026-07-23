package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingBatch
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingProvider
import com.longdev.xiaoling.model.ProviderProfile
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.model.preferredEmbeddingModel
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.network.OpenAiKnowledgeEmbeddingProvider

class SelectedProviderKnowledgeEmbeddingProvider(
    context: Context,
    private val client: OpenAiCompatibleClient = OpenAiCompatibleClient(),
    private val providers: ProviderRepository = ProviderRepository(context.applicationContext),
    private val preferences: UiPreferenceStore = UiPreferenceStore(context.applicationContext),
) : KnowledgeEmbeddingProvider {
    override suspend fun embed(texts: List<String>): KnowledgeEmbeddingBatch {
        val stored = providers.load()
        val profile = stored.profiles.firstOrNull { it.id == stored.selectedProfileId }
            ?: error("当前没有可用的模型提供方")
        val embeddingModel = profile.preferredEmbeddingModel()
            ?: error("当前提供方没有同步到 Embedding 模型")
        // long: 知识管理页没有 Agent Profile 上下文，只能使用用户当前选中的 Provider；向量表同时保存 Provider ID 和模型，后续切换不会混用空间。
        val config = ProviderRequestConfig(
            baseUrl = profile.baseUrl.trim(),
            apiKey = profile.apiKey.trim(),
            model = profile.model.trim(),
            providerId = profile.id,
            userAgent = preferences.loadUserAgent(),
            maxTokens = ProviderProfile.FIXED_MAX_TOKENS,
            embeddingModel = embeddingModel,
        )
        return OpenAiKnowledgeEmbeddingProvider(profile.id, config, client).embed(texts)
    }
}
