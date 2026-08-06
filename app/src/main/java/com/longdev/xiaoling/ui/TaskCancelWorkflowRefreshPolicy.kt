package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.VerifiedAgentContext

/**
 * long: 取消结果已经通过应用执行器和 typed verification 后，任务中心才需要重新读取 Room；
 * 模型自由文本、失败或未验证结果不能触发刷新，避免把不可信状态当成持久化事实。
 */
internal fun shouldRefreshWorkflowsAfterTaskCancel(
    context: VerifiedAgentContext,
): Boolean = presentTaskCancelCompletion(context) != null
