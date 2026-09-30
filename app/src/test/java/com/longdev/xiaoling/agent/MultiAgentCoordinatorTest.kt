package com.longdev.xiaoling.agent

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiAgentCoordinatorTest {
    @Test
    fun runsAtMostTwoReadOnlyChildrenAndKeepsInputOrder() = runTest {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val coordinator = MultiAgentCoordinator()
        val results = coordinator.run(
            parent = parentContext(),
            children = listOf(
                ReadOnlyChildAgentSpec("one", "读取第一个事实"),
                ReadOnlyChildAgentSpec("two", "读取第二个事实"),
            ),
        ) { child ->
            val current = active.incrementAndGet()
            peak.updateAndGet { previous -> maxOf(previous, current) }
            delay(10)
            active.decrementAndGet()
            ReadOnlyChildAgentResult(child.id, "run-${child.id}", AgentRunStatus.COMPLETED, child.goal)
        }

        assertEquals(listOf("one", "two"), results.map { it.childId })
        assertEquals(2, peak.get())
        assertTrue(results.all { it.status == AgentRunStatus.COMPLETED })
    }

    @Test
    fun rejectsBackgroundWorkflowAndRecursiveChildren() = runTest {
        val coordinator = MultiAgentCoordinator()
        val child = listOf(ReadOnlyChildAgentSpec("one", "只读目标"))
        val background = runCatching {
            coordinator.run(parentContext(AgentExecutionOrigin.BACKGROUND), child) { error("不应执行") }
        }.exceptionOrNull()
        val workflow = runCatching {
            coordinator.run(parentContext(invocationSource = AgentInvocationSource.WORKFLOW), child) { error("不应执行") }
        }.exceptionOrNull()
        val recursive = runCatching {
            coordinator.run(parentContext(depth = 1), child) { error("不应执行") }
        }.exceptionOrNull()

        assertEquals("只读子 Agent 目前只允许前台执行", background?.message)
        assertEquals("Workflow 和远程入口暂不允许派生子 Agent", workflow?.message)
        assertEquals("只读子 Agent 暂不允许递归派生", recursive?.message)
    }

    @Test
    fun capturesRunnerFailureWithoutReorderingOtherResults() = runTest {
        val coordinator = MultiAgentCoordinator()
        val failure = AtomicReference<Throwable?>()
        val results = coordinator.run(
            parent = parentContext(),
            children = listOf(
                ReadOnlyChildAgentSpec("first", "第一个"),
                ReadOnlyChildAgentSpec("second", "第二个"),
            ),
        ) { child ->
            runCatching {
                if (child.id == "first") error("子 Agent 失败")
                ReadOnlyChildAgentResult(child.id, "run-${child.id}", AgentRunStatus.COMPLETED, child.goal)
            }.getOrElse { error ->
                failure.set(error)
                ReadOnlyChildAgentResult(child.id, null, AgentRunStatus.FAILED, null, error.message)
            }
        }

        assertEquals("first", results[0].childId)
        assertEquals(AgentRunStatus.FAILED, results[0].status)
        assertEquals("子 Agent 失败", results[0].errorMessage)
        assertEquals(AgentRunStatus.COMPLETED, results[1].status)
        assertEquals("子 Agent 失败", failure.get()?.message)
    }

    private fun parentContext(
        executionOrigin: AgentExecutionOrigin = AgentExecutionOrigin.FOREGROUND,
        invocationSource: AgentInvocationSource = AgentInvocationSource.DIRECT,
        depth: Int = 0,
    ) = MultiAgentParentContext(
        parentRunId = "run-parent",
        executionOrigin = executionOrigin,
        invocationSource = invocationSource,
        depth = depth,
    )
}
