package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.AgentSkillSource
import com.longdev.xiaoling.agent.GitHubSkillCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AgentSkillManagementPresentation(
    private val scope: CoroutineScope,
    private val readState: () -> XiaoLingUiState,
    private val writeState: (XiaoLingUiState) -> Unit,
    private val listSkills: suspend () -> List<AgentSkillRecord>,
    private val importDocument: suspend (String) -> AgentSkillRecord,
    private val discoverCandidates: suspend (String) -> List<GitHubSkillCandidate>,
    private val importGitHubDocument: suspend (String) -> AgentSkillRecord,
    private val setEnabled: suspend (String, Boolean) -> AgentSkillRecord?,
    private val deleteLocal: suspend (String) -> Boolean,
    private val showValidation: (String) -> Unit,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // long: 每次异步结果都合并宿主最新状态，避免 Skill 页面完成操作时覆盖其他页面刚更新的会话或配置。
    private var uiState: XiaoLingUiState
        get() = readState()
        set(value) = writeState(value)
    private var skillLoadJob: Job? = null

    fun refreshSkills() {
        // long: 后来的刷新接管加载状态；旧任务取消不再写失败，防止它结束新任务的加载提示。
        skillLoadJob?.cancel()
        uiState = uiState.copy(loadingSkills = true, skillError = null)
        skillLoadJob = scope.launch {
            runCatching {
                withContext(ioDispatcher) { listSkills() }
            }.onSuccess { skills ->
                uiState = uiState.copy(loadingSkills = false, skills = skills, skillError = null)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                uiState = uiState.copy(
                    loadingSkills = false,
                    skillError = error.message ?: "无法读取 Skill",
                )
            }
        }
    }

    fun importSkill(readDocument: () -> String) {
        if (uiState.importingSkill) return
        uiState = uiState.copy(importingSkill = true, skillError = null, result = null)
        scope.launch {
            runCatching {
                withContext(ioDispatcher) {
                    val raw = readDocument()
                    importDocument(raw)
                }
            }.onSuccess { record ->
                uiState = uiState.copy(
                    importingSkill = false,
                    result = OperationResult(
                        true,
                        "Skill 已导入",
                        "${record.definition.name} v${record.definition.version} 已${if (record.enabled) "启用" else "保持停用"}",
                    ),
                )
                refreshSkills()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                uiState = uiState.copy(
                    importingSkill = false,
                    skillError = error.message ?: "Skill 导入失败",
                    result = OperationResult(false, "Skill 导入失败", error.message ?: "文件校验未通过"),
                )
            }
        }
    }

    fun discoverGitHubSkills(url: String) {
        if (uiState.importingSkill) return
        uiState = uiState.copy(importingSkill = true, githubSkillCandidates = emptyList(), skillError = null)
        scope.launch {
            runCatching { withContext(ioDispatcher) { discoverCandidates(url) } }
                .onSuccess { candidates ->
                    uiState = uiState.copy(importingSkill = false)
                    when (candidates.size) {
                        0 -> uiState = uiState.copy(skillError = "GitHub 地址下没有找到 SKILL.json 或 SKILL.md")
                        1 -> importSkillFromGitHub(candidates.single().sourceUrl)
                        else -> uiState = uiState.copy(githubSkillCandidates = candidates)
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    uiState = uiState.copy(importingSkill = false, skillError = error.message ?: "GitHub Skill 发现失败")
                }
        }
    }

    fun importGitHubSkillCandidate(candidate: GitHubSkillCandidate) {
        if (candidate !in uiState.githubSkillCandidates) return
        uiState = uiState.copy(githubSkillCandidates = emptyList())
        importSkillFromGitHub(candidate.sourceUrl)
    }

    fun cancelGitHubSkillSelection() {
        if (uiState.importingSkill) return
        uiState = uiState.copy(githubSkillCandidates = emptyList())
    }

    fun importSkillFromGitHub(url: String) {
        if (uiState.importingSkill) return
        uiState = uiState.copy(importingSkill = true, githubSkillCandidates = emptyList(), skillError = null, result = null)
        scope.launch {
            runCatching {
                withContext(ioDispatcher) { importGitHubDocument(url) }
            }.onSuccess { record ->
                uiState = uiState.copy(
                    importingSkill = false,
                    result = OperationResult(
                        true,
                        "GitHub Skill 已导入",
                        "${record.definition.name} v${record.definition.version} 已${if (record.enabled) "启用" else "保持停用"}",
                    ),
                )
                refreshSkills()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                uiState = uiState.copy(
                    importingSkill = false,
                    skillError = error.message ?: "GitHub Skill 导入失败",
                    result = OperationResult(false, "GitHub Skill 导入失败", error.message ?: "远程文件校验未通过"),
                )
            }
        }
    }

    fun setSkillEnabled(skillId: String, enabled: Boolean) {
        if (skillId in uiState.mutatingSkillIds) return
        uiState = uiState.copy(mutatingSkillIds = uiState.mutatingSkillIds + skillId, skillError = null)
        scope.launch {
            runCatching {
                withContext(ioDispatcher) { setEnabled(skillId, enabled) }
            }.onSuccess { updated ->
                uiState = uiState.copy(
                    mutatingSkillIds = uiState.mutatingSkillIds - skillId,
                    result = OperationResult(
                        updated != null,
                        if (enabled) "Skill 已启用" else "Skill 已停用",
                        updated?.definition?.name ?: "Skill 不存在",
                    ),
                )
                refreshSkills()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                uiState = uiState.copy(
                    mutatingSkillIds = uiState.mutatingSkillIds - skillId,
                    skillError = error.message ?: "无法更新 Skill",
                )
            }
        }
    }

    fun requestLocalSkillDelete(skillId: String) {
        val skill = uiState.skills.firstOrNull { it.definition.id == skillId } ?: return
        if (skill.definition.source != AgentSkillSource.LOCAL) {
            showValidation("内置 Skill 不能删除")
            return
        }
        uiState = uiState.copy(pendingLocalSkillDelete = skill)
    }

    fun cancelLocalSkillDelete() {
        uiState = uiState.copy(pendingLocalSkillDelete = null)
    }

    fun confirmLocalSkillDelete() {
        val skill = uiState.pendingLocalSkillDelete ?: return
        val skillId = skill.definition.id
        if (skillId in uiState.mutatingSkillIds) return
        uiState = uiState.copy(
            pendingLocalSkillDelete = null,
            mutatingSkillIds = uiState.mutatingSkillIds + skillId,
        )
        scope.launch {
            runCatching {
                withContext(ioDispatcher) { deleteLocal(skillId) }
            }.onSuccess { deleted ->
                uiState = uiState.copy(
                    mutatingSkillIds = uiState.mutatingSkillIds - skillId,
                    result = OperationResult(
                        deleted,
                        if (deleted) "Skill 已删除" else "删除失败",
                        if (deleted) skill.definition.name else "Skill 不存在或不是本地 Skill",
                    ),
                )
                refreshSkills()
            }.onFailure { error ->
                if (error is CancellationException) throw error
                uiState = uiState.copy(
                    mutatingSkillIds = uiState.mutatingSkillIds - skillId,
                    skillError = error.message ?: "删除 Skill 失败",
                )
            }
        }
    }

}
