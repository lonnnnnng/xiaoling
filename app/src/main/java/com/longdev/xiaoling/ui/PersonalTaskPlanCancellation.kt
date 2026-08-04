package com.longdev.xiaoling.ui

internal fun personalTaskPlanCancellationFailure(goal: String): PersonalTaskFailureUiState {
    // long: 计划尚未创建任何执行事实时，用户停止只应保留原目标并给出可重新生成入口。
    return PersonalTaskFailureUiState(
        goal = goal,
        title = "计划生成已停止",
        message = "原始目标已保留，可重新生成任务计划",
    )
}
