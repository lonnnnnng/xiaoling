package com.longdev.xiaoling.genui

import com.longdev.xiaoling.agent.*
import java.util.UUID

/** long: 共享夹具只存本轮事实，Android 端不访问用户 Room；真实持久化另由生产专项验证。 */
internal class GenUiTestLedger : AgentRunLedger {
    private val runs=linkedMapOf<String,AgentRunRecord>()
    private val steps=linkedMapOf<String,AgentStepRecord>()
    private val events=mutableListOf<RunEventRecord>()
    var lastRunId:String?=null
    override suspend fun createRun(conversationId:String,userMessageId:String,goal:String,retryOfRunId:String?):AgentRunRecord {
        val now=System.currentTimeMillis()
        val run=AgentRunRecord(id="run-${UUID.randomUUID()}",retryOfRunId=retryOfRunId,conversationId=conversationId,
            userMessageId=userMessageId,goal=goal,status=AgentRunStatus.QUEUED,result=null,errorMessage=null,
            createdAt=now,updatedAt=now,completedAt=null)
        runs[run.id]=run;lastRunId=run.id; return run
    }
    override suspend fun updateRunStatus(runId:String,status:AgentRunStatus,result:String?,errorMessage:String?) {
        val current=runs.getValue(runId); if(current.status.isTerminal) return
        runs[runId]=current.copy(status=status,result=result ?: current.result,errorMessage=errorMessage ?: current.errorMessage,
            completedAt=System.currentTimeMillis().takeIf { status.isTerminal })
    }
    override suspend fun appendStep(runId:String,type:String,title:String,detail:String,status:AgentStepStatus):AgentStepRecord {
        val step=AgentStepRecord("step-${UUID.randomUUID()}",runId,steps.values.count{it.runId==runId}+1,type,status,title,detail,System.currentTimeMillis(),null)
        steps[step.id]=step; return step
    }
    override suspend fun updateStep(stepId:String,status:AgentStepStatus,detail:String?) {
        val step=steps.getValue(stepId);steps[stepId]=step.copy(status=status,detail=detail ?: step.detail)
    }
    override suspend fun appendEvent(runId:String,type:String,message:String,metadata:RunEventMetadata?) {
        events+=RunEventRecord("event-${UUID.randomUUID()}",runId,type,message,System.currentTimeMillis(),metadata)
    }
    override suspend fun snapshot(runId:String)=AgentRunSnapshot(runs.getValue(runId),steps.values.filter{it.runId==runId},events.filter{it.runId==runId})
}
