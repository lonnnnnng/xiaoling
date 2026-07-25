package com.longdev.xiaoling.knowledge

import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ModelResponseResult
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.RequestMessage
import com.longdev.xiaoling.network.ApiFailure
import com.longdev.xiaoling.network.FailureKind
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiKnowledgeAnswerabilityJudgeTest {
    @Test
    fun malformedSuccessfulResponseMapsToRetryableProtocolFailure() = runTest {
        val judge = OpenAiKnowledgeAnswerabilityJudge(
            providerConfig = ProviderRequestConfig(
                baseUrl = "https://judge.example/v1",
                apiKey = "test-key",
                model = "gpt-test",
                providerId = "logical-answerability-judge-v1",
            ),
            completionClient = KnowledgeAnswerabilityCompletionClient { config, _ ->
                ModelResponseResult(
                    requestUrl = "https://judge.example/v1/responses",
                    model = config.model,
                    latencyMs = 10L,
                    promptBytes = 77,
                    responseText = "not-json",
                )
            },
        )

        val failure = runCatching { judge.judge(request()) }.exceptionOrNull()

        val typedFailure = failure as KnowledgeAnswerabilityJudgeFailure
        assertEquals(KnowledgeAnswerabilityJudgeFailureKind.PROTOCOL, typedFailure.kind)
        assertEquals(10L, typedFailure.telemetry?.latencyMs)
        assertEquals(77L, typedFailure.telemetry?.promptBytes)
    }

    @Test
    fun productionTransportUsesResponsesEndpointAndConfiguredHeaders() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val output = """
                {"verdict":"ANSWERED","confidence":0.91,"evidence_quotes":["三份副本"],"contradiction_detected":false,"reason_code":"DIRECT_EVIDENCE"}
            """.trimIndent()
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    JSONObject().put("output_text", output).toString(),
                ),
            )
            val judge = OpenAiKnowledgeAnswerabilityJudge(
                providerConfig = ProviderRequestConfig(
                    baseUrl = server.url("/v1").toString(),
                    apiKey = "judge-test-key",
                    model = "gpt-test",
                    providerId = "logical-answerability-judge-v1",
                    userAgent = "Judge Client/1.0",
                ),
            )

            val response = judge.judge(request())
            val recorded = server.takeRequest()
            val body = recorded.body.readUtf8()

            assertEquals("/v1/responses", recorded.path)
            assertEquals("Bearer judge-test-key", recorded.getHeader("Authorization"))
            assertEquals("Judge Client/1.0", recorded.getHeader("User-Agent"))
            assertTrue(body.contains("strict evidence judge"))
            assertTrue(body.contains("\"stream\":false"))
            assertEquals(KnowledgeAnswerabilityVerdict.ANSWERED, response.output.verdict)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun httpServerFailureMapsToRetryableJudgeServerFailure() = runTest {
        val judge = OpenAiKnowledgeAnswerabilityJudge(
            providerConfig = ProviderRequestConfig(
                baseUrl = "https://judge.example/v1",
                apiKey = "test-key",
                model = "gpt-test",
                providerId = "logical-answerability-judge-v1",
            ),
            completionClient = KnowledgeAnswerabilityCompletionClient { _, _ ->
                throw ApiFailure(FailureKind.UNKNOWN, "HTTP 503", statusCode = 503)
            },
        )

        val failure = runCatching { judge.judge(request()) }.exceptionOrNull()

        assertEquals(
            KnowledgeAnswerabilityJudgeFailureKind.SERVER,
            (failure as KnowledgeAnswerabilityJudgeFailure).kind,
        )
        assertTrue((failure as KnowledgeAnswerabilityJudgeFailure).telemetry?.latencyMs != null)
        assertTrue((failure as KnowledgeAnswerabilityJudgeFailure).telemetry?.promptBytes ?: 0L > 0L)
    }

    @Test
    fun modelFailureIsNotReportedAsConfigurationIdentityDrift() = runTest {
        val judge = OpenAiKnowledgeAnswerabilityJudge(
            providerConfig = ProviderRequestConfig(
                baseUrl = "https://judge.example/v1",
                apiKey = "test-key",
                model = "gpt-test",
                providerId = "logical-answerability-judge-v1",
            ),
            completionClient = KnowledgeAnswerabilityCompletionClient { _, _ ->
                throw ApiFailure(FailureKind.MODEL, "model unavailable")
            },
        )

        val failure = runCatching { judge.judge(request()) }.exceptionOrNull()

        assertEquals(
            KnowledgeAnswerabilityJudgeFailureKind.MODEL,
            (failure as KnowledgeAnswerabilityJudgeFailure).kind,
        )
    }

    @Test
    fun judgeUsesFrozenProtocolAndReturnsIdentityDerivedFromActualConfig() = runTest {
        var capturedConfig: ProviderRequestConfig? = null
        var capturedMessages: List<RequestMessage>? = null
        val judge = OpenAiKnowledgeAnswerabilityJudge(
            providerConfig = ProviderRequestConfig(
                baseUrl = "https://judge.example/v1/",
                apiKey = "test-key",
                model = "gpt-test",
                providerId = "actual-provider-profile-id",
                apiMode = ApiMode.CHAT_COMPLETIONS,
                streamingEnabled = true,
                reasoningSummaryEnabled = true,
                temperature = 0.7,
                maxTokens = 4_096,
                topP = 0.4,
            ),
            completionClient = KnowledgeAnswerabilityCompletionClient { config, messages ->
                capturedConfig = config
                capturedMessages = messages
                ModelResponseResult(
                    requestUrl = "https://judge.example/v1/responses",
                    model = config.model,
                    latencyMs = 10L,
                    promptBytes = 123,
                    usage = com.longdev.xiaoling.model.ModelTokenUsage(
                        inputTokens = 40L,
                        outputTokens = 12L,
                        totalTokens = 52L,
                    ),
                    responseText = """
                        {"verdict":"ANSWERED","confidence":0.92,"evidence_quotes":["三份副本"],"contradiction_detected":false,"reason_code":"DIRECT_EVIDENCE"}
                    """.trimIndent(),
                )
            },
        )

        val response = judge.judge(
            KnowledgeAnswerabilityJudgeRequest(
                sourceRunId = "run-answerability",
                persistedMessageId = "message-answerability",
                question = "备份应保留几份副本？",
                candidateText = "备份应保留三份副本。",
                references = listOf(reference()),
                expectedIdentity = KnowledgeAnswerabilityJudgeIdentity(
                    providerId = "must-not-be-copied",
                    model = "other-model",
                    configurationFingerprint = "other-fingerprint",
                    promptVersion = "other-prompt",
                ),
            ),
        )

        val config = requireNotNull(capturedConfig)
        assertEquals(ApiMode.RESPONSES, config.apiMode)
        assertFalse(config.streamingEnabled)
        assertFalse(config.reasoningSummaryEnabled)
        assertFalse(config.httpDebugLoggingEnabled)
        assertEquals(0.0, config.temperature, 0.0)
        assertEquals(220, config.maxTokens)
        assertEquals(1.0, config.topP, 0.0)
        assertEquals(2, capturedMessages?.size)
        assertEquals("system", capturedMessages?.first()?.role)
        assertTrue(capturedMessages?.first()?.content.orEmpty().contains("strict evidence judge"))
        assertEquals(
            "QUESTION:\n备份应保留几份副本？\n\nCANDIDATE DOCUMENT:\n备份应保留三份副本。",
            capturedMessages?.last()?.content,
        )
        assertEquals(
            KnowledgeAnswerabilityJudgeIdentity(
                providerId = "actual-provider-profile-id",
                model = "gpt-test",
                configurationFingerprint = "eba5c4c6002cf109a2c7f5633b356895e2b495ecc0d6ace95f23a1c86033910a",
                promptVersion = "stage92-answerability-json-v1",
            ),
            response.identity,
        )
        assertEquals(KnowledgeAnswerabilityVerdict.ANSWERED, response.output.verdict)
        assertEquals(listOf("三份副本"), response.output.evidenceQuotes)
        assertEquals(10L, response.telemetry?.latencyMs)
        assertEquals(123L, response.telemetry?.promptBytes)
        assertEquals(52L, response.telemetry?.totalTokens)
    }

    @Test
    fun missingProviderIdentityFailsBeforeNetworkRequest() = runTest {
        var completionCalled = false
        val judge = OpenAiKnowledgeAnswerabilityJudge(
            providerConfig = ProviderRequestConfig(
                baseUrl = "https://judge.example/v1",
                apiKey = "test-key",
                model = "gpt-test",
            ),
            completionClient = KnowledgeAnswerabilityCompletionClient { _, _ ->
                completionCalled = true
                error("身份缺失时不应调用 Provider")
            },
        )

        val failure = runCatching { judge.judge(request()) }.exceptionOrNull()

        assertFalse(completionCalled)
        assertEquals(
            KnowledgeAnswerabilityJudgeFailureKind.IDENTITY,
            (failure as KnowledgeAnswerabilityJudgeFailure).kind,
        )
    }

    private fun reference() = KnowledgeReference(
        retrievalId = "retrieval-answerability",
        documentId = "document-backup",
        documentName = "备份规范.md",
        documentRevision = 1,
        chunkId = "chunk-backup",
        chunkSequence = 0,
        startOffset = 0,
        endOffset = 20,
    )

    private fun request() = KnowledgeAnswerabilityJudgeRequest(
        sourceRunId = "run-answerability",
        persistedMessageId = "message-answerability",
        question = "备份应保留几份副本？",
        candidateText = "备份应保留三份副本。",
        references = listOf(reference()),
        expectedIdentity = KnowledgeAnswerabilityJudgeIdentity(
            providerId = "expected",
            model = "gpt-test",
            configurationFingerprint = "fingerprint",
            promptVersion = "prompt",
        ),
    )
}
