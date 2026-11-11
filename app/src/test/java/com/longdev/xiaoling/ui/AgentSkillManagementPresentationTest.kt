package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentSkillDefinition
import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.AgentSkillSource
import com.longdev.xiaoling.agent.GitHubSkillCandidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentSkillManagementPresentationTest {
    @Test fun replacedRefreshCannotEndNewLoadingOrOverwriteOtherPageState() = runTest {
        val f = Fixture(this)
        val old = CompletableDeferred<List<AgentSkillRecord>>()
        val latest = CompletableDeferred<List<AgentSkillRecord>>()
        var reads = 0
        f.load = { if (++reads == 1) old.await() else latest.await() }
        f.presentation.refreshSkills()
        runCurrent()
        f.presentation.refreshSkills()
        runCurrent()
        assertTrue(f.state.loadingSkills)
        assertNull(f.state.skillError)
        f.state = f.state.copy(selectedConversationId = "another-conversation")
        latest.complete(listOf(skill("latest")))
        advanceUntilIdle()
        old.complete(listOf(skill("stale")))
        advanceUntilIdle()
        assertEquals(listOf("latest"), f.state.skills.map { it.definition.id })
        assertEquals("another-conversation", f.state.selectedConversationId)
        assertFalse(f.state.loadingSkills)
        assertNull(f.state.skillError)
    }

    @Test fun discoveryRequiresSelectionOnlyForMultipleCurrentCandidates() = runTest {
        val f = Fixture(this)
        f.presentation.discoverGitHubSkills("repo")
        advanceUntilIdle()
        assertTrue(f.state.skillError!!.contains("没有找到"))
        assertTrue(f.importedUrls.isEmpty())

        val first = candidate("one")
        val second = candidate("two")
        f.candidates = listOf(first)
        f.presentation.discoverGitHubSkills("repo")
        advanceUntilIdle()
        assertEquals(listOf(first.sourceUrl), f.importedUrls)
        assertFalse(f.state.importingSkill)
        assertEquals("GitHub Skill 已导入", f.state.result?.title)

        f.candidates = listOf(first, second)
        f.presentation.discoverGitHubSkills("repo")
        advanceUntilIdle()
        assertEquals(f.candidates, f.state.githubSkillCandidates)
        f.presentation.importGitHubSkillCandidate(candidate("stale"))
        assertEquals(f.candidates, f.state.githubSkillCandidates)
        f.presentation.importGitHubSkillCandidate(second)
        f.presentation.importGitHubSkillCandidate(second)
        advanceUntilIdle()
        assertEquals(listOf(first.sourceUrl, second.sourceUrl), f.importedUrls)
        assertTrue(f.state.githubSkillCandidates.isEmpty())
        f.presentation.discoverGitHubSkills("repo")
        advanceUntilIdle()
        f.presentation.cancelGitHubSkillSelection()
        assertTrue(f.state.githubSkillCandidates.isEmpty())
    }

    @Test fun mutationUsesStableIdAndSuppressesDuplicateUntilItFinishes() = runTest {
        val f = Fixture(this)
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<String, Boolean>>()
        f.mutate = { id, enabled ->
            calls += id to enabled
            release.await()
            f.records = listOf(skill(id).copy(enabled = enabled))
            f.records.single()
        }
        f.presentation.setSkillEnabled("local", false)
        f.presentation.setSkillEnabled("local", true)
        runCurrent()
        assertEquals(listOf("local" to false), calls)
        assertEquals(setOf("local"), f.state.mutatingSkillIds)
        f.state = f.state.copy(selectedConversationId = "changed")
        release.complete(Unit)
        advanceUntilIdle()
        assertFalse(f.state.skills.single().enabled)
        assertTrue(f.state.mutatingSkillIds.isEmpty())
        assertEquals("changed", f.state.selectedConversationId)
    }

    @Test fun localDeleteNeedsConfirmationAndBuiltInNeverReachesStore() = runTest {
        val f = Fixture(this)
        f.state = f.state.copy(skills = listOf(skill("built-in", AgentSkillSource.BUILT_IN), skill("local")))
        f.presentation.requestLocalSkillDelete("built-in")
        f.presentation.confirmLocalSkillDelete()
        assertEquals(listOf("内置 Skill 不能删除"), f.validations)
        assertTrue(f.deletedIds.isEmpty())
        f.presentation.requestLocalSkillDelete("local")
        f.presentation.cancelLocalSkillDelete()
        f.presentation.confirmLocalSkillDelete()
        assertTrue(f.deletedIds.isEmpty())
        f.presentation.requestLocalSkillDelete("local")
        f.presentation.confirmLocalSkillDelete()
        f.presentation.confirmLocalSkillDelete()
        advanceUntilIdle()
        assertEquals(listOf("local"), f.deletedIds)
        assertNull(f.state.pendingLocalSkillDelete)
        assertTrue(f.state.mutatingSkillIds.isEmpty())
        assertEquals("Skill 已删除", f.state.result?.title)
    }

    @Test fun fileReadFailureAndDuplicateImportDoNotReachStore() = runTest {
        val f = Fixture(this)
        var imports = 0
        val release = CompletableDeferred<Unit>()
        f.importDocument = { imports++; release.await(); skill("imported") }
        f.presentation.importSkill { error("文件不可读") }
        advanceUntilIdle()
        assertEquals(0, imports)
        assertEquals("文件不可读", f.state.skillError)
        assertFalse(f.state.importingSkill)
        f.presentation.importSkill { "valid document" }
        f.presentation.importSkill { error("重复导入不能读文件") }
        runCurrent()
        assertEquals(1, imports)
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(f.state.result?.success == true)
        assertFalse(f.state.importingSkill)
    }

    private class Fixture(scope: TestScope) {
        var state = XiaoLingUiState()
        var records = emptyList<AgentSkillRecord>()
        var candidates = emptyList<GitHubSkillCandidate>()
        val importedUrls = mutableListOf<String>()
        val deletedIds = mutableListOf<String>()
        val validations = mutableListOf<String>()
        var load: suspend () -> List<AgentSkillRecord> = { records }
        var mutate: suspend (String, Boolean) -> AgentSkillRecord? = { _, _ -> null }
        var importDocument: suspend (String) -> AgentSkillRecord = { skill("imported") }
        val presentation = AgentSkillManagementPresentation(
            scope = scope, readState = { state }, writeState = { state = it }, listSkills = { load() },
            importDocument = { importDocument(it) }, discoverCandidates = { candidates },
            importGitHubDocument = { importedUrls += it; skill("github") },
            setEnabled = { id, enabled -> mutate(id, enabled) },
            deleteLocal = { deletedIds += it; true }, showValidation = { validations += it },
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
    }

    companion object {
        private fun skill(id: String, source: AgentSkillSource = AgentSkillSource.LOCAL) = AgentSkillRecord(
            AgentSkillDefinition(id = id, name = id, description = "说明", instructions = "说明", toolNames = emptySet(), keywords = emptySet(), source = source),
            enabled = true, importedAt = 1L, updatedAt = 1L,
        )
        private fun candidate(id: String) = GitHubSkillCandidate("https://github.com/example/skills/$id/SKILL.md", "main", "$id/SKILL.md")
    }
}
