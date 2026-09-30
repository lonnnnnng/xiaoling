package com.longdev.xiaoling.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 只读子 Agent 的最小调度契约。
 *
 * long: 第二组先把并发、归属和权限边界冻结在一个可测试的协调器里；真正的设备动作、写工具、后台任务和递归派生留到后续切片。
 */
data class ReadOnlyChildAgentSpec(
    val id: String,
    val goal: String,
)

data class MultiAgentParentContext(
    val parentRunId: String,
    val executionOrigin: AgentExecutionOrigin,
    val invocationSource: AgentInvocationSource,
    val depth: Int = 0,
)

data class ReadOnlyChildAgentResult(
    val childId: String,
    val childRunId: String?,
    val status: AgentRunStatus,
    val responseText: String?,
    val errorMessage: String? = null,
)

fun interface ReadOnlyChildAgentRunner {
    suspend fun run(spec: ReadOnlyChildAgentSpec): ReadOnlyChildAgentResult
}

object MultiAgentPolicy {
    const val MAX_CHILDREN = 2
    const val MAX_GOAL_LENGTH = 2_000

    fun validateParent(context: MultiAgentParentContext) {
        require(context.parentRunId.isNotBlank()) { "多 Agent 缺少父 Run ID" }
        require(context.executionOrigin == AgentExecutionOrigin.FOREGROUND) {
            "只读子 Agent 目前只允许前台执行"
        }
        require(context.invocationSource == AgentInvocationSource.DIRECT) {
            "Workflow 和远程入口暂不允许派生子 Agent"
        }
        require(context.depth == 0) { "只读子 Agent 暂不允许递归派生" }
    }

    fun validateChildren(children: List<ReadOnlyChildAgentSpec>) {
        require(children.isNotEmpty()) { "至少需要一个子 Agent 目标" }
        require(children.size <= MAX_CHILDREN) { "一次最多派生 $MAX_CHILDREN 个只读子 Agent" }
        require(children.map { it.id }.distinct().size == children.size) { "子 Agent ID 不能重复" }
        children.forEach { child ->
            require(child.id.isNotBlank()) { "子 Agent ID 不能为空" }
            require(child.goal.isNotBlank()) { "子 Agent 目标不能为空" }
            require(child.goal.length <= MAX_GOAL_LENGTH) { "子 Agent 目标不能超过 $MAX_GOAL_LENGTH 个字符" }
        }
    }
}

class MultiAgentCoordinator(
    private val maxConcurrentChildren: Int = MultiAgentPolicy.MAX_CHILDREN,
) {
    init {
        require(maxConcurrentChildren in 1..MultiAgentPolicy.MAX_CHILDREN) {
            "子 Agent 并发上限必须在 1 到 ${MultiAgentPolicy.MAX_CHILDREN} 之间"
        }
    }

    suspend fun run(
        parent: MultiAgentParentContext,
        children: List<ReadOnlyChildAgentSpec>,
        runner: ReadOnlyChildAgentRunner,
    ): List<ReadOnlyChildAgentResult> {
        MultiAgentPolicy.validateParent(parent)
        MultiAgentPolicy.validateChildren(children)
        val semaphore = Semaphore(maxConcurrentChildren)
        return coroutineScope {
            // long: 保持输入顺序返回结果，内部只限制同时运行数量；这样父 Run 能稳定地把子结果映射回用户请求。
            children.map { child ->
                async {
                    semaphore.withPermit { runner.run(child) }
                }
            }.awaitAll()
        }
    }
}
