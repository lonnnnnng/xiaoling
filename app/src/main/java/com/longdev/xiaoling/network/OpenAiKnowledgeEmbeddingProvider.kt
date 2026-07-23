package com.longdev.xiaoling.network

import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingBatch
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingProvider
import com.longdev.xiaoling.model.ProviderRequestConfig

class OpenAiKnowledgeEmbeddingProvider(
    private val providerId: String,
    private val config: ProviderRequestConfig,
    private val client: OpenAiCompatibleClient,
) : KnowledgeEmbeddingProvider {
    override suspend fun embed(texts: List<String>): KnowledgeEmbeddingBatch {
        require(texts.isNotEmpty()) { "Embedding 输入不能为空" }
        val model = config.embeddingModel?.trim().orEmpty()
        require(model.isNotBlank()) { "当前提供方没有可用的 Embedding 模型" }
        // long: 兼容网关常限制单次 input 数量；固定小批次并保持原顺序，避免大知识文档因单个超大请求整体失败或向量错位。
        val vectors = texts.chunked(EMBEDDING_BATCH_SIZE).flatMap { batch ->
            client.createEmbeddings(config, batch)
        }
        return KnowledgeEmbeddingBatch(
            providerId = providerId,
            model = model,
            vectors = vectors,
        )
    }

    private companion object {
        const val EMBEDDING_BATCH_SIZE = 32
    }
}
