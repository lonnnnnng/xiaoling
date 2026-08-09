package com.longdev.xiaoling.ui.agentskill

import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.agent.AgentSkillRecord

internal data class AgentSkillTryAvailability(
    val enabled: Boolean,
    val disabledReason: String? = null,
)

internal data class AgentSkillTryDraft(
    val skillId: String,
    val prompt: String,
)

internal object AgentSkillTryPolicy {
    fun availability(
        skill: AgentSkillRecord,
        selectedProfile: AgentProfileRecord?,
        registeredToolNames: Set<String>,
    ): AgentSkillTryAvailability {
        if (!skill.enabled) return AgentSkillTryAvailability(false, "Skill 已停用")
        if (selectedProfile == null) return AgentSkillTryAvailability(false, "请先选择 Agent")
        if (skill.definition.id !in selectedProfile.allowedSkillIds) {
            return AgentSkillTryAvailability(false, "当前 Agent 未启用此 Skill")
        }
        if (!selectedProfile.allowedToolNames.containsAll(skill.definition.toolNames)) {
            return AgentSkillTryAvailability(false, "当前 Agent 未开放所需工具")
        }
        if (!registeredToolNames.containsAll(skill.definition.toolNames)) {
            return AgentSkillTryAvailability(false, "所需工具尚未注册")
        }
        if (skill.definition.triggerExamples.none { example -> example.isNotBlank() }) {
            return AgentSkillTryAvailability(false, "Skill 没有可试用示例")
        }
        return AgentSkillTryAvailability(true)
    }

    fun prepareDraft(
        skillId: String,
        requestedExample: String,
        skills: List<AgentSkillRecord>,
        selectedProfile: AgentProfileRecord?,
        registeredToolNames: Set<String>,
    ): AgentSkillTryDraft? {
        val skill = skills.singleOrNull { record -> record.definition.id == skillId } ?: return null
        if (!availability(skill, selectedProfile, registeredToolNames).enabled) return null
        // long: 页面只能回传当前 Skill 自己声明的示例；刷新、导入或列表替换后的陈旧按钮不能把任意文本注入对话草稿。
        val canonicalExample = skill.definition.triggerExamples
            .map(String::trim)
            .firstOrNull { example -> example.isNotEmpty() && example == requestedExample.trim() }
            ?: return null
        val prompt = if (canonicalExample == "/agent" || canonicalExample.startsWith("/agent ")) {
            canonicalExample
        } else {
            "/agent $canonicalExample"
        }
        return AgentSkillTryDraft(skillId = skillId, prompt = prompt)
    }
}
