package com.longdev.xiaoling.agent

import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.network.RequestMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class M1ProviderSmokeInstrumentedTest {
    @Test fun configuredProviderSupportsOrdinaryAndNormallyTerminatedStream() = runBlocking {
        val config = androidLiveProvider(301).copy(maxTokens = 300, httpDebugLoggingEnabled = false)
        val client = OpenAiCompatibleClient()
        val messages = listOf(RequestMessage("user", "这是客户端连通性测试。仅回复：连通正常"))
        val ordinary = client.sendMessage(config.copy(streamingEnabled = false), messages)
        assertTrue(ordinary.responseText.isNotBlank())
        var deltas = 0
        val streaming = client.sendMessage(config.copy(streamingEnabled = true), messages) { deltas++ }
        assertTrue(streaming.responseText.isNotBlank())
        assertTrue("真实流必须具有正常终态", streaming.actionCompletionConfirmed)
        assertTrue(deltas > 0)
        // long: 证据只记录完成事实与计数，不记录上游密钥、完整配置或可能包含用户内容的模型正文。
        androidLiveStatus("M1_PROVIDER_SMOKE ordinaryCompleted=true streamCompleted=true deltas=$deltas model=${config.model}")
    }
}
