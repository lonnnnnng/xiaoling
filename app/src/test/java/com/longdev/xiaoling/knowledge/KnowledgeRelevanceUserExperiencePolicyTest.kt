package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Test

class KnowledgeRelevanceUserExperiencePolicyTest {
    @Test
    fun lowSemanticScoreKeepsLexicalAndOverlappingEvidenceWithMatchingReferences() {
        val search = searchResult(
            hit("chunk-lexical", setOf(KnowledgeSearchMatchChannel.LEXICAL)),
            hit("chunk-semantic", setOf(KnowledgeSearchMatchChannel.SEMANTIC)),
            hit(
                "chunk-overlap",
                setOf(KnowledgeSearchMatchChannel.LEXICAL, KnowledgeSearchMatchChannel.SEMANTIC),
            ),
        )

        val presentation = KnowledgeRelevanceUserExperiencePolicy.present(
            search = search,
            decision = decision(KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_KEEP_LEXICAL),
        )

        assertEquals(listOf("chunk-lexical", "chunk-overlap"), presentation.hits.map { it.chunkId })
        assertEquals(listOf("chunk-lexical", "chunk-overlap"), presentation.references.map { it.chunkId })
        assertEquals("已降级为关键词匹配", presentation.notice?.title)
        assertEquals("语义候选相关性不足，本次仅保留关键词命中的知识片段。", presentation.notice?.detail)
    }

    @Test
    fun missingCandidateSourceFailsOpenWithoutDroppingHitsOrReferences() {
        val search = searchResult(hit("chunk-unknown", emptySet()))

        val presentation = KnowledgeRelevanceUserExperiencePolicy.present(
            search = search,
            decision = decision(KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_KEEP_LEXICAL),
        )

        assertEquals(listOf("chunk-unknown"), presentation.hits.map { it.chunkId })
        assertEquals(listOf("chunk-unknown"), presentation.references.map { it.chunkId })
        assertEquals("相关性检查暂未应用", presentation.notice?.title)
        assertEquals("候选来源信息不完整，本次已保留当前知识结果。", presentation.notice?.detail)
    }

    @Test
    fun decisionAndCandidateSourcesMustAgreeBeforeReferencesCanBeRemoved() {
        val semanticOnly = searchResult(hit("chunk-semantic-only", setOf(KnowledgeSearchMatchChannel.SEMANTIC)))
        val lexical = searchResult(hit("chunk-lexical-only", setOf(KnowledgeSearchMatchChannel.LEXICAL)))

        val claimedFallback = KnowledgeRelevanceUserExperiencePolicy.present(
            search = semanticOnly,
            decision = decision(KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_KEEP_LEXICAL),
        )
        val claimedNoLexical = KnowledgeRelevanceUserExperiencePolicy.present(
            search = lexical,
            decision = decision(KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_NO_LEXICAL),
        )

        assertEquals(listOf("chunk-semantic-only"), claimedFallback.hits.map { it.chunkId })
        assertEquals(listOf("chunk-semantic-only"), claimedFallback.references.map { it.chunkId })
        assertEquals("相关性检查暂未应用", claimedFallback.notice?.title)
        assertEquals(listOf("chunk-lexical-only"), claimedNoLexical.hits.map { it.chunkId })
        assertEquals(listOf("chunk-lexical-only"), claimedNoLexical.references.map { it.chunkId })
        assertEquals("相关性检查暂未应用", claimedNoLexical.notice?.title)
    }

    @Test
    fun noLexicalFallbackProducesNoReferencesAndExplainsTheEmptyResult() {
        val search = searchResult(hit("chunk-semantic", setOf(KnowledgeSearchMatchChannel.SEMANTIC)))

        val presentation = KnowledgeRelevanceUserExperiencePolicy.present(
            search = search,
            decision = decision(KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_NO_LEXICAL),
        )

        assertEquals(emptyList<KnowledgeSearchHit>(), presentation.hits)
        assertEquals(emptyList<KnowledgeReference>(), presentation.references)
        assertEquals("未找到足够可靠的本地知识", presentation.notice?.title)
    }

    @Test
    fun shadowDecisionKeepsCurrentHitsAndDoesNotExposeAnUnappliedWarning() {
        val search = searchResult(hit("chunk-semantic", setOf(KnowledgeSearchMatchChannel.SEMANTIC)))

        val presentation = KnowledgeRelevanceUserExperiencePolicy.present(
            search = search,
            decision = decision(KnowledgeRelevanceProductionDisposition.KEEP_CURRENT_RESULTS).copy(
                reason = KnowledgeRelevanceProductionReason.GATE_DISABLED,
                enforcementEnabled = false,
                semanticRejectionWouldApply = true,
            ),
        )

        assertEquals(listOf("chunk-semantic"), presentation.hits.map { it.chunkId })
        assertEquals(listOf("chunk-semantic"), presentation.references.map { it.chunkId })
        assertEquals(null, presentation.notice)
    }

    @Test
    fun shadowModeCannotRemoveEvidenceEvenIfDispositionIsMalformed() {
        val search = searchResult(hit("chunk-lexical", setOf(KnowledgeSearchMatchChannel.LEXICAL)))

        val presentation = KnowledgeRelevanceUserExperiencePolicy.present(
            search = search,
            decision = decision(KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_KEEP_LEXICAL).copy(
                enforcementEnabled = false,
            ),
        )

        assertEquals(listOf("chunk-lexical"), presentation.hits.map { it.chunkId })
        assertEquals(listOf("chunk-lexical"), presentation.references.map { it.chunkId })
        assertEquals("相关性检查暂未应用", presentation.notice?.title)
    }

    private fun searchResult(vararg hits: KnowledgeSearchHit): KnowledgeSearchResult {
        return KnowledgeSearchResult(
            hits = hits.toList(),
            retrieval = KnowledgeRetrievalRecord(
                id = "retrieval-stage88",
                query = "阶段 88",
                chunkIds = hits.map { it.chunkId },
                documentIds = hits.map { it.documentId }.distinct(),
                sourceConversationId = "conversation-stage88",
                sourceRunId = "run-stage88",
                embeddingProviderId = "provider-a",
                embeddingModel = "embedding-a",
                embeddingStatus = KnowledgeEmbeddingStatus.USED,
                embeddingTopScore = 0.60,
                createdAt = 1L,
            ),
        )
    }

    private fun hit(
        chunkId: String,
        channels: Set<KnowledgeSearchMatchChannel>,
    ) = KnowledgeSearchHit(
        chunkId = chunkId,
        documentId = "document-$chunkId",
        documentRevision = 2,
        documentName = "$chunkId.md",
        sequence = 1,
        startOffset = 20,
        endOffset = 44,
        text = "知识片段 $chunkId",
        matchChannels = channels,
    )

    private fun decision(disposition: KnowledgeRelevanceProductionDisposition) =
        KnowledgeRelevanceProductionDecision(
            disposition = disposition,
            reason = KnowledgeRelevanceProductionReason.BELOW_FROZEN_THRESHOLD,
            enforcementEnabled = true,
            semanticRejectionWouldApply = true,
            preserveLexicalFallback = disposition == KnowledgeRelevanceProductionDisposition.DROP_SEMANTIC_KEEP_LEXICAL,
        )
}
