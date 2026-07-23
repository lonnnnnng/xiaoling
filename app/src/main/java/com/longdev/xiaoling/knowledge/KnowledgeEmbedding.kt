package com.longdev.xiaoling.knowledge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class KnowledgeEmbeddingBatch(
    val providerId: String,
    val model: String,
    val vectors: List<FloatArray>,
) {
    init {
        require(providerId.isNotBlank()) { "Embedding 提供方 ID 不能为空" }
        require(model.isNotBlank()) { "Embedding 模型不能为空" }
        require(vectors.isNotEmpty()) { "Embedding 结果不能为空" }
        val dimensions = vectors.first().size
        require(dimensions > 0) { "Embedding 向量不能为空" }
        require(vectors.all { it.size == dimensions && it.all(Float::isFinite) }) {
            "Embedding 向量必须维度一致且只包含有限值"
        }
    }
}

fun interface KnowledgeEmbeddingProvider {
    suspend fun embed(texts: List<String>): KnowledgeEmbeddingBatch
}

object KnowledgeEmbeddingVectorCodec {
    fun encode(vector: FloatArray): ByteArray {
        // long: 统一用 little-endian Float32 保存，保证不同 Provider 批次和 Room 重建后仍能按同一维度解码。
        require(vector.isNotEmpty() && vector.all(Float::isFinite)) { "Embedding 向量无效" }
        return ByteBuffer.allocate(vector.size * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { vector.forEach(::putFloat) }
            .array()
    }

    fun decode(bytes: ByteArray, dimensions: Int): FloatArray {
        // long: 维度先验校验可阻断模型切换后的旧 BLOB 被错误解释，检索失败时由上层回退词法结果。
        require(dimensions > 0 && bytes.size == dimensions * Float.SIZE_BYTES) { "Embedding BLOB 长度与维度不匹配" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(dimensions) { buffer.float }
            .also { vector -> require(vector.all(Float::isFinite)) { "Embedding BLOB 包含无效数值" } }
    }
}

object KnowledgeEmbeddingSimilarity {
    fun cosine(left: FloatArray, right: FloatArray): Double? {
        // long: 零向量没有可解释方向，返回 null 让检索审计记录维度/Provider 异常而不是伪造相似度。
        if (left.isEmpty() || left.size != right.size) return null
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        left.indices.forEach { index ->
            val leftValue = left[index].toDouble()
            val rightValue = right[index].toDouble()
            if (!leftValue.isFinite() || !rightValue.isFinite()) return null
            dot += leftValue * rightValue
            leftNorm += leftValue * leftValue
            rightNorm += rightValue * rightValue
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return null
        return dot / (sqrt(leftNorm) * sqrt(rightNorm))
    }
}

object KnowledgeSearchFusionPolicy {
    fun fuse(
        ftsIds: List<String>,
        likeIds: List<String>,
        semanticIds: List<String>,
        limit: Int,
    ): List<String> {
        // long: 没有语义候选时保留 FTS+LIKE 的历史顺序，避免 Provider 不可用导致用户看到无关的排序变化。
        val boundedLimit = limit.coerceAtLeast(1)
        if (semanticIds.isEmpty()) return (ftsIds + likeIds).distinct().take(boundedLimit)
        val scores = linkedMapOf<String, Double>()
        val bestRanks = mutableMapOf<String, Int>()
        listOf(ftsIds, likeIds, semanticIds).forEach { rankedIds ->
            rankedIds.distinct().forEachIndexed { rank, id ->
                scores[id] = scores.getOrDefault(id, 0.0) + 1.0 / (RRF_K + rank + 1)
                bestRanks[id] = minOf(bestRanks[id] ?: Int.MAX_VALUE, rank)
            }
        }
        // long: RRF 只负责合并多路排名；同分时按最佳名次再按 chunk ID 固定排序，确保审计和 UI 不随 HashMap 遍历变化。
        return scores.keys.sortedWith(
            compareByDescending<String> { scores.getValue(it) }
                .thenBy { bestRanks.getValue(it) }
                .thenBy { it },
        ).take(boundedLimit)
    }

    private const val RRF_K = 60.0
}
