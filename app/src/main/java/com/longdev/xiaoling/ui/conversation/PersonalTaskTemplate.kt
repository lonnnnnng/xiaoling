package com.longdev.xiaoling.ui.conversation

internal data class PersonalTaskTemplate(
    val id: String,
    val title: String,
    val goal: String,
)

// long: 模板只提供已验收动作范围内的目标起点，不提前请求模型、不创建 Workflow，也不绕过用户确认。
internal val personalTaskTemplates = listOf(
    PersonalTaskTemplate(
        id = "calculator",
        title = "打开计算器",
        goal = "打开计算器并查看当前界面",
    ),
    PersonalTaskTemplate(
        id = "settings",
        title = "搜索系统设置",
        goal = "打开系统设置并搜索 Wi-Fi",
    ),
    PersonalTaskTemplate(
        id = "clock",
        title = "打开时钟",
        goal = "打开时钟并查看当前界面",
    ),
    PersonalTaskTemplate(
        id = "weather",
        title = "查看天气",
        goal = "打开天气并查看当前天气",
    ),
)
