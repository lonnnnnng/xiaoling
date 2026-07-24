package com.longdev.xiaoling.knowledge

data class KnowledgeRelevanceUserNotice(
    val title: String,
    val detail: String,
)

data class KnowledgeRelevancePresentedResult(
    val hits: List<KnowledgeSearchHit>,
    val references: List<KnowledgeReference>,
    val notice: KnowledgeRelevanceUserNotice?,
)

/**
 * long: 相关性拒绝真正接入检索前，先固定用户看到的片段、引用和解释必须来自同一批候选，避免界面说“已降级”却仍把纯语义引用交给模型或用户。
 */
object KnowledgeRelevanceUserExperiencePolicy {
    fun present(
        search: KnowledgeSearchResult,
        decision: KnowledgeRelevanceProductionDecision,
    ): KnowledgeRelevancePresentedResult {
        if (!decision.enforcementEnabled &&
            decision.disposition != KnowledgeRelevanceProductionDisposition.KEEP_CURRENT_RESULTS
        ) {
            // long: shadow 只能观察，任何畸形或迟到的删除 disposition 都不能越过 rollout 开关改变用户证据。
            return failOpen(search, "灰度开关未开启，本次已保留当前知识结果。")
        }
        if (decision.disposition != KnowledgeRelevanceProductionDisposition.KEEP_CURRENT_RESULTS &&
            search.hits.any { it.matchChannels.isEmpty() }
        ) {
            // long: 候选来源缺失时无法证明某个引用只来自语义检索；此时保留全部结果比误删真实词法证据更安全。
            return failOpen(search, "候选来源信息不完整，本次已保留当前知识结果。")
        }
        val hasLexicalHit = search.hits.any { KnowledgeSearchMatchChannel.LEXICAL in it.matchChannels }
        val sourceDecisionMismatch = when (decision.disposition) {
            KnowledgeRelevanceProductionDisposition.KEEP_CURRENT_RESULTS -> false
            KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_KEEP_LEXICAL -> !hasLexicalHit
            KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_NO_LEXICAL -> hasLexicalHit
        }
        if (sourceDecisionMismatch) {
            // long: 决策快照与候选来源互相矛盾时不能选择任一方删证据；保留当前结果，让后续接入层重新观察并修复快照。
            return failOpen(search, "相关性决策与候选来源不一致，本次已保留当前知识结果。")
        }
        val hits = when (decision.disposition) {
            KnowledgeRelevanceProductionDisposition.KEEP_CURRENT_RESULTS -> search.hits
            KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_KEEP_LEXICAL -> search.hits.filter { hit ->
                KnowledgeSearchMatchChannel.LEXICAL in hit.matchChannels
            }
            KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_NO_LEXICAL -> emptyList()
        }
        val notice = when (decision.disposition) {
            KnowledgeRelevanceProductionDisposition.KEEP_CURRENT_RESULTS -> null
            KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_KEEP_LEXICAL -> KnowledgeRelevanceUserNotice(
                title = "已降级为关键词匹配",
                detail = "语义候选相关性不足，本次仅保留关键词命中的知识片段。",
            )
            KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_NO_LEXICAL -> KnowledgeRelevanceUserNotice(
                title = "未找到足够可靠的本地知识",
                detail = "语义候选相关性不足，且没有关键词命中。",
            )
        }
        return KnowledgeRelevancePresentedResult(
            hits = hits,
            references = hits.map { hit -> hit.toKnowledgeReference(search.retrieval.id) },
            notice = notice,
        )
    }

    private fun failOpen(
        search: KnowledgeSearchResult,
        detail: String,
    ) = KnowledgeRelevancePresentedResult(
        hits = search.hits,
        references = search.hits.map { hit -> hit.toKnowledgeReference(search.retrieval.id) },
        notice = KnowledgeRelevanceUserNotice(
            title = "相关性检查暂未应用",
            detail = detail,
        ),
    )
}

fun KnowledgeSearchHit.toKnowledgeReference(retrievalId: String): KnowledgeReference {
    return KnowledgeReference(
        retrievalId = retrievalId,
        documentId = documentId,
        documentName = documentName,
        documentRevision = documentRevision,
        chunkId = chunkId,
        chunkSequence = sequence,
        startOffset = startOffset,
        endOffset = endOffset,
    )
}
