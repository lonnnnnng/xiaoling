package com.longdev.xiaoling.agent

object AgentLlmRetryDispositionPolicy {
    fun classify(
        phase: AgentLlmPhase,
        kind: AgentLlmFailureKind,
        completedToolCount: Int,
    ): AgentLlmRetryDisposition {
        require(completedToolCount >= 0) { "已完成工具数量不能小于 0" }
        if (phase == AgentLlmPhase.SUMMARIZE) {
            // long: 工具结果已经完成并验证时，总结请求只影响展示文案；失败必须明确落为本地兜底完成，不能把已验证事实改判失败。
            return AgentLlmRetryDisposition.LOCAL_FALLBACK_COMPLETED
        }
        if (kind in configurationKinds) {
            // long: 鉴权、地址和模型选择错误无法通过重复发送修复；任务中心保留失败 Run，提示先修 Provider/模型配置。
            return AgentLlmRetryDisposition.CONFIGURATION_REQUIRED
        }
        return if (completedToolCount == 0 && kind in transientKinds) {
            // long: 规划尚未进入任何工具副作用边界时，瞬态网络失败可以直接创建一个新的独立 Run；这不是原地重放旧 Executor。
            AgentLlmRetryDisposition.RETRY_WITHOUT_CONFIRMATION
        } else {
            // long: 规划失败前已经有工具事实，或错误无法证明是瞬态网络问题；关联新 Run 仍需用户确认，旧 Run 和 Tool Ledger 保持只读。
            AgentLlmRetryDisposition.RETRY_WITH_CONFIRMATION
        }
    }

    private val configurationKinds = setOf(
        AgentLlmFailureKind.AUTHENTICATION,
        AgentLlmFailureKind.REQUEST_URL,
        AgentLlmFailureKind.MODEL,
    )

    private val transientKinds = setOf(
        AgentLlmFailureKind.RATE_LIMIT,
        AgentLlmFailureKind.TIMEOUT,
        AgentLlmFailureKind.DNS,
        AgentLlmFailureKind.TLS,
        AgentLlmFailureKind.CONNECTION,
    )
}
