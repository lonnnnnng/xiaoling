package com.longdev.xiaoling.genui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.storage.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** long: UUID 数据库隔离 action/审批证据；执行生产宿主时间工具，不改正式 Provider、MCP 或 Memory。 */
class GenUiProductionInstrumentedTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="stage290-genui-${UUID.randomUUID()}.db"
    private lateinit var db:XiaoLingDatabase
    private lateinit var repo:RoomAgentRunRepository
    private val foreground=AgentPluginForegroundState(); private val owner=Any()
    private val leases=GenUiActionLeases()
    @Before fun prepare() {
        assertEquals("begonia",android.os.Build.DEVICE)
        db=Room.databaseBuilder(context,XiaoLingDatabase::class.java,name).addMigrations(*XiaoLingDatabase.migrations()).build()
        repo=RoomAgentRunRepository(context,db); foreground.resumed(owner)
    }
    @After fun clean() { db.close(); if(context.getDatabasePath(name).exists()) check(context.deleteDatabase(name)) }
    private fun useCase()=AgentRunUseCase(context,OpenAiCompatibleClient(),McpSessionRegistry(object:McpServerStore {
        override fun list()=emptyList<McpServerConfig>()
        override fun get(id:String):McpServerConfig?=null
        override fun upsert(config:McpServerConfig):McpServerConfig=error("不得写正式MCP")
        override fun delete(id:String):Boolean=error("不得写正式MCP")
    }),repo,pluginForeground=foreground,skillStore=RoomAgentSkillStore(context,db))
    private fun profile()=AgentProfileSnapshot("fixture","只读卡片","P","fixture-provider","fixture",ApiMode.CHAT_COMPLETIONS,
        "本地固定 action",AgentContextPolicy.CURRENT_CONVERSATION,listOf("app.current_time","notes.create"),emptyList(),false)
    private fun selection()=leases.select(requireNotNull(leases.issue("c","source",genUiFixtureText())),"c","source",genUiFixtureText(),"read",emptyMap())
    @Test fun productionClickPersistsApprovalThenOneVerifiedNativeResultAndSourceIdentity()=runBlocking {
        val useCase=useCase(); val waiting=CompletableDeferred<ApprovalRequestRecord>(); val decision=CompletableDeferred<ApprovalDecision>()
        val gate=object:ApprovalGate { override suspend fun requestApproval(runId:String,toolCall:ToolCall,definition:ToolDefinition):ApprovalDecision {
            val request=repo.createApprovalRequest("c",runId,toolCall,definition); waiting.complete(request)
            return decision.await()
        } }
        try {
            val job=async(Dispatchers.IO) { useCase.runGenUiAction("c","new-user",selection(),foreground.capture(),profile(),gate) }
            val request=withTimeout(10_000){waiting.await()}; assertEquals(AgentRunStatus.WAITING_APPROVAL,repo.snapshot(request.runId).run.status)
            assertTrue(repo.toolLedger(request.runId).results.isEmpty())
            repo.decideApprovalRequest(request.id,ApprovalRequestStatus.APPROVED,"用户批准")
            decision.complete(ApprovalDecision(true,"用户批准")); val summary=job.await(); val detail=requireNotNull(repo.runDetail(summary.runId))
            assertEquals(AgentRunStatus.COMPLETED,summary.status); assertEquals(1,detail.toolLedger.calls.size)
            assertEquals("app.current_time",detail.toolLedger.calls.single().toolName)
            assertEquals(ToolVerificationStatus.PASSED,detail.toolLedger.results.single().verificationStatus)
            assertTrue(detail.toolLedger.results.single().content.isNotBlank())
            val source=detail.snapshot.events.single { it.type==GenUiExecutionPolicy.SELECTION_EVENT }.metadata as RunEventMetadata.Reason
            val json=org.json.JSONObject(source.reason); assertEquals("source",json.getString("messageId")); assertEquals(GenUiActionPolicy.hash(genUiFixtureText()),json.getString("messageSha256"))
            assertEquals(ApprovalRequestStatus.APPROVED,detail.approvals.single().status)
            assertTrue(repo.contextTranscript(summary.runId).isEmpty())
        } finally { useCase.closeMcpOwners() }
    }
    @Test fun rejectionAndForegroundRevocationPersistNoResult()=runBlocking {
        for(pause in listOf(false,true)) {
            foreground.resumed(owner); val useCase=useCase(); val waiting=CompletableDeferred<Unit>()
            try {
                val gate=object:ApprovalGate { override suspend fun requestApproval(runId:String,toolCall:ToolCall,definition:ToolDefinition):ApprovalDecision {
                    waiting.complete(Unit); if(pause) awaitCancellation(); return ApprovalDecision(false,"拒绝")
                } }
                val job=async(Dispatchers.IO){runCatching { useCase.runGenUiAction("c","u",selection(),foreground.capture(),profile(),gate) }}
                waiting.await(); if(pause) { foreground.paused(owner); foreground.resumed(owner) }
                assertTrue(withTimeout(5_000){job.await()}.isFailure)
                val id=db.agentRunDao().getRecentRuns(1).single().id
                assertEquals(if(pause) AgentRunStatus.CANCELLED else AgentRunStatus.FAILED,repo.snapshot(id).run.status)
                assertTrue(repo.toolLedger(id).results.isEmpty())
            } finally { useCase.closeMcpOwners() }
        }
    }
    @Test fun markerFailureRollsBackRunAndInitializationFailureSettles()=runBlocking {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_genui_marker BEFORE INSERT ON run_events WHEN NEW.type = 'genui.action.selected' BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        assertTrue(runCatching { repo.createGenUiRun("c","u","fixture","{}") }.isFailure)
        assertTrue(db.agentRunDao().getRecentRuns(10).isEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_genui_marker")
        val useCase=useCase()
        try {
            assertTrue(runCatching { useCase.runGenUiAction("c","u",selection(),foreground.capture(),profile(),AutoApprovalGate()) { error("fixture callback") } }.isFailure)
            val id=db.agentRunDao().getRecentRuns(1).single().id
            assertEquals(AgentRunStatus.FAILED,repo.snapshot(id).run.status); assertTrue(GenUiExecutionPolicy.isGenUiRun(requireNotNull(repo.runDetail(id))))
        } finally { useCase.closeMcpOwners() }
    }
    @Test fun diskReopenClosesOldActionAndNeverRecoversPermission()=runBlocking {
        val oldLease=requireNotNull(leases.issue("c","source",genUiFixtureText()))
        val run=repo.createGenUiRun("c","u","fixture","damaged-source"); repo.updateRunStatus(run.id,AgentRunStatus.WAITING_APPROVAL)
        db.close(); db=Room.databaseBuilder(context,XiaoLingDatabase::class.java,name).addMigrations(*XiaoLingDatabase.migrations()).build(); repo=RoomAgentRunRepository(context,db)
        assertTrue(runCatching { GenUiActionLeases().select(oldLease,"c","source",genUiFixtureText(),"read",emptyMap()) }.isFailure)
        val useCase=useCase()
        try {
            assertTrue(useCase.recoverCommittedToolRuns().isEmpty()); assertTrue(useCase.recoverVerifiedToolRuns().isEmpty())
            assertEquals(1,useCase.closeInterruptedRuns()); val detail=requireNotNull(repo.runDetail(run.id))
            assertEquals(AgentRunStatus.CANCELLED,detail.snapshot.run.status)
            assertEquals(AgentRunRestartDispositionCode.GENUI_SELECTION_EXPIRED,detail.latestRecoveryMetadata()!!.restartDisposition!!.code)
            assertEquals(AgentTaskRetryEligibility.NotRetryable,AgentTaskRetryPolicy.evaluate(detail))
        } finally { useCase.closeMcpOwners() }
    }
}
