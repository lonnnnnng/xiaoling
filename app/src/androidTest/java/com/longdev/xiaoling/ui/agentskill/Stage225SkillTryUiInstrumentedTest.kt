package com.longdev.xiaoling.ui.agentskill

import android.os.Build
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomAgentSkillStore
import com.longdev.xiaoling.storage.RoomStateStore
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * long: 第 225 阶段只验证真实应用壳把当前 Profile 已授权的 SAFE Skill 示例变成可审阅草稿，
 * 并以 Room 前后摘要证明点击没有发送消息、创建 Run 或执行工具。
 */
class Stage225SkillTryUiInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun currentSafeSkillExampleOnlyPrefillsAgentDraftWithoutCreatingFacts() = runBlocking {
        assumeTrue(
            "第 225 阶段只允许显式 stage225Manual=true 运行",
            InstrumentationRegistry.getArguments().getString(ARG_MANUAL_RUN) == "true",
        )
        assertEquals("第 225 阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = XiaoLingDatabase.getInstance(context)
        val stateStore = RoomStateStore(context)

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithTag("bottom_tab_settings").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("bottom_tab_settings").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("settings-entry-agent-skills").fetchSemanticsNodes().isNotEmpty()
        }

        val selectedProfileId = requireNotNull(stateStore.selectedAgentProfileId()) { "Redmi 当前没有选中的 Agent Profile" }
        val selectedConversationId = requireNotNull(stateStore.selectedConversationId()) { "Redmi 当前没有选中的会话" }
        val selectedProfile = RoomAgentProfileStore(context).list().singleOrNull { profile ->
            profile.id == selectedProfileId
        }
        assertNotNull("Redmi 当前选中的 Agent Profile 不存在", selectedProfile)
        requireNotNull(selectedProfile)
        val skill = selectCurrentSafeSkill(
            profile = selectedProfile,
            skills = RoomAgentSkillStore(context).list(),
        )
        assertNotNull("当前 Agent Profile 没有可直接试用的 SAFE Skill", skill)
        requireNotNull(skill)
        val example = skill.definition.triggerExamples
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .first()
        val expectedPrompt = if (example == "/agent" || example.startsWith("/agent ")) example else "/agent $example"
        val baselineMessages = database.conversationDao().getMessagesByConversationId(selectedConversationId).stableDigest()
        val runRepository = RoomAgentRunRepository(context)
        val baselineRuns = runRepository.recentRunDetails(RUN_AUDIT_LIMIT).stableDigest()

        composeRule.onNodeWithTag("settings-entry-agent-skills").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag("agent-skill-list", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        // long: Skill 采用 LazyColumn 延迟组合；先让列表滚动并组合目标项，不能假设当前 Profile 授权项恰好位于首屏。
        composeRule.onNodeWithTag("agent-skill-list", useUnmergedTree = true)
            .performScrollToNode(hasTestTag(agentSkillItemTag(skill.definition.id)))
        composeRule.onNodeWithTag(agentSkillItemTag(skill.definition.id), useUnmergedTree = true).performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(
                agentSkillTryExampleTag(skill.definition.id, 0),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(
            agentSkillTryExampleTag(skill.definition.id, 0),
            useUnmergedTree = true,
        ).performScrollTo().assertIsEnabled().performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("bottom_tab_conversation").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("conversation-prompt-input").assertTextEquals(expectedPrompt)
        composeRule.waitForIdle()

        assertEquals(selectedProfileId, stateStore.selectedAgentProfileId())
        assertEquals(selectedConversationId, stateStore.selectedConversationId())
        assertEquals(
            "Skill 示例点击不得写入会话消息",
            baselineMessages,
            database.conversationDao().getMessagesByConversationId(selectedConversationId).stableDigest(),
        )
        assertEquals(
            "Skill 示例点击不得创建或改写 Agent Run",
            baselineRuns,
            runRepository.recentRunDetails(RUN_AUDIT_LIMIT).stableDigest(),
        )
        println(
            "STAGE225_SKILL_TRY profileId=$selectedProfileId skillId=${skill.definition.id} " +
                "conversationUnchanged=true messagesUnchanged=true runsUnchanged=true promptPrefilled=true autoSent=false",
        )
    }

    private fun selectCurrentSafeSkill(
        profile: AgentProfileRecord,
        skills: List<AgentSkillRecord>,
    ): AgentSkillRecord? {
        return skills.firstOrNull { skill ->
            skill.enabled &&
                skill.definition.declaredRisk == ToolRisk.SAFE &&
                skill.definition.id in profile.allowedSkillIds &&
                profile.allowedToolNames.containsAll(skill.definition.toolNames) &&
                skill.definition.triggerExamples.any { example -> example.isNotBlank() }
        }
    }

    private fun Any.stableDigest(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private companion object {
        const val ARG_MANUAL_RUN = "stage225Manual"
        const val RUN_AUDIT_LIMIT = 100
    }
}
