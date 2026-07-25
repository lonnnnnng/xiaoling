package com.longdev.xiaoling.knowledge

import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ModelResponseResult
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.network.ApiFailure
import com.longdev.xiaoling.network.FailureKind
import com.longdev.xiaoling.network.RequestMessage
import kotlinx.coroutines.CancellationException

fun interface KnowledgeAnswerabilityCompletionClient {
    suspend fun complete(
        config: ProviderRequestConfig,
        messages: List<RequestMessage>,
    ): ModelResponseResult
}

/**
 * long: 生产 Judge 复用第 92 阶段已校准的固定 Prompt 与生成参数；协议版本变化必须重新校准，不能在普通功能改动中静默漂移。
 */
object KnowledgeAnswerabilityJudgeProtocol {
    const val PROMPT_VERSION = "stage92-answerability-json-v1"
    const val MAX_TOKENS = 220

    val systemPrompt: String = """
        You are a strict evidence judge. Use only the candidate document, never outside knowledge.
        Decide whether the candidate directly answers every material part of the question.
        Return exactly one JSON object with exactly these keys:
        {"verdict":"ANSWERED|PARTIALLY_ANSWERED|NOT_ANSWERED|UNKNOWN","confidence":0.0,"evidence_quotes":["verbatim substring"],"contradiction_detected":false,"reason_code":"DIRECT_EVIDENCE"}
        ANSWERED requires enough direct information for the whole question and at least one verbatim quote.
        PARTIALLY_ANSWERED means only some requested facts are present.
        NOT_ANSWERED means the topic is related but the requested fact is absent.
        UNKNOWN is only for an unreadable or ambiguous candidate.
        evidence_quotes must be exact substrings copied from the candidate document; use [] for NOT_ANSWERED or UNKNOWN.
        reason_code must be uppercase letters, digits, or underscores only.
        Do not use markdown, comments, or any text before or after the JSON object.
    """.trimIndent()

    fun messages(question: String, candidateText: String): List<RequestMessage> = listOf(
        RequestMessage(role = "system", content = systemPrompt),
        RequestMessage(
            role = "user",
            content = "QUESTION:\n$question\n\nCANDIDATE DOCUMENT:\n$candidateText",
        ),
    )
}

/**
 * long: Adapter 只执行一次真实 Provider 请求并严格解码；重试统一留给 shadow 协调器，避免一次 attempt 在网络层被偷偷放大。
 */
class OpenAiKnowledgeAnswerabilityJudge(
    private val providerConfig: ProviderRequestConfig,
    private val completionClient: KnowledgeAnswerabilityCompletionClient = defaultCompletionClient(),
) : KnowledgeAnswerabilityJudgePort {
    override suspend fun judge(request: KnowledgeAnswerabilityJudgeRequest): KnowledgeAnswerabilityJudgeResponse {
        val config = providerConfig.copy(
            apiMode = ApiMode.RESPONSES,
            streamingEnabled = false,
            reasoningSummaryEnabled = false,
            temperature = 0.0,
            maxTokens = KnowledgeAnswerabilityJudgeProtocol.MAX_TOKENS,
            topP = 1.0,
            // long: Judge 输入含用户问题和知识候选；即使 Debug 全局开启 HTTP 日志，这条请求也必须逐请求关闭正文记录。
            httpDebugLoggingEnabled = false,
        )
        val actualIdentity = try {
            // long: 在网络请求前冻结真实配置身份；响应不能复制 request.expectedIdentity，否则 Provider 漂移会被伪装成已通过门禁。
            KnowledgeAnswerabilityJudgeIdentityFactory.fromConfig(config)
        } catch (error: IllegalArgumentException) {
            throw KnowledgeAnswerabilityJudgeFailure(KnowledgeAnswerabilityJudgeFailureKind.IDENTITY, error)
        }
        val result = try {
            completionClient.complete(
                config = config,
                messages = KnowledgeAnswerabilityJudgeProtocol.messages(
                    question = request.question,
                    candidateText = request.candidateText,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (failure: ApiFailure) {
            throw KnowledgeAnswerabilityJudgeFailure(failure.toJudgeFailureKind(), failure)
        }
        val output = try {
            KnowledgeAnswerabilityResponseCodec.decode(result.responseText)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // long: HTTP 成功但 JSON 不满足固定字段与证据语义时属于可受控重试的协议失败，不能按未知异常静默吞掉。
            throw KnowledgeAnswerabilityJudgeFailure(KnowledgeAnswerabilityJudgeFailureKind.PROTOCOL, error)
        }
        return KnowledgeAnswerabilityJudgeResponse(
            identity = actualIdentity,
            output = output,
        )
    }

    private fun ApiFailure.toJudgeFailureKind(): KnowledgeAnswerabilityJudgeFailureKind {
        // long: 只有可能自行恢复的 5xx、限流和传输故障进入协调器的一次受控重试；认证、请求和身份错误继续请求只会放大成本与延迟。
        if (statusCode != null && statusCode in 500..599) {
            return KnowledgeAnswerabilityJudgeFailureKind.SERVER
        }
        return when (kind) {
            FailureKind.AUTHENTICATION -> KnowledgeAnswerabilityJudgeFailureKind.AUTHENTICATION
            FailureKind.RATE_LIMIT -> KnowledgeAnswerabilityJudgeFailureKind.RATE_LIMIT
            FailureKind.TIMEOUT,
            FailureKind.DNS,
            FailureKind.TLS,
            FailureKind.CONNECTION,
            -> KnowledgeAnswerabilityJudgeFailureKind.TRANSIENT_NETWORK
            FailureKind.RESPONSE -> KnowledgeAnswerabilityJudgeFailureKind.PROTOCOL
            FailureKind.REQUEST_URL -> KnowledgeAnswerabilityJudgeFailureKind.CLIENT_REQUEST
            FailureKind.MODEL -> KnowledgeAnswerabilityJudgeFailureKind.IDENTITY
            FailureKind.UNKNOWN -> if (statusCode != null && statusCode in 400..499) {
                KnowledgeAnswerabilityJudgeFailureKind.CLIENT_REQUEST
            } else {
                KnowledgeAnswerabilityJudgeFailureKind.UNEXPECTED
            }
        }
    }

    private companion object {
        fun defaultCompletionClient(): KnowledgeAnswerabilityCompletionClient {
            val client = OpenAiCompatibleClient()
            return KnowledgeAnswerabilityCompletionClient { config, messages ->
                client.sendMessage(config = config, messages = messages)
            }
        }
    }
}
