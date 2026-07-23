package com.longdev.xiaoling.storage

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.knowledge.KnowledgeEmbeddingStatus
import com.longdev.xiaoling.knowledge.KnowledgeSearchQualityCaseResult
import com.longdev.xiaoling.knowledge.KnowledgeSearchQualityPolicy
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.network.OpenAiKnowledgeEmbeddingProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class RealProviderKnowledgeScaleInstrumentedTest {
    @Test
    fun boundedCorpusRecordsQualityLatencyAndMemoryBaseline() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString(ARG_BASE_URL).orEmpty().trim()
        val apiKey = arguments.getString(ARG_API_KEY).orEmpty().trim()
        val embeddingModel = arguments.getString(ARG_MODEL).orEmpty().trim()
        assumeTrue("未显式提供真实 Embedding Provider，跳过有界语料基线", baseUrl.isNotBlank())
        assumeTrue("未显式提供真实 Embedding API Key，跳过有界语料基线", apiKey.isNotBlank())
        assumeTrue("未显式提供真实 Embedding 模型，跳过有界语料基线", embeddingModel.isNotBlank())

        val client = OpenAiCompatibleClient()
        val config = ProviderRequestConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = embeddingModel,
            providerId = PROVIDER_ID,
            embeddingModel = embeddingModel,
        )
        assertTrue(client.fetchModels(config).any { it.equals(embeddingModel, ignoreCase = true) })

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            // long: 阶段验收从外部独立启动本测试三次，让每轮 PSS 都有新进程基线；单次内循环三轮会把前轮 HTTP/SQLite 分配混入后续样本。
            val semanticStore = RoomKnowledgeDocumentStore(
                context = context,
                database = database,
                embeddingProvider = OpenAiKnowledgeEmbeddingProvider(PROVIDER_ID, config, client),
            )
            val lexicalStore = RoomKnowledgeDocumentStore(context, database)
            val memoryBefore = memorySnapshot()
            val documentsByName = linkedMapOf<String, String>()

            // long: 十篇单主题短文把真实 Provider 调用控制在有界范围，同时给跨语言召回留出足够的干扰文档。
            val indexStartedAt = SystemClock.elapsedRealtimeNanos()
            CORPUS.forEach { entry ->
                val document = semanticStore.importUtf8Document(
                    displayName = entry.fileName,
                    mimeType = "text/markdown",
                    bytes = entry.text.toByteArray(Charsets.UTF_8),
                )
                documentsByName[entry.fileName] = document.id
            }
            val indexMillis = elapsedMillisSince(indexStartedAt)
            val memoryAfterIndex = memorySnapshot()

            val queryMillis = mutableListOf<Long>()
            val qualityCases = mutableListOf<KnowledgeSearchQualityCaseResult>()
            QUERY_CASES.forEach { queryCase ->
                val relevantDocumentId = requireNotNull(documentsByName[queryCase.relevantFileName])
                val lexical = lexicalStore.search(queryCase.query, limit = QUALITY_LIMIT)
                assertTrue("跨语言基线不得被词法命中虚高", lexical.hits.isEmpty())
                val rankings = mutableListOf<List<String>>()
                repeat(QUERY_RUNS) { runIndex ->
                    val queryStartedAt = SystemClock.elapsedRealtimeNanos()
                    val result = semanticStore.search(
                        query = queryCase.query,
                        limit = QUALITY_LIMIT,
                        sourceRunId = "stage80-${queryCase.caseId}-$runIndex",
                    )
                    queryMillis += elapsedMillisSince(queryStartedAt)
                    assertEquals(KnowledgeEmbeddingStatus.USED, result.retrieval.embeddingStatus)
                    assertEquals(PROVIDER_ID, result.retrieval.embeddingProviderId)
                    assertEquals(embeddingModel, result.retrieval.embeddingModel)
                    rankings += result.hits.map { it.documentId }
                }
                qualityCases += KnowledgeSearchQualityCaseResult(
                    caseId = queryCase.caseId,
                    relevantDocumentIds = setOf(relevantDocumentId),
                    rankedDocumentIdsByRun = rankings,
                    limit = QUALITY_LIMIT,
                )
            }
            val quality = KnowledgeSearchQualityPolicy.evaluate(qualityCases)

            // long: 现有 cosine 路径没有相似度阈值；无关问题只记录返回数，不伪造“负例已拒绝”结论。
            val unrelatedStartedAt = SystemClock.elapsedRealtimeNanos()
            val unrelated = semanticStore.search(UNRELATED_QUERY, limit = QUALITY_LIMIT)
            val unrelatedQueryMillis = elapsedMillisSince(unrelatedStartedAt)
            assertEquals(KnowledgeEmbeddingStatus.USED, unrelated.retrieval.embeddingStatus)

            val vectorStats = database.vectorStats(PROVIDER_ID, embeddingModel)
            val documentCount = database.scalarLong("SELECT COUNT(*) FROM knowledge_documents")
            val chunkCount = database.scalarLong("SELECT COUNT(*) FROM knowledge_chunks")
            val sqlitePageBytes = database.scalarLong("PRAGMA page_count") *
                database.scalarLong("PRAGMA page_size")
            val memoryAfterSearch = memorySnapshot()
            val metrics = JSONObject()
                .put("documents", documentCount)
                .put("chunks", chunkCount)
                .put("vectorRows", vectorStats.rowCount)
                .put("dimensions", vectorStats.minDimensions)
                .put("vectorBytes", vectorStats.vectorBytes)
                .put("sqlitePageBytes", sqlitePageBytes)
                .put("indexMillis", indexMillis)
                .put("queryMillis", queryMillis.toJsonArray())
                .put("queryMedianMillis", queryMillis.percentile(0.50))
                .put("queryP95Millis", queryMillis.percentile(0.95))
                .put("unrelatedQueryMillis", unrelatedQueryMillis)
                .put("unrelatedHitCount", unrelated.hits.size)
                .put("recallAt5", quality.meanRecallAtK)
                .put("mrr", quality.meanReciprocalRank)
                .put("stableRankingRate", quality.stableRankingRate)
                .put("pssBeforeKb", memoryBefore.pssKb)
                .put("pssAfterIndexKb", memoryAfterIndex.pssKb)
                .put("pssAfterSearchKb", memoryAfterSearch.pssKb)
                .put("javaHeapBeforeBytes", memoryBefore.javaHeapBytes)
                .put("javaHeapAfterIndexBytes", memoryAfterIndex.javaHeapBytes)
                .put("javaHeapAfterSearchBytes", memoryAfterSearch.javaHeapBytes)
            Log.i(METRICS_TAG, metrics.toString())
            println("$METRICS_TAG ${metrics}")

            assertEquals(CORPUS.size.toLong(), documentCount)
            assertEquals(chunkCount, vectorStats.rowCount)
            assertEquals(vectorStats.minDimensions, vectorStats.maxDimensions)
            assertTrue(vectorStats.minDimensions > 0)
            assertEquals(
                vectorStats.rowCount * vectorStats.minDimensions * Float.SIZE_BYTES,
                vectorStats.vectorBytes,
            )
            assertEquals(QUERY_CASES.size, quality.positiveCaseCount)
            assertTrue("真实语料 Recall@5 低于 0.8", quality.meanRecallAtK >= 0.8)
            assertTrue("真实语料 MRR 低于 0.7", quality.meanReciprocalRank >= 0.7)
            assertTrue("重复检索排序稳定率低于 0.8", quality.stableRankingRate >= 0.8)
        } finally {
            database.close()
        }
    }

    private fun XiaoLingDatabase.vectorStats(providerId: String, model: String): VectorStats {
        val query = SimpleSQLiteQuery(
            """
                SELECT COUNT(*), COALESCE(SUM(LENGTH(vectorBlob)), 0),
                       COALESCE(MIN(dimensions), 0), COALESCE(MAX(dimensions), 0)
                FROM knowledge_chunk_embeddings
                WHERE providerId = ? AND model = ?
            """.trimIndent(),
            arrayOf(providerId, model),
        )
        return openHelper.readableDatabase.query(query).use { cursor ->
            check(cursor.moveToFirst()) { "无法读取 Embedding 向量统计" }
            VectorStats(
                rowCount = cursor.getLong(0),
                vectorBytes = cursor.getLong(1),
                minDimensions = cursor.getLong(2),
                maxDimensions = cursor.getLong(3),
            )
        }
    }

    private fun XiaoLingDatabase.scalarLong(sql: String): Long =
        openHelper.readableDatabase.query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "无法读取 SQLite 基线指标" }
            cursor.getLong(0)
        }

    private fun memorySnapshot(): MemorySnapshot {
        val runtime = Runtime.getRuntime()
        return MemorySnapshot(
            pssKb = Debug.getPss(),
            javaHeapBytes = runtime.totalMemory() - runtime.freeMemory(),
        )
    }

    private fun elapsedMillisSince(startedAtNanos: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startedAtNanos) / NANOS_PER_MILLISECOND

    private fun List<Long>.percentile(fraction: Double): Long {
        require(isNotEmpty()) { "百分位数样本不能为空" }
        val sorted = sorted()
        val index = (ceil(sorted.size * fraction).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun List<Long>.toJsonArray(): JSONArray = JSONArray().also { array ->
        forEach(array::put)
    }

    private data class CorpusEntry(
        val fileName: String,
        val text: String,
    )

    private data class QueryCase(
        val caseId: String,
        val query: String,
        val relevantFileName: String,
    )

    private data class VectorStats(
        val rowCount: Long,
        val vectorBytes: Long,
        val minDimensions: Long,
        val maxDimensions: Long,
    )

    private data class MemorySnapshot(
        val pssKb: Long,
        val javaHeapBytes: Long,
    )

    private companion object {
        const val ARG_BASE_URL = "embeddingProviderBaseUrl"
        const val ARG_API_KEY = "embeddingProviderApiKey"
        const val ARG_MODEL = "embeddingProviderModel"
        const val PROVIDER_ID = "real-embedding-scale-baseline"
        const val METRICS_TAG = "XIAOLING_STAGE80_METRICS"
        const val QUALITY_LIMIT = 5
        const val QUERY_RUNS = 2
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val UNRELATED_QUERY = "How do coral reefs coordinate spawning under moonlight?"

        val CORPUS = listOf(
            CorpusEntry("01-专注节奏.md", "番茄工作法把任务拆成二十五分钟的专注区间，之后安排短暂休息。完成四轮后再进行较长休息，减少持续分心。"),
            CorpusEntry("02-天然酵种.md", "制作酸面包前要持续喂养酵种，观察体积增长和气泡。和面后通过拉伸折叠增强筋度，等待面团充分发酵再烘焙。"),
            CorpusEntry("03-睡眠习惯.md", "稳定的入睡时间和起床时间有助于建立作息。晚间减少强光和咖啡因，保持卧室安静凉爽，可以让身体更容易进入休息状态。"),
            CorpusEntry("04-骑行检查.md", "自行车出发前应检查轮胎侧壁标注的气压范围，用气压计调整胎压。同时确认刹车、链条和快拆杆正常，避免途中故障。"),
            CorpusEntry("05-缓存验证.md", "浏览器可以保存服务器响应的验证标识。再次请求时附带该标识，服务器确认内容未变化后只返回简短状态，避免重复传输完整资源。"),
            CorpusEntry("06-植物浇水.md", "室内植物浇水前先用手指检查表层以下的土壤湿度。土壤仍然湿润时延后浇水，干燥时一次浇透并排出托盘积水。"),
            CorpusEntry("07-室内乐排练.md", "弦乐四重奏排练时，先用慢速节拍统一弓法和呼吸位置。分声部确认音准后再合奏，重点倾听内声部与旋律的平衡。"),
            CorpusEntry("08-旅行准备.md", "跨城旅行先确定日期和预算，再比较交通与住宿。行程中每天只安排少量核心地点，预留转场和休息时间，并离线保存订单。"),
            CorpusEntry("09-应急储备.md", "家庭应急资金用于失业、突发医疗或必要维修。可以先存下一个月必要支出，再逐步提高到数个月，并存放在流动性较高的账户中。"),
            CorpusEntry("10-照片备份.md", "照片备份应保留至少两份副本，其中一份放在不同的物理位置。定期抽查文件能否打开，并在更换手机前确认最近同步时间。"),
        )

        val QUERY_CASES = listOf(
            QueryCase("focus-rhythm", "What routine divides concentrated work into short timed intervals followed by breaks?", "01-专注节奏.md"),
            QueryCase("natural-starter", "How should a starter be maintained before baking naturally leavened bread?", "02-天然酵种.md"),
            QueryCase("sleep-routine", "Which evening habits make it easier to fall asleep at a consistent time?", "03-睡眠习惯.md"),
            QueryCase("bicycle-check", "What should a rider inspect and adjust before leaving on a bicycle?", "04-骑行检查.md"),
            QueryCase("response-reuse", "How can a browser avoid downloading a resource again when it has not changed?", "05-缓存验证.md"),
        )
    }
}
