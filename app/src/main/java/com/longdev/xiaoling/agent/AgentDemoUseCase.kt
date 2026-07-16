package com.longdev.xiaoling.agent

import android.content.Context
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.storage.RoomAgentRunRepository

class AgentDemoUseCase(
    context: Context,
    private val client: OpenAiCompatibleClient,
) {
    private val ledger = RoomAgentRunRepository(context)
    private val toolRegistry = FakeToolRegistry()
    private val approvalGate = AutoApprovalGate()

    suspend fun run(
        conversationId: String,
        userMessageId: String,
        goal: String,
        config: ProviderRequestConfig,
    ): AgentRunSummary {
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = toolRegistry,
            llm = OpenAiAgentLlm(client, config),
            approvalGate = approvalGate,
        )
        return runtime.runDemo(
            conversationId = conversationId,
            userMessageId = userMessageId,
            goal = goal,
        )
    }
}
