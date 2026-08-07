package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.VerifiedAgentContext

/**
 * long: 周期计划状态只有经过应用执行器和 typed verification 后才刷新任务投影，
 * 避免模型自由文本或未验证结果让会话和 Room 快照显示互相矛盾的状态。
 */
internal fun shouldRefreshWorkflowsAfterTaskScheduleControl(
    context: VerifiedAgentContext,
): Boolean = presentTaskScheduleControlCompletion(context) != null
