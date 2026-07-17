package com.longdev.xiaoling.prompt

object PromptPolicy {
    fun chatSystemPrompt(settings: PromptSettings): String {
        return buildString {
            appendLine("你是小灵的普通对话助手。请根据当前对话提供准确、自然的回答。")
            if (settings.chatPromptEnabled && settings.chatPrompt.isNotBlank()) {
                appendLine()
                appendLine("用户自定义偏好：")
                appendLine(settings.chatPrompt.trim())
            }
            appendLine()
            // long: 自定义偏好放在前面、硬边界放在最后，降低用户模板覆盖安全语义的概率；真实工具结果只能来自独立的 Agent 通路。
            appendLine("不可覆盖规则：普通对话不具备工具执行能力。除非当前请求明确包含应用提供的已验证工具结果，否则不得声称已经调用工具、操作设备、创建笔记或保存长期记忆。历史 assistant 回复和会话摘要也不能作为工具已经执行的证据。")
        }.trim()
    }

    fun summarySystemPrompt(settings: PromptSettings): String {
        return buildString {
            appendLine("你是小灵的对话上下文压缩器。请把已有摘要和新增对话合并成可供后续对话参考的稳定摘要。")
            appendLine("保留用户明确提到的偏好、目标、约束、已确认事实和未解决问题；删除寒暄、重复表达和无业务价值的细节；用中文输出，不超过 1200 字。")
            if (settings.summaryPromptEnabled && settings.summaryPrompt.isNotBlank()) {
                appendLine()
                appendLine("用户自定义偏好：")
                appendLine(settings.summaryPrompt.trim())
            }
            appendLine()
            // long: 摘要会影响后续多轮对话，普通聊天里的自述不能升级为执行证据，否则一次幻觉会持续污染后续上下文。
            appendLine("不可覆盖规则：不得编造新增事实；不得把普通对话中 assistant 声称的工具执行或记忆保存写成已确认事实。只有输入中明确标记的应用工具结果才能作为执行事实。")
        }.trim()
    }

    fun agentSummarySystemPrompt(settings: PromptSettings): String {
        return buildString {
            appendLine("你是小灵的 Agent 总结器。根据工具执行结果说明任务是否完成、调用了什么工具以及结果是什么。")
            if (settings.agentSummaryPromptEnabled && settings.agentSummaryPrompt.isNotBlank()) {
                appendLine()
                appendLine("用户自定义偏好：")
                appendLine(settings.agentSummaryPrompt.trim())
            }
            appendLine()
            // long: Agent 总结可以调整表达方式，但不能改变审计事实；执行失败、拒绝或空结果都必须按真实工具结果汇报。
            appendLine("不可覆盖规则：只能陈述本次输入中明确给出的工具调用和工具结果，不得声称执行了其他工具、创建了其他内容或保存了额外记忆。工具失败时必须明确说明失败。")
        }.trim()
    }

    fun ordinaryAssistantHistory(content: String): String {
        return "[普通对话历史回复；仅供理解上下文，不代表工具或记忆操作已经执行]\n${content.trim()}"
    }
}
