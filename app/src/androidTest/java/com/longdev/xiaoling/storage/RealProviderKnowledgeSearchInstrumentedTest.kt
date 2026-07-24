package com.longdev.xiaoling.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingRebuildStatus
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingStatus
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.network.OpenAiKnowledgeEmbeddingProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealProviderKnowledgeSearchInstrumentedTest {
    @Test
    fun explicitProviderBuildsAndUsesSemanticIndex() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString(ARG_BASE_URL).orEmpty().trim()
        val apiKey = arguments.getString(ARG_API_KEY).orEmpty().trim()
        val embeddingModel = arguments.getString(ARG_MODEL).orEmpty().trim()
        assumeTrue("未显式提供真实 Embedding Provider，跳过联网语义检索验收", baseUrl.isNotBlank())
        assumeTrue("未显式提供真实 Embedding API Key，跳过联网语义检索验收", apiKey.isNotBlank())
        assumeTrue("未显式提供真实 Embedding 模型，跳过联网语义检索验收", embeddingModel.isNotBlank())

        val client = OpenAiCompatibleClient()
        val config = ProviderRequestConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = embeddingModel,
            providerId = PROVIDER_ID,
            embeddingModel = embeddingModel,
        )
        val availableModels = client.fetchModels(config)
        assertTrue(availableModels.any { it.equals(embeddingModel, ignoreCase = true) })

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            // long: 真实联网验收只写内存 Room，避免测试语料、检索审计或向量覆盖用户手机中的正式知识库。
            val provider = OpenAiKnowledgeEmbeddingProvider(PROVIDER_ID, config, client)
            val semanticStore = RoomKnowledgeDocumentStore(
                context = context,
                database = database,
                embeddingProvider = provider,
            )
            val focusDocument = semanticStore.importUtf8Document(
                displayName = "专注方法.md",
                mimeType = "text/markdown",
                bytes = "番茄工作法把任务拆成二十五分钟专注区间和短暂休息，帮助保持注意力。"
                    .toByteArray(Charsets.UTF_8),
            )
            semanticStore.importUtf8Document(
                displayName = "面包制作.md",
                mimeType = "text/markdown",
                bytes = "制作酸面包需要培养酵种、控制含水量，并等待面团充分发酵。"
                    .toByteArray(Charsets.UTF_8),
            )
            val query = "How can timed work sessions help me stay focused?"

            // long: 同一数据库先关闭语义入口证明英文问题没有词法命中，后续中文文档命中才能归因于真实跨语言向量检索。
            val lexicalResult = RoomKnowledgeDocumentStore(context, database).search(query, limit = 5)
            assertTrue(lexicalResult.hits.isEmpty())
            assertEquals(KnowledgeEmbeddingStatus.LEXICAL_ONLY, lexicalResult.retrieval.embeddingStatus)

            val semanticResult = semanticStore.search(query, limit = 5, sourceRunId = "run-real-embedding")
            assertEquals(KnowledgeEmbeddingStatus.USED, semanticResult.retrieval.embeddingStatus)
            assertEquals(PROVIDER_ID, semanticResult.retrieval.embeddingProviderId)
            assertEquals(embeddingModel, semanticResult.retrieval.embeddingModel)
            assertEquals(2, semanticResult.retrieval.embeddingCandidateCount)
            assertTrue(requireNotNull(semanticResult.retrieval.embeddingScoreMean).isFinite())
            assertTrue(requireNotNull(semanticResult.retrieval.embeddingScoreStandardDeviation) >= 0.0)
            assertTrue(requireNotNull(semanticResult.retrieval.embeddingTopScoreZScore).isFinite())
            assertEquals(focusDocument.id, semanticResult.hits.first().documentId)
            assertEquals(semanticResult.hits.map { it.chunkId }, semanticResult.retrieval.chunkIds)

            val indexes = semanticStore.getEmbeddingIndexes(focusDocument.id)
            assertEquals(1, indexes.size)
            assertEquals(PROVIDER_ID, indexes.single().providerId)
            assertEquals(embeddingModel, indexes.single().model)
            assertTrue(indexes.single().dimensions > 0)
            assertTrue(indexes.single().chunkCount > 0)

            val rebuilt = semanticStore.rebuildEmbeddings(focusDocument.id)
            assertEquals(KnowledgeEmbeddingRebuildStatus.INDEXED, rebuilt.status)
            assertEquals(1, rebuilt.documentRevision)
            assertEquals(1, semanticStore.getDocument(focusDocument.id)?.revision)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val ARG_BASE_URL = "embeddingProviderBaseUrl"
        const val ARG_API_KEY = "embeddingProviderApiKey"
        const val ARG_MODEL = "embeddingProviderModel"
        const val PROVIDER_ID = "real-embedding-compatibility"
    }
}
