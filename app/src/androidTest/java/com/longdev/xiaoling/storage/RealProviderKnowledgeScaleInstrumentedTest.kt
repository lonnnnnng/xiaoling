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
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceCalibrationReport
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceCalibrationPolicy
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceCalibrationSample
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceCandidateGate
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceDatasetIdentity
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceFrozenGate
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceHoldoutCriteria
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceHoldoutPolicy
import com.longdev.xiaoling.knowledge.KnowledgeRelevanceLabel
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

    @Test
    fun relevanceCalibrationRecordsPositiveNearAndFarDistributions() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString(ARG_BASE_URL).orEmpty().trim()
        val apiKey = arguments.getString(ARG_API_KEY).orEmpty().trim()
        val embeddingModel = arguments.getString(ARG_MODEL).orEmpty().trim()
        assumeTrue("未显式提供真实 Embedding Provider，跳过相关性校准", baseUrl.isNotBlank())
        assumeTrue("未显式提供真实 Embedding API Key，跳过相关性校准", apiKey.isNotBlank())
        assumeTrue("未显式提供真实 Embedding 模型，跳过相关性校准", embeddingModel.isNotBlank())

        val client = OpenAiCompatibleClient()
        val config = ProviderRequestConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = embeddingModel,
            providerId = CALIBRATION_PROVIDER_ID,
            embeddingModel = embeddingModel,
        )
        assertTrue(client.fetchModels(config).any { it.equals(embeddingModel, ignoreCase = true) })

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            // long: 外部会独立启动三次 instrumentation 进程；这里保留单进程两次重复，用于区分进程间漂移和同进程排序抖动。
            val semanticStore = RoomKnowledgeDocumentStore(
                context = context,
                database = database,
                embeddingProvider = OpenAiKnowledgeEmbeddingProvider(CALIBRATION_PROVIDER_ID, config, client),
            )
            val lexicalStore = RoomKnowledgeDocumentStore(context, database)
            val documentNames = mutableMapOf<String, String>()
            val memoryBefore = memorySnapshot()
            val indexStartedAt = SystemClock.elapsedRealtimeNanos()
            CALIBRATION_CORPUS.forEach { entry ->
                val document = semanticStore.importUtf8Document(
                    displayName = entry.fileName,
                    mimeType = "text/markdown",
                    bytes = entry.text.toByteArray(Charsets.UTF_8),
                )
                documentNames[document.id] = entry.fileName
            }
            val indexMillis = elapsedMillisSince(indexStartedAt)
            val memoryAfterIndex = memorySnapshot()

            val observations = CALIBRATION_CASES.flatMap { queryCase ->
                val lexicalHitCount = lexicalStore.search(queryCase.query, limit = QUALITY_LIMIT).hits.size
                assertEquals("校准查询不得被词法命中虚高", 0, lexicalHitCount)
                (0 until CALIBRATION_QUERY_RUNS).map { runIndex ->
                    val queryStartedAt = SystemClock.elapsedRealtimeNanos()
                    val result = semanticStore.search(
                        query = queryCase.query,
                        limit = QUALITY_LIMIT,
                        sourceRunId = "stage82-${queryCase.caseId}-$runIndex",
                    )
                    val queryMillis = elapsedMillisSince(queryStartedAt)
                    val topScore = requireNotNull(result.retrieval.embeddingTopScore)
                    val secondScore = requireNotNull(result.retrieval.embeddingSecondScore)
                    val margin = requireNotNull(result.retrieval.embeddingScoreMargin)
                    assertEquals(KnowledgeEmbeddingStatus.USED, result.retrieval.embeddingStatus)
                    assertEquals(CALIBRATION_PROVIDER_ID, result.retrieval.embeddingProviderId)
                    assertEquals(embeddingModel, result.retrieval.embeddingModel)
                    assertEquals(CALIBRATION_CORPUS.size, result.retrieval.embeddingCandidateCount)
                    assertTrue(topScore.isFinite())
                    assertTrue(secondScore.isFinite())
                    assertTrue(margin.isFinite() && margin >= 0.0)
                    assertEquals(topScore - secondScore, margin, 0.0000001)
                    val observation = CalibrationObservation(
                        caseId = queryCase.caseId,
                        label = queryCase.label,
                        runIndex = runIndex,
                        expectedFileName = queryCase.expectedFileName,
                        rankedFileNames = result.hits.map { hit ->
                            documentNames[hit.documentId] ?: hit.documentName
                        },
                        lexicalHitCount = lexicalHitCount,
                        candidateCount = requireNotNull(result.retrieval.embeddingCandidateCount),
                        queryMillis = queryMillis,
                        topScore = topScore,
                        secondScore = secondScore,
                        margin = margin,
                    )
                    Log.i(CALIBRATION_CASE_TAG, observation.toJson().toString())
                    println("$CALIBRATION_CASE_TAG ${observation.toJson()}")
                    observation
                }
            }

            val relevance = KnowledgeRelevanceCalibrationPolicy.evaluate(
                observations.map { observation ->
                    KnowledgeRelevanceCalibrationSample(
                        caseId = observation.caseId,
                        label = observation.label,
                        topScore = observation.topScore,
                        scoreMargin = observation.margin,
                    )
                },
            )
            val qualityCases = CALIBRATION_CASES.filter { it.label == KnowledgeRelevanceLabel.POSITIVE }.map { queryCase ->
                KnowledgeSearchQualityCaseResult(
                    caseId = queryCase.caseId,
                    relevantDocumentIds = setOf(requireNotNull(queryCase.expectedFileName)),
                    rankedDocumentIdsByRun = observations.filter { it.caseId == queryCase.caseId }
                        .sortedBy { it.runIndex }
                        .map { it.rankedFileNames },
                    limit = QUALITY_LIMIT,
                )
            }
            val quality = KnowledgeSearchQualityPolicy.evaluate(qualityCases)
            val recallAt1 = qualityCases.count { result ->
                result.rankedDocumentIdsByRun.first().firstOrNull() in result.relevantDocumentIds
            }.toDouble() / qualityCases.size
            val vectorStats = database.vectorStats(CALIBRATION_PROVIDER_ID, embeddingModel)
            val memoryAfterSearch = memorySnapshot()
            val queryMillis = observations.map { it.queryMillis }

            // long: 候选门禁与质量指标来自同一小样本，只能作为 shadow 观测；第 82 阶段不把阈值接入生产检索或负例拒绝。
            val metrics = JSONObject()
                .put("providerId", CALIBRATION_PROVIDER_ID)
                .put("model", embeddingModel)
                .put("shadowCandidateOnly", true)
                .put("documents", CALIBRATION_CORPUS.size)
                .put("casesPerBucket", CALIBRATION_CASES.size / KnowledgeRelevanceLabel.entries.size)
                .put("runsPerCase", CALIBRATION_QUERY_RUNS)
                .put("observations", observations.size)
                .put("indexMillis", indexMillis)
                .put("queryMedianMillis", queryMillis.percentile(0.50))
                .put("queryP95Millis", queryMillis.percentile(0.95))
                .put("candidateCount", observations.map { it.candidateCount }.distinct().single())
                .put("vectorRows", vectorStats.rowCount)
                .put("dimensions", vectorStats.minDimensions)
                .put("vectorBytes", vectorStats.vectorBytes)
                .put("recallAt1", recallAt1)
                .put("recallAt5", quality.meanRecallAtK)
                .put("mrr", quality.meanReciprocalRank)
                .put("stableRankingRate", quality.stableRankingRate)
                .put("buckets", relevance.toBucketJsonArray())
                .put("candidateGate", relevance.candidateGate.toJson())
                .put("pssBeforeKb", memoryBefore.pssKb)
                .put("pssAfterIndexKb", memoryAfterIndex.pssKb)
                .put("pssAfterSearchKb", memoryAfterSearch.pssKb)
                .put("javaHeapBeforeBytes", memoryBefore.javaHeapBytes)
                .put("javaHeapAfterIndexBytes", memoryAfterIndex.javaHeapBytes)
                .put("javaHeapAfterSearchBytes", memoryAfterSearch.javaHeapBytes)
            Log.i(CALIBRATION_METRICS_TAG, metrics.toString())
            println("$CALIBRATION_METRICS_TAG $metrics")

            assertEquals(
                CALIBRATION_CASES.size * CALIBRATION_QUERY_RUNS,
                observations.size,
            )
            assertEquals(KnowledgeRelevanceLabel.entries.toSet(), observations.map { it.label }.toSet())
            assertEquals(CALIBRATION_CORPUS.size.toLong(), vectorStats.rowCount)
            assertEquals(vectorStats.minDimensions, vectorStats.maxDimensions)
            assertTrue(vectorStats.minDimensions > 0)
            assertEquals(
                vectorStats.rowCount * vectorStats.minDimensions * Float.SIZE_BYTES,
                vectorStats.vectorBytes,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun frozenGateValidatesIndependentHoldoutWithoutRetuning() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString(ARG_BASE_URL).orEmpty().trim()
        val apiKey = arguments.getString(ARG_API_KEY).orEmpty().trim()
        val embeddingModel = arguments.getString(ARG_MODEL).orEmpty().trim()
        assumeTrue("未显式提供真实 Embedding Provider，跳过冻结门禁 holdout", baseUrl.isNotBlank())
        assumeTrue("未显式提供真实 Embedding API Key，跳过冻结门禁 holdout", apiKey.isNotBlank())
        assumeTrue("未显式提供真实 Embedding 模型，跳过冻结门禁 holdout", embeddingModel.isNotBlank())
        assertEquals("冻结门禁只适用于完成第 82 阶段校准的模型", FROZEN_MODEL, embeddingModel)

        val client = OpenAiCompatibleClient()
        val config = ProviderRequestConfig(
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = embeddingModel,
            providerId = CALIBRATION_PROVIDER_ID,
            embeddingModel = embeddingModel,
        )
        assertTrue(client.fetchModels(config).any { it.equals(embeddingModel, ignoreCase = true) })

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val store = RoomKnowledgeDocumentStore(
                context = context,
                database = database,
                embeddingProvider = OpenAiKnowledgeEmbeddingProvider(CALIBRATION_PROVIDER_ID, config, client),
            )
            val lexicalStore = RoomKnowledgeDocumentStore(context, database)
            val documentNames = mutableMapOf<String, String>()
            val memoryBefore = memorySnapshot()
            val indexStartedAt = SystemClock.elapsedRealtimeNanos()
            HOLDOUT_CORPUS.forEach { entry ->
                val document = store.importUtf8Document(
                    displayName = entry.fileName,
                    mimeType = "text/markdown",
                    bytes = entry.text.toByteArray(Charsets.UTF_8),
                )
                documentNames[document.id] = entry.fileName
            }
            val indexMillis = elapsedMillisSince(indexStartedAt)
            val memoryAfterIndex = memorySnapshot()

            val observations = HOLDOUT_CASES.flatMap { queryCase ->
                val lexicalHitCount = lexicalStore.search(queryCase.query, limit = QUALITY_LIMIT).hits.size
                assertEquals("holdout 查询不得被词法命中虚高", 0, lexicalHitCount)
                (0 until HOLDOUT_QUERY_RUNS).map { runIndex ->
                    val queryStartedAt = SystemClock.elapsedRealtimeNanos()
                    val result = store.search(
                        query = queryCase.query,
                        limit = QUALITY_LIMIT,
                        sourceRunId = "stage83-${queryCase.caseId}-$runIndex",
                    )
                    val queryMillis = elapsedMillisSince(queryStartedAt)
                    val topScore = requireNotNull(result.retrieval.embeddingTopScore)
                    val secondScore = requireNotNull(result.retrieval.embeddingSecondScore)
                    val margin = requireNotNull(result.retrieval.embeddingScoreMargin)
                    assertEquals(KnowledgeEmbeddingStatus.USED, result.retrieval.embeddingStatus)
                    assertEquals(CALIBRATION_PROVIDER_ID, result.retrieval.embeddingProviderId)
                    assertEquals(embeddingModel, result.retrieval.embeddingModel)
                    assertEquals(HOLDOUT_CORPUS.size, result.retrieval.embeddingCandidateCount)
                    assertTrue(topScore.isFinite())
                    assertTrue(secondScore.isFinite())
                    assertTrue(margin.isFinite() && margin >= 0.0)
                    assertEquals(topScore - secondScore, margin, 0.0000001)
                    val observation = CalibrationObservation(
                        caseId = queryCase.caseId,
                        label = queryCase.label,
                        runIndex = runIndex,
                        expectedFileName = queryCase.expectedFileName,
                        rankedFileNames = result.hits.map { hit ->
                            documentNames[hit.documentId] ?: hit.documentName
                        },
                        lexicalHitCount = lexicalHitCount,
                        candidateCount = requireNotNull(result.retrieval.embeddingCandidateCount),
                        queryMillis = queryMillis,
                        topScore = topScore,
                        secondScore = secondScore,
                        margin = margin,
                    )
                    Log.i(HOLDOUT_CASE_TAG, observation.toJson().toString())
                    println("$HOLDOUT_CASE_TAG ${observation.toJson()}")
                    observation
                }
            }

            val frozenGate = KnowledgeRelevanceFrozenGate(
                gateVersion = FROZEN_GATE_VERSION,
                calibrationIdentity = KnowledgeRelevanceDatasetIdentity(
                    providerId = CALIBRATION_PROVIDER_ID,
                    model = FROZEN_MODEL,
                    datasetVersion = CALIBRATION_DATASET_VERSION,
                ),
                minimumTopScore = FROZEN_MINIMUM_TOP_SCORE,
                minimumScoreMargin = FROZEN_MINIMUM_SCORE_MARGIN,
            )
            val criteria = KnowledgeRelevanceHoldoutCriteria(
                minimumPositiveAcceptanceRate = MINIMUM_POSITIVE_ACCEPTANCE_RATE,
                minimumNearNegativeRejectionRate = MINIMUM_NEAR_NEGATIVE_REJECTION_RATE,
                minimumFarNegativeRejectionRate = MINIMUM_FAR_NEGATIVE_REJECTION_RATE,
                minimumDecisionStableRate = MINIMUM_DECISION_STABLE_RATE,
            )
            val holdout = KnowledgeRelevanceHoldoutPolicy.evaluate(
                frozenGate = frozenGate,
                holdoutIdentity = KnowledgeRelevanceDatasetIdentity(
                    providerId = CALIBRATION_PROVIDER_ID,
                    model = embeddingModel,
                    datasetVersion = HOLDOUT_DATASET_VERSION,
                ),
                samples = observations.map { observation ->
                    KnowledgeRelevanceCalibrationSample(
                        caseId = observation.caseId,
                        label = observation.label,
                        topScore = observation.topScore,
                        scoreMargin = observation.margin,
                    )
                },
                criteria = criteria,
            )
            val qualityCases = HOLDOUT_CASES.filter { it.label == KnowledgeRelevanceLabel.POSITIVE }.map { queryCase ->
                KnowledgeSearchQualityCaseResult(
                    caseId = queryCase.caseId,
                    relevantDocumentIds = setOf(requireNotNull(queryCase.expectedFileName)),
                    rankedDocumentIdsByRun = observations.filter { it.caseId == queryCase.caseId }
                        .sortedBy { it.runIndex }
                        .map { it.rankedFileNames },
                    limit = QUALITY_LIMIT,
                )
            }
            val quality = KnowledgeSearchQualityPolicy.evaluate(qualityCases)
            val recallAt1 = qualityCases.count { result ->
                result.rankedDocumentIdsByRun.first().firstOrNull() in result.relevantDocumentIds
            }.toDouble() / qualityCases.size
            val rankingGatePassed = recallAt1 >= MINIMUM_RECALL_AT_1 &&
                quality.meanRecallAtK >= MINIMUM_RECALL_AT_5 &&
                quality.meanReciprocalRank >= MINIMUM_MRR &&
                quality.stableRankingRate >= MINIMUM_RANKING_STABLE_RATE
            val vectorStats = database.vectorStats(CALIBRATION_PROVIDER_ID, embeddingModel)
            val memoryAfterSearch = memorySnapshot()
            val queryMillis = observations.map { it.queryMillis }
            val passed = holdout.passed && rankingGatePassed

            // long: 汇总只评价预先冻结的 Stage 82 门禁和预注册质量标准，不计算或输出 holdout 自身的最佳候选阈值。
            val metrics = JSONObject()
                .put("providerId", CALIBRATION_PROVIDER_ID)
                .put("model", embeddingModel)
                .put("gateVersion", FROZEN_GATE_VERSION)
                .put("calibrationDatasetVersion", CALIBRATION_DATASET_VERSION)
                .put("holdoutDatasetVersion", HOLDOUT_DATASET_VERSION)
                .put("minimumTopScore", FROZEN_MINIMUM_TOP_SCORE)
                .put("minimumScoreMargin", FROZEN_MINIMUM_SCORE_MARGIN)
                .put("documents", HOLDOUT_CORPUS.size)
                .put("casesPerBucket", HOLDOUT_CASES.size / KnowledgeRelevanceLabel.entries.size)
                .put("runsPerCase", HOLDOUT_QUERY_RUNS)
                .put("observations", observations.size)
                .put("indexMillis", indexMillis)
                .put("queryMedianMillis", queryMillis.percentile(0.50))
                .put("queryP95Millis", queryMillis.percentile(0.95))
                .put("candidateCount", observations.map { it.candidateCount }.distinct().single())
                .put("vectorRows", vectorStats.rowCount)
                .put("dimensions", vectorStats.minDimensions)
                .put("vectorBytes", vectorStats.vectorBytes)
                .put("positiveAcceptanceRate", holdout.positiveAcceptanceRate)
                .put("nearNegativeRejectionRate", holdout.nearNegativeRejectionRate)
                .put("farNegativeRejectionRate", holdout.farNegativeRejectionRate)
                .put("decisionStableRate", holdout.decisionStableRate)
                .put("recallAt1", recallAt1)
                .put("recallAt5", quality.meanRecallAtK)
                .put("mrr", quality.meanReciprocalRank)
                .put("rankingStableRate", quality.stableRankingRate)
                .put("holdoutGatePassed", holdout.passed)
                .put("rankingGatePassed", rankingGatePassed)
                .put("passed", passed)
                .put("pssBeforeKb", memoryBefore.pssKb)
                .put("pssAfterIndexKb", memoryAfterIndex.pssKb)
                .put("pssAfterSearchKb", memoryAfterSearch.pssKb)
                .put("javaHeapBeforeBytes", memoryBefore.javaHeapBytes)
                .put("javaHeapAfterIndexBytes", memoryAfterIndex.javaHeapBytes)
                .put("javaHeapAfterSearchBytes", memoryAfterSearch.javaHeapBytes)
            Log.i(HOLDOUT_METRICS_TAG, metrics.toString())
            println("$HOLDOUT_METRICS_TAG $metrics")

            assertEquals(HOLDOUT_CASES.size * HOLDOUT_QUERY_RUNS, observations.size)
            assertEquals(KnowledgeRelevanceLabel.entries.toSet(), observations.map { it.label }.toSet())
            assertEquals(HOLDOUT_CORPUS.size.toLong(), vectorStats.rowCount)
            assertEquals(vectorStats.minDimensions, vectorStats.maxDimensions)
            assertTrue(vectorStats.minDimensions > 0)
            assertEquals(
                vectorStats.rowCount * vectorStats.minDimensions * Float.SIZE_BYTES,
                vectorStats.vectorBytes,
            )
            assertTrue("独立 holdout 未通过冻结相关性门禁", holdout.passed)
            assertTrue("独立 holdout 未通过预注册排序质量门禁", rankingGatePassed)
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

    private fun CalibrationObservation.toJson(): JSONObject = JSONObject()
        .put("caseId", caseId)
        .put("label", label.name)
        .put("runIndex", runIndex)
        .put("expectedFileName", expectedFileName ?: JSONObject.NULL)
        .put("rankedFileNames", JSONArray(rankedFileNames))
        .put("lexicalHitCount", lexicalHitCount)
        .put("candidateCount", candidateCount)
        .put("queryMillis", queryMillis)
        .put("topScore", topScore)
        .put("secondScore", secondScore)
        .put("margin", margin)

    private fun KnowledgeRelevanceCalibrationReport.toBucketJsonArray(): JSONArray =
        JSONArray().also { array ->
            KnowledgeRelevanceLabel.entries.forEach { label ->
                val bucket = bucket(label)
                array.put(
                    JSONObject()
                        .put("label", label.name)
                        .put("sampleCount", bucket.sampleCount)
                        .put("uniqueCaseCount", bucket.uniqueCaseCount)
                        .put("topScoreP05", bucket.topScore.p05)
                        .put("topScoreP50", bucket.topScore.p50)
                        .put("topScoreP95", bucket.topScore.p95)
                        .put("marginP05", bucket.scoreMargin.p05)
                        .put("marginP50", bucket.scoreMargin.p50)
                        .put("marginP95", bucket.scoreMargin.p95),
                )
            }
        }

    private fun KnowledgeRelevanceCandidateGate.toJson(): JSONObject =
        JSONObject()
            .put("minimumTopScore", minimumTopScore)
            .put("minimumScoreMargin", minimumScoreMargin)
            .put("positiveAcceptanceRate", positiveAcceptanceRate)
            .put("nearNegativeRejectionRate", nearNegativeRejectionRate)
            .put("farNegativeRejectionRate", farNegativeRejectionRate)
            .put("balancedAccuracy", balancedAccuracy)

    private data class CorpusEntry(
        val fileName: String,
        val text: String,
    )

    private data class QueryCase(
        val caseId: String,
        val query: String,
        val relevantFileName: String,
    )

    private data class CalibrationQueryCase(
        val caseId: String,
        val label: KnowledgeRelevanceLabel,
        val query: String,
        val expectedFileName: String? = null,
    )

    private data class CalibrationObservation(
        val caseId: String,
        val label: KnowledgeRelevanceLabel,
        val runIndex: Int,
        val expectedFileName: String?,
        val rankedFileNames: List<String>,
        val lexicalHitCount: Int,
        val candidateCount: Int,
        val queryMillis: Long,
        val topScore: Double,
        val secondScore: Double,
        val margin: Double,
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
        const val CALIBRATION_PROVIDER_ID = "real-embedding-relevance-calibration"
        const val METRICS_TAG = "XIAOLING_STAGE80_METRICS"
        const val CALIBRATION_CASE_TAG = "XIAOLING_STAGE82_CASE"
        const val CALIBRATION_METRICS_TAG = "XIAOLING_STAGE82_SUMMARY"
        const val HOLDOUT_CASE_TAG = "XIAOLING_STAGE83_CASE"
        const val HOLDOUT_METRICS_TAG = "XIAOLING_STAGE83_SUMMARY"
        const val QUALITY_LIMIT = 5
        const val QUERY_RUNS = 2
        const val CALIBRATION_QUERY_RUNS = 2
        const val HOLDOUT_QUERY_RUNS = 2
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val UNRELATED_QUERY = "How do coral reefs coordinate spawning under moonlight?"
        const val FROZEN_MODEL = "Qwen/Qwen3-Embedding-0.6B"
        const val FROZEN_GATE_VERSION = "stage82-qwen-v1"
        const val CALIBRATION_DATASET_VERSION = "stage82-calibration-v1"
        const val HOLDOUT_DATASET_VERSION = "stage83-holdout-v1"
        const val FROZEN_MINIMUM_TOP_SCORE = 0.6735426515268672
        const val FROZEN_MINIMUM_SCORE_MARGIN = 0.0178535973263384
        const val MINIMUM_POSITIVE_ACCEPTANCE_RATE = 0.90
        const val MINIMUM_NEAR_NEGATIVE_REJECTION_RATE = 0.80
        const val MINIMUM_FAR_NEGATIVE_REJECTION_RATE = 0.90
        const val MINIMUM_DECISION_STABLE_RATE = 1.0
        const val MINIMUM_RECALL_AT_1 = 0.90
        const val MINIMUM_RECALL_AT_5 = 1.0
        const val MINIMUM_MRR = 0.90
        const val MINIMUM_RANKING_STABLE_RATE = 1.0

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

        // long: 每个原始主题增加一篇同主题干扰文档，校准查询必须在近邻语义中辨别具体事实，避免十篇孤立主题高估效果。
        val CALIBRATION_CORPUS = CORPUS + listOf(
            CorpusEntry("11-深度工作.md", "深度工作可以关闭通知并预留九十分钟不被打断的区间。开始前写下单一目标，结束后统一处理消息，而不是按固定二十五分钟轮换。"),
            CorpusEntry("12-干酵母面包.md", "使用即发干酵母时可以直接混入面粉，无需每天喂养。面团完成揉制后等待一次发酵，整形后进行最后醒发再烘焙。"),
            CorpusEntry("13-午睡恢复.md", "短暂午睡适合安排在下午较早时段，通常控制在二十分钟左右。醒来后接触自然光并稍作活动，避免影响夜间睡意。"),
            CorpusEntry("14-骑行清洁.md", "雨天骑行结束后先擦干车架和链条，再补充适量链条油。清理刹车边和泥沙能减少磨损，这些维护适合回家后进行。"),
            CorpusEntry("15-缓存过期.md", "服务器可以通过缓存有效期告诉浏览器在一段时间内直接复用本地响应。有效期结束后再联系服务器，适合更新周期明确的静态资源。"),
            CorpusEntry("16-植物施肥.md", "室内植物生长期可以按肥料说明稀释后施用，并避开刚换盆或休眠阶段。过量肥料会积累盐分，需要降低频率而不是增加浇水。"),
            CorpusEntry("17-管弦乐排练.md", "管弦乐团排练由指挥先处理各声部进入位置和速度变化，再合并木管、铜管与弦乐。大编制排练重点是指挥手势和整体动态层次。"),
            CorpusEntry("18-旅行行李.md", "出发前按天气和活动准备分层衣物、证件、充电器与常用药。随身行李保留一天必需品，托运行李外侧不要悬挂贵重物品。"),
            CorpusEntry("19-计划储蓄.md", "预计一年内发生的保险、学费或家电支出可以建立专项储蓄。按目标金额和剩余月份定期存入，避免把可预见费用当作紧急事件。"),
            CorpusEntry("20-照片整理.md", "照片整理可以按年份、事件和地点建立相册，删除重复截图并补充关键词。统一命名有助于搜索，但不等同于建立异地副本。"),
        )

        val QUERY_CASES = listOf(
            QueryCase("focus-rhythm", "What routine divides concentrated work into short timed intervals followed by breaks?", "01-专注节奏.md"),
            QueryCase("natural-starter", "How should a starter be maintained before baking naturally leavened bread?", "02-天然酵种.md"),
            QueryCase("sleep-routine", "Which evening habits make it easier to fall asleep at a consistent time?", "03-睡眠习惯.md"),
            QueryCase("bicycle-check", "What should a rider inspect and adjust before leaving on a bicycle?", "04-骑行检查.md"),
            QueryCase("response-reuse", "How can a browser avoid downloading a resource again when it has not changed?", "05-缓存验证.md"),
        )

        val CALIBRATION_CASES = listOf(
            CalibrationQueryCase(
                caseId = "positive-focus",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "How does the Pomodoro method structure focused work and breaks?",
                expectedFileName = "01-专注节奏.md",
            ),
            CalibrationQueryCase(
                caseId = "positive-starter",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "How should a sourdough starter be fed and assessed before baking?",
                expectedFileName = "02-天然酵种.md",
            ),
            CalibrationQueryCase(
                caseId = "positive-sleep",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "What evening habits support regular sleep?",
                expectedFileName = "03-睡眠习惯.md",
            ),
            CalibrationQueryCase(
                caseId = "positive-bicycle",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "What tire, brake, chain, and quick-release checks belong before a bicycle ride?",
                expectedFileName = "04-骑行检查.md",
            ),
            CalibrationQueryCase(
                caseId = "positive-cache-validator",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "How does a browser revalidate an unchanged response without downloading the full resource?",
                expectedFileName = "05-缓存验证.md",
            ),
            CalibrationQueryCase(
                caseId = "positive-watering",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "How should soil moisture be checked before thoroughly watering an indoor plant?",
                expectedFileName = "06-植物浇水.md",
            ),
            CalibrationQueryCase(
                caseId = "positive-quartet",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "How can a string quartet rehearse bowing, intonation, and inner-voice balance?",
                expectedFileName = "07-室内乐排练.md",
            ),
            CalibrationQueryCase(
                caseId = "positive-itinerary",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "How should dates, budget, transport, lodging, and daily pacing be planned for an intercity trip?",
                expectedFileName = "08-旅行准备.md",
            ),
            CalibrationQueryCase(
                caseId = "positive-emergency-fund",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "How should a household build liquid savings for job loss, medical surprises, or urgent repairs?",
                expectedFileName = "09-应急储备.md",
            ),
            CalibrationQueryCase(
                caseId = "positive-photo-backup",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "What copy locations and restore checks make a photo backup resilient before changing phones?",
                expectedFileName = "10-照片备份.md",
            ),
            CalibrationQueryCase(
                caseId = "near-focus-app",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "Which Pomodoro timer app supports team leaderboards and shared rewards?",
            ),
            CalibrationQueryCase(
                caseId = "near-starter-microbes",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "Which bacterial species dominate a sourdough starter at different temperatures?",
            ),
            CalibrationQueryCase(
                caseId = "near-sleep-melatonin",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "What melatonin dosage should change between eastbound and westbound travel?",
            ),
            CalibrationQueryCase(
                caseId = "near-bicycle-compound",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "Which bicycle tire compound has the lowest rolling resistance on wet roads?",
            ),
            CalibrationQueryCase(
                caseId = "near-cache-cdn",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "How should a global CDN invalidate cached objects across every region?",
            ),
            CalibrationQueryCase(
                caseId = "near-plant-fertilizer",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "Which fertilizer NPK ratio produces the most orchid flowers?",
            ),
            CalibrationQueryCase(
                caseId = "near-quartet-royalty",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "How should recording royalties be divided among string quartet musicians?",
            ),
            CalibrationQueryCase(
                caseId = "near-travel-visa",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "Which visa category permits remote employment during long-term overseas travel?",
            ),
            CalibrationQueryCase(
                caseId = "near-emergency-tax",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "How are interest earnings from an emergency savings account taxed?",
            ),
            CalibrationQueryCase(
                caseId = "near-photo-profile",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "Which RAW color profile best matches a particular camera sensor?",
            ),
            CalibrationQueryCase(
                caseId = "far-coral",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = UNRELATED_QUERY,
            ),
            CalibrationQueryCase(
                caseId = "far-spacecraft",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "What orbital maneuvers place a spacecraft around Mars?",
            ),
            CalibrationQueryCase(
                caseId = "far-volcano",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "How do seismic waves reveal magma movement beneath a volcano?",
            ),
            CalibrationQueryCase(
                caseId = "far-roman-law",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "How did Roman courts distinguish contracts from property claims?",
            ),
            CalibrationQueryCase(
                caseId = "far-protein",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "How do chaperone proteins prevent aggregation during cellular folding?",
            ),
            CalibrationQueryCase(
                caseId = "far-whale",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "Which ocean currents guide seasonal whale migration routes?",
            ),
            CalibrationQueryCase(
                caseId = "far-quantum",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "How does a surface code detect errors in a quantum computer?",
            ),
            CalibrationQueryCase(
                caseId = "far-ceramic",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "What kiln atmosphere creates a copper-red ceramic glaze?",
            ),
            CalibrationQueryCase(
                caseId = "far-manuscript",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "How were pigments prepared for illuminated medieval manuscripts?",
            ),
            CalibrationQueryCase(
                caseId = "far-radio",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "How do radio telescopes combine signals with very long baseline interferometry?",
            ),
        )

        // long: holdout 主题与 Stage 82 校准集完全分离，只保留相同的成对干扰结构，避免用训练主题回测冻结阈值。
        val HOLDOUT_CORPUS = listOf(
            CorpusEntry("h01-手冲咖啡.md", "手冲咖啡先用约两倍粉重的热水均匀润湿咖啡粉，等待三十秒排气。之后以缓慢画圈方式分段注水，保持稳定水位并避免直接冲击滤纸边缘。"),
            CorpusEntry("h02-家庭堆肥.md", "家庭堆肥可交替加入干燥落叶等棕色材料和果蔬残渣等绿色材料。混合物保持拧干海绵般湿润，定期翻动增加空气，出现异味时补充棕色材料。"),
            CorpusEntry("h03-钢琴练习.md", "练习新钢琴段落时先分手并使用慢速节拍器，确保指法和节奏稳定。连续准确后再小幅提高速度，合手时从较低速度重新开始。"),
            CorpusEntry("h04-徒步导航.md", "徒步前在纸质地图标出路线、转折点和撤退方向，并用指南针核对方位。途中在明显地标处确认位置，不能只依赖手机电量和网络信号。"),
            CorpusEntry("h05-铸铁锅养护.md", "铸铁锅清洗后要立即擦干并用小火蒸发残余水分。锅面温热时涂一层很薄的食用油再擦去多余部分，可以减少生锈并维护油膜。"),
            CorpusEntry("h06-间隔复习.md", "间隔复习根据回忆结果逐渐拉长复习间隔。答错的内容缩短下次间隔，稳定答对后再延长，并在每次查看答案前主动回忆。"),
            CorpusEntry("h07-鱼缸换水.md", "鱼缸维护采用定期部分换水而不是一次全部更换。新水先除氯并调整到接近原缸温度，换水时轻柔清理底部杂物，避免破坏过滤系统。"),
            CorpusEntry("h08-冬季车辆检查.md", "入冬前检查蓄电池状态、防冻液冰点、轮胎气压和雨刷。低温会降低胎压和电池能力，玻璃清洗液也应选择适合当地最低温度的型号。"),
            CorpusEntry("h09-会议行动项.md", "会议记录应把讨论结论转换为明确行动项，每项写清负责人和截止日期。会后尽快发送摘要，并在下次会议开始时核对未完成事项。"),
            CorpusEntry("h10-缝纫机张力.md", "缝纫前用同材质边角料测试上线和梭芯线张力。线结应落在布料中间；上线浮在背面时逐步提高上线张力，每次只调整一小格。"),
            CorpusEntry("h11-意式浓缩.md", "制作意式浓缩需要细研磨并均匀布粉压实，让高压热水在较短时间穿过粉饼。流速过快时调细研磨，过慢时调粗并重新测试。"),
            CorpusEntry("h12-家庭回收.md", "家庭回收物应按当地规则分类，容器先倒空并简单清洁，纸张保持干燥。油污严重的包装和不被接收的复合材料不能混入回收桶。"),
            CorpusEntry("h13-钢琴调律.md", "钢琴音高会受温湿度变化影响，长期保持稳定环境有助于减少漂移。明显走音或搬运后应由专业调律师检查，不建议自行大幅旋转弦轴。"),
            CorpusEntry("h14-帐篷搭建.md", "帐篷应搭在平整且不积水的位置，先展开地布再连接帐杆。固定四角后均匀拉紧防风绳，入口避开迎风方向并远离枯枝。"),
            CorpusEntry("h15-不锈钢锅清洁.md", "不锈钢锅出现焦痕时先加入温水浸泡，再用木铲松动残渣。顽固污渍可配合温和清洁剂顺纹擦洗，避免使用会留下深划痕的硬质工具。"),
            CorpusEntry("h16-跟读练习.md", "语言跟读先选择短音频，听清语调和停顿后紧跟原声模仿。录下自己的声音与原音比较，重点修正重音、连读和节奏，而不是只背单词。"),
            CorpusEntry("h17-观赏鱼喂食.md", "观赏鱼每天少量喂食，以几分钟内吃完为准。残饵应及时移除，水温下降或鱼只活动减弱时适当减少频率，避免过量污染水质。"),
            CorpusEntry("h18-电动车充电.md", "日常电动车充电可结合出发时间预约完成，避免车辆长时间停在极高电量。低温环境下提前预热电池，有助于充电速度和出发后的能耗表现。"),
            CorpusEntry("h19-邮件收件箱.md", "处理收件箱时先删除无用通知，再把需要行动的邮件转成任务。两分钟内可完成的直接回复，等待他人处理的邮件单独标记并定期复查。"),
            CorpusEntry("h20-手工刺绣.md", "手工刺绣先把布料均匀固定在绣绷上，保持平整但不要过度拉伸。针脚长度尽量一致，换线时在线背面固定线头，避免正面出现结块。"),
        )

        val HOLDOUT_CASES = listOf(
            CalibrationQueryCase(
                caseId = "holdout-positive-pour-over",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "How should coffee grounds be bloomed and then poured over in controlled circles?",
                expectedFileName = "h01-手冲咖啡.md",
            ),
            CalibrationQueryCase(
                caseId = "holdout-positive-compost",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "How should brown and green materials, moisture, and aeration be balanced in home compost?",
                expectedFileName = "h02-家庭堆肥.md",
            ),
            CalibrationQueryCase(
                caseId = "holdout-positive-piano",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "How should separate-hand piano practice use a metronome before increasing tempo?",
                expectedFileName = "h03-钢琴练习.md",
            ),
            CalibrationQueryCase(
                caseId = "holdout-positive-navigation",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "How can a hiker use a paper map, compass bearings, and landmarks without relying on a phone?",
                expectedFileName = "h04-徒步导航.md",
            ),
            CalibrationQueryCase(
                caseId = "holdout-positive-cast-iron",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "How should a cast iron pan be dried with heat and coated with a very thin layer of oil?",
                expectedFileName = "h05-铸铁锅养护.md",
            ),
            CalibrationQueryCase(
                caseId = "holdout-positive-spaced-review",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "How should recall success and failure change the next spaced-review interval?",
                expectedFileName = "h06-间隔复习.md",
            ),
            CalibrationQueryCase(
                caseId = "holdout-positive-aquarium",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "How should partial aquarium water changes handle chlorine, temperature, and filter stability?",
                expectedFileName = "h07-鱼缸换水.md",
            ),
            CalibrationQueryCase(
                caseId = "holdout-positive-winter-car",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "What battery, coolant, tire-pressure, wiper, and washer-fluid checks belong before winter?",
                expectedFileName = "h08-冬季车辆检查.md",
            ),
            CalibrationQueryCase(
                caseId = "holdout-positive-actions",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "How should meeting decisions become action items with owners, deadlines, and follow-up?",
                expectedFileName = "h09-会议行动项.md",
            ),
            CalibrationQueryCase(
                caseId = "holdout-positive-thread-tension",
                label = KnowledgeRelevanceLabel.POSITIVE,
                query = "How can scrap fabric reveal whether sewing-machine upper-thread tension needs a small adjustment?",
                expectedFileName = "h10-缝纫机张力.md",
            ),
            CalibrationQueryCase(
                caseId = "holdout-near-coffee-minerals",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "Which dissolved mineral concentrations create the best water chemistry for specialty coffee?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-near-compost-methane",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "How much methane does a household compost pile emit at different outdoor temperatures?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-near-piano-exam",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "How do conservatory examiners score interpretation in an advanced piano jury?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-near-hiking-permit",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "Which permit and quota rules apply to overnight hiking in a protected park?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-near-cast-iron-alloy",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "What carbon and silicon percentages define the alloy used in cast iron cookware?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-near-review-subscription",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "Which spaced-repetition application offers the cheapest family subscription?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-near-fish-genetics",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "Which inherited traits determine color patterns in aquarium fish hybrids?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-near-winter-tire-law",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "What legal tread depth and studded-tire dates apply to winter driving?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-near-meeting-consent",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "Which consent laws govern audio recording during a workplace meeting?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-near-sewing-patent",
                label = KnowledgeRelevanceLabel.NEAR_NEGATIVE,
                query = "Which early patent introduced automatic thread tension in sewing machines?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-far-exoplanet",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "How can transit spectroscopy identify molecules in an exoplanet atmosphere?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-far-glacier",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "What climate history can oxygen isotopes reveal in deep glacier ice cores?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-far-coins",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "How do numismatists identify the mint and reign of an ancient silver coin?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-far-antibody",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "How does affinity maturation improve antibodies inside a germinal center?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-far-vent",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "Which microbes obtain energy from chemicals at deep-sea hydrothermal vents?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-far-lensing",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "How does gravitational lensing magnify a galaxy behind a massive cluster?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-far-bird",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "How might migratory birds sense Earth's magnetic field during navigation?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-far-hieroglyph",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "How did bilingual inscriptions help scholars decipher ancient hieroglyphs?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-far-crystal",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "How do vacancies and dislocations alter the strength of a crystal lattice?",
            ),
            CalibrationQueryCase(
                caseId = "holdout-far-cyclone",
                label = KnowledgeRelevanceLabel.FAR_NEGATIVE,
                query = "What ocean and atmospheric conditions intensify a tropical cyclone eyewall?",
            ),
        )
    }
}
