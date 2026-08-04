package com.longdev.xiaoling.ui

enum class PersonalTaskFailureAction {
    RETRY_PLAN,
    VIEW_WORKFLOW,
}

internal fun personalTaskPlanCancellationFailure(goal: String): PersonalTaskFailureUiState {
    // long: 计划尚未创建任何执行事实时，用户停止只应保留原目标并给出可重新生成入口。
    return PersonalTaskFailureUiState(
        goal = goal,
        title = "计划生成已停止",
        message = "原始目标已保留，可重新生成任务计划",
    )
}

internal fun personalTaskCommittedFailure(
    goal: String,
    title: String,
    message: String,
): PersonalTaskFailureUiState {
    // long: 已提交的任务只能引导用户查看既有记录，不能复用“重新生成”创建第二个 Workflow。
    return PersonalTaskFailureUiState(
        goal = goal,
        title = title,
        message = message,
        action = PersonalTaskFailureAction.VIEW_WORKFLOW,
    )
}
