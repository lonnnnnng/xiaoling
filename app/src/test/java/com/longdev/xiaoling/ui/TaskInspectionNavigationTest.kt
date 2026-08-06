package com.longdev.xiaoling.ui

import com.longdev.xiaoling.automation.WorkflowRecord
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskInspectionNavigationTest {
    @Test
    fun trustedInspectionPartReturnsExactTaskName() {
        val part = inspectionPart()

        assertEquals("每日回顾", part.inspectedTaskNameForNavigation())
    }

    @Test
    fun failedMissingAndModelLikePartsCannotCreateNavigation() {
        assertNull(inspectionPart(success = false).inspectedTaskNameForNavigation())
        assertNull(
            inspectionPart(verificationStatus = MessageToolVerificationStatus.FAILED)
                .inspectedTaskNameForNavigation(),
        )
        assertNull(inspectionPart(result = "模型声称：任务最近运行").inspectedTaskNameForNavigation())
        assertNull(inspectionPart(arguments = mapOf("name" to "每日回顾", "id" to "private")).inspectedTaskNameForNavigation())
        assertNull(inspectionPart(toolName = "tasks.list").inspectedTaskNameForNavigation())
        assertNull(inspectionPart(arguments = mapOf("name" to "长".repeat(101))).inspectedTaskNameForNavigation())
    }

    @Test
    fun verifiedCancellationPartReturnsExactTaskNameForNavigation() {
        val part = inspectionPart(
            toolName = "tasks.cancel",
            arguments = mapOf("name" to "每日回顾"),
            result = "任务“每日回顾”：计划已取消，不会再执行。当前状态：已取消。",
            verificationStatus = MessageToolVerificationStatus.VERIFIED,
        )

        assertEquals("每日回顾", part.inspectedTaskNameForNavigation())
    }

    @Test
    fun cancellationNavigationRejectsModelLikeOrInjectedResult() {
        assertNull(
            inspectionPart(
                toolName = "tasks.cancel",
                arguments = mapOf("name" to "每日回顾"),
                result = "模型声称：任务“每日回顾”已经取消",
                verificationStatus = MessageToolVerificationStatus.VERIFIED,
            ).inspectedTaskNameForNavigation(),
        )
        assertNull(
            inspectionPart(
                toolName = "tasks.cancel",
                arguments = mapOf("name" to "每日\n回顾"),
                result = "任务“每日\n回顾”：计划已取消，不会再执行。",
                verificationStatus = MessageToolVerificationStatus.VERIFIED,
            ).inspectedTaskNameForNavigation(),
        )
        assertNull(
            inspectionPart(
                toolName = "tasks.cancel",
                arguments = mapOf("name" to "每日回顾"),
                result = "任务“每日回顾”：计划已取消，不会再执行。",
                verificationStatus = MessageToolVerificationStatus.READABLE_ONLY,
            ).inspectedTaskNameForNavigation(),
        )
    }

    @Test
    fun currentWorkflowResolutionRequiresOneExactNameMatch() {
        val target = workflow(id = "workflow-target", name = "每日回顾")

        assertEquals("workflow-target", resolveInspectedWorkflowId(listOf(target), "每日回顾"))
        assertNull(resolveInspectedWorkflowId(listOf(target), "每日回顾 "))
        assertNull(resolveInspectedWorkflowId(emptyList(), "每日回顾"))
        assertNull(
            resolveInspectedWorkflowId(
                listOf(target, workflow(id = "workflow-duplicate", name = "每日回顾")),
                "每日回顾",
            ),
        )
    }

    private fun inspectionPart(
        toolName: String = "tasks.inspect",
        arguments: Map<String, String> = mapOf("name" to " 每日回顾 "),
        result: String = "任务最近运行\n任务：每日回顾 · 已启用",
        success: Boolean = true,
        verificationStatus: MessageToolVerificationStatus = MessageToolVerificationStatus.READABLE_ONLY,
    ) = MessagePart.Tool(
        id = "tool-part",
        toolName = toolName,
        arguments = arguments,
        result = result,
        success = success,
        verificationStatus = verificationStatus,
        memoryIdsUsed = emptyList(),
    )

    private fun workflow(id: String, name: String) = WorkflowRecord(
        id = id,
        name = name,
        goal = "任务目标",
        enabled = true,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
