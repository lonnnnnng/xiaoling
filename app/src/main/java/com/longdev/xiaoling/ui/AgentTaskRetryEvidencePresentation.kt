package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentTaskRetryEvidenceCode

internal data class AgentTaskRetryEvidencePresentation(
    val code: AgentTaskRetryEvidenceCode,
    val label: String,
    val detail: String,
    val suggestedAction: String,
)

internal fun presentAgentTaskRetryEvidence(
    code: AgentTaskRetryEvidenceCode,
): AgentTaskRetryEvidencePresentation {
    return when (code) {
        AgentTaskRetryEvidenceCode.NO_SIDE_EFFECT -> AgentTaskRetryEvidencePresentation(
            code = code,
            label = "未发现高风险副作用",
            detail = "当前证据未显示非 SAFE 工具已经产生外部变化。",
            suggestedAction = "可以直接创建关联新 Run。",
        )
        AgentTaskRetryEvidenceCode.NOT_COMMITTED -> AgentTaskRetryEvidencePresentation(
            code = code,
            label = "明确未提交",
            detail = "工具结果明确失败且回执标记为未提交。",
            suggestedAction = "可以直接创建关联新 Run。",
        )
        AgentTaskRetryEvidenceCode.COMMIT_UNKNOWN -> AgentTaskRetryEvidencePresentation(
            code = code,
            label = "提交状态未知",
            detail = "无法确认工具是否已经产生外部变化。",
            suggestedAction = "确认后创建关联新 Run，旧 Run 保持不变。",
        )
        AgentTaskRetryEvidenceCode.COMMITTED_UNVERIFIED -> AgentTaskRetryEvidencePresentation(
            code = code,
            label = "已提交但未验证",
            detail = "已有提交回执，但缺少通过验证的完整事实。",
            suggestedAction = "确认后创建关联新 Run，不重放旧 Run。",
        )
        AgentTaskRetryEvidenceCode.COMMITTED_VERIFIED -> AgentTaskRetryEvidencePresentation(
            code = code,
            label = "已提交且已验证",
            detail = "旧 Run 已有成功提交与验证事实，重试可能重复产生结果。",
            suggestedAction = "只有确认后才能创建关联新 Run。",
        )
        AgentTaskRetryEvidenceCode.EVIDENCE_INCOMPLETE -> AgentTaskRetryEvidencePresentation(
            code = code,
            label = "执行证据不完整",
            detail = "工具账本或事件证据存在缺失、漂移或矛盾。",
            suggestedAction = "确认后创建关联新 Run，不能恢复或重放旧 Run。",
        )
    }
}
