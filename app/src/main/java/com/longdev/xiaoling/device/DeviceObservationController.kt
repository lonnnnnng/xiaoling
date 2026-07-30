package com.longdev.xiaoling.device

import java.util.UUID
import kotlinx.coroutines.delay

interface DeviceAccessibilityGateway {
    fun isServiceAuthorized(): Boolean
    fun isServiceConnected(): Boolean
    fun currentWindowGeneration(): Long
    suspend fun captureRawWindow(): RawDeviceWindow?
    suspend fun launchApp(packageName: String): Boolean
    fun isHomePackage(packageName: String): Boolean
    suspend fun performGlobalAction(action: DeviceGlobalAction): Boolean
    suspend fun performNodeAction(
        expectedWindowGeneration: Long,
        nodePath: List<Int>,
        expectedFingerprint: String,
        action: DeviceNodeAction,
        text: String? = null,
        direction: DeviceScrollDirection? = null,
    ): RawDeviceActionResult
}

interface DeviceSnapshotProvider {
    fun health(): DeviceAgentHealthState
    suspend fun capture(): DeviceSnapshotCapture
}

class DeviceObservationController(
    private val agentEnabled: () -> Boolean,
    private val gateway: DeviceAccessibilityGateway,
    private val snapshotPolicy: DeviceSnapshotPolicy = DeviceSnapshotPolicy(),
    private val referenceStore: DeviceNodeReferenceStore = DeviceNodeReferenceStore(),
    private val actionPolicy: DeviceActionPolicy = DeviceActionPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val snapshotIdFactory: () -> String = { "device-snapshot-${UUID.randomUUID()}" },
) : DeviceController {
    override fun health(): DeviceAgentHealthState {
        return DeviceAgentHealthPolicy.evaluate(
            agentEnabled = agentEnabled(),
            serviceAuthorized = gateway.isServiceAuthorized(),
            serviceConnected = gateway.isServiceConnected(),
        )
    }

    override fun inspectReference(snapshotId: String, ref: String): DeviceReferenceInspection {
        val generation = gateway.currentWindowGeneration()
        val current = referenceStore.resolve(snapshotId, ref, generation, clock())
            as? DeviceNodeReferenceResolution.Current
        return DeviceReferenceInspection(
            currentWindowGeneration = generation,
            matched = current != null,
            target = current?.let {
                DeviceReferenceTargetInspection(
                    enabled = it.enabled,
                    editable = it.editable,
                    redacted = it.redacted,
                    actions = it.actions,
                )
            },
        )
    }

    override suspend fun capture(): DeviceSnapshotCapture {
        when (health()) {
            DeviceAgentHealthState.AGENT_DISABLED -> return failedAndClearReferences(
                DeviceSnapshotFailure.AGENT_DISABLED,
                "设备 Agent 尚未启用，请先在设置中明确开启",
            )
            DeviceAgentHealthState.ACCESSIBILITY_NOT_AUTHORIZED -> return failedAndClearReferences(
                DeviceSnapshotFailure.ACCESSIBILITY_NOT_AUTHORIZED,
                "无障碍服务未授权或授权已失效，请前往系统设置重新确认",
            )
            DeviceAgentHealthState.SERVICE_DISCONNECTED -> return failedAndClearReferences(
                DeviceSnapshotFailure.SERVICE_DISCONNECTED,
                "无障碍服务已授权但尚未连接，请稍后刷新或重新启用服务",
            )
            DeviceAgentHealthState.READY -> Unit
        }
        val rawWindow = gateway.captureRawWindow()
            ?: return failedAndClearReferences(DeviceSnapshotFailure.NO_ACTIVE_WINDOW, "当前没有可读取的活动窗口")
        if (gateway.currentWindowGeneration() != rawWindow.generation) {
            return failedAndClearReferences(DeviceSnapshotFailure.WINDOW_CHANGED, "页面在读取期间已变化，请重新获取快照")
        }
        return when (
            val assessment = snapshotPolicy.build(
                window = rawWindow,
                snapshotId = snapshotIdFactory(),
                nowMillis = clock(),
            )
        ) {
            is DeviceSnapshotAssessment.Blocked -> {
                val failure = when (assessment.reason) {
                    DeviceSnapshotBlockReason.PRIVATE_APPLICATION -> DeviceSnapshotFailure.PRIVATE_APPLICATION
                    DeviceSnapshotBlockReason.SENSITIVE_WINDOW -> DeviceSnapshotFailure.SENSITIVE_WINDOW
                }
                failedAndClearReferences(failure, assessment.message)
            }
            is DeviceSnapshotAssessment.Available -> {
                referenceStore.replace(
                    snapshotId = assessment.snapshot.snapshotId,
                    windowGeneration = assessment.snapshot.windowGeneration,
                    expiresAt = assessment.snapshot.expiresAt,
                    references = assessment.references,
                )
                DeviceSnapshotCapture.Success(assessment.snapshot, assessment.references)
            }
        }
    }

    fun clearReferences() {
        referenceStore.clear()
    }

    override suspend fun openApp(packageName: String): DeviceActionCapture {
        healthFailureOrNull()?.let { return it }
        if (!actionPolicy.isAppAllowed(packageName)) {
            return actionFailed(DeviceActionFailure.APP_NOT_ALLOWED, "当前仅允许打开首批已验证应用：$packageName 不在白名单")
        }
        val beforeGeneration = gateway.currentWindowGeneration()
        if (!gateway.launchApp(packageName)) {
            return actionFailed(DeviceActionFailure.APP_NOT_AVAILABLE, "目标应用未安装、没有可启动入口或启动被系统拒绝")
        }
        return captureAfterAction(
            action = "open_app",
            beforeSnapshotId = null,
            beforeGeneration = beforeGeneration,
            verify = { capture ->
                PostActionVerification(verified = capture.snapshot.packageName == packageName)
            },
            successMessage = "已打开允许列表中的应用，并重新观察到目标前台窗口",
        )
    }

    override suspend fun back(): DeviceActionCapture = performGlobalAction(DeviceGlobalAction.BACK)

    override suspend fun home(): DeviceActionCapture = performGlobalAction(DeviceGlobalAction.HOME)

    override suspend fun tap(snapshotId: String, ref: String): DeviceActionCapture {
        return performReferencedAction(snapshotId, ref, DeviceNodeAction.TAP)
    }

    override suspend fun typeText(snapshotId: String, ref: String, text: String): DeviceActionCapture {
        actionPolicy.validateTextInput(text)?.let { reason ->
            return actionFailed(DeviceActionFailure.SENSITIVE_INPUT, reason)
        }
        return performReferencedAction(snapshotId, ref, DeviceNodeAction.TYPE_TEXT, text = text)
    }

    override suspend fun swipe(
        snapshotId: String,
        ref: String,
        direction: DeviceScrollDirection,
    ): DeviceActionCapture {
        return performReferencedAction(snapshotId, ref, DeviceNodeAction.SWIPE, direction = direction)
    }

    private suspend fun performGlobalAction(action: DeviceGlobalAction): DeviceActionCapture {
        healthFailureOrNull()?.let { return it }
        val beforeGeneration = gateway.currentWindowGeneration()
        val beforeSnapshot = (capture() as? DeviceSnapshotCapture.Success)?.snapshot
        if (!gateway.performGlobalAction(action)) {
            return actionFailed(DeviceActionFailure.ACTION_FAILED, "系统拒绝执行设备动作：${action.name.lowercase()}")
        }
        return captureAfterAction(
            action = action.name.lowercase(),
            beforeSnapshotId = beforeSnapshot?.snapshotId,
            beforeGeneration = beforeGeneration,
            verify = { capture ->
                val after = capture.snapshot
                PostActionVerification(
                    verified = when (action) {
                        DeviceGlobalAction.HOME -> gateway.isHomePackage(after.packageName)
                        DeviceGlobalAction.BACK -> beforeSnapshot == null ||
                            after.packageName != beforeSnapshot.packageName ||
                            after.windowId != beforeSnapshot.windowId ||
                            after.windowGeneration != beforeSnapshot.windowGeneration
                    },
                )
            },
            successMessage = "系统导航动作已执行，并完成后置界面观察",
        )
    }

    private suspend fun performReferencedAction(
        snapshotId: String,
        ref: String,
        action: DeviceNodeAction,
        text: String? = null,
        direction: DeviceScrollDirection? = null,
    ): DeviceActionCapture {
        healthFailureOrNull()?.let { return it }
        val beforeGeneration = gateway.currentWindowGeneration()
        val resolution = referenceStore.resolve(snapshotId, ref, beforeGeneration, clock())
        val current = when (resolution) {
            is DeviceNodeReferenceResolution.Current -> resolution
            DeviceNodeReferenceResolution.SnapshotNotFound ->
                return actionFailed(DeviceActionFailure.SNAPSHOT_NOT_FOUND, "节点快照不存在或已被新观察替换，请重新获取 snapshot")
            DeviceNodeReferenceResolution.ReferenceNotFound ->
                return actionFailed(DeviceActionFailure.REFERENCE_NOT_FOUND, "节点引用不存在，请重新获取 snapshot")
            DeviceNodeReferenceResolution.Expired ->
                return actionFailed(DeviceActionFailure.REFERENCE_EXPIRED, "节点引用已过期，请重新获取 snapshot")
            DeviceNodeReferenceResolution.WindowChanged ->
                return actionFailed(DeviceActionFailure.WINDOW_CHANGED, "页面已变化，旧节点引用不可继续使用")
        }
        if (action !in current.actions) {
            return actionFailed(DeviceActionFailure.ACTION_NOT_SUPPORTED, "该节点不支持 ${action.name.lowercase()} 动作")
        }
        val rawResult = gateway.performNodeAction(
            expectedWindowGeneration = beforeGeneration,
            nodePath = current.nodePath,
            expectedFingerprint = current.fingerprint,
            action = action,
            text = text,
            direction = direction,
        )
        when (rawResult) {
            RawDeviceActionResult.Performed -> Unit
            RawDeviceActionResult.WindowChanged ->
                return actionFailed(DeviceActionFailure.WINDOW_CHANGED, "页面在动作执行前已变化，请重新获取 snapshot")
            RawDeviceActionResult.NodeNotFound ->
                return actionFailed(DeviceActionFailure.NODE_NOT_FOUND, "节点已消失，请重新获取 snapshot")
            RawDeviceActionResult.NodeChanged ->
                return actionFailed(DeviceActionFailure.NODE_CHANGED, "节点内容或位置已变化，请重新获取 snapshot")
            RawDeviceActionResult.ActionNotSupported ->
                return actionFailed(DeviceActionFailure.ACTION_NOT_SUPPORTED, "当前节点已不再支持该动作")
            RawDeviceActionResult.Failed ->
                return actionFailed(DeviceActionFailure.ACTION_FAILED, "Android Accessibility 动作执行失败")
        }
        return captureAfterAction(
            action = action.name.lowercase(),
            beforeSnapshotId = snapshotId,
            beforeGeneration = beforeGeneration,
            verify = { capture ->
                val after = capture.snapshot
                when (action) {
                    DeviceNodeAction.TYPE_TEXT -> {
                        val expected = text.orEmpty()
                        val targetReference = capture.references.singleOrNull { reference ->
                            reference.nodePath == current.nodePath
                        }
                        val targetNode = targetReference?.let { reference ->
                            after.nodes.singleOrNull { node -> node.ref == reference.ref }
                        }
                        val readBack = targetNode?.let { node ->
                            DeviceTypeTextReadBack(
                                nodePath = current.nodePath.toList(),
                                text = node.text,
                            )
                        }
                        // long: 文本输入只能由原节点路径的精确回读证明；页面其他位置出现相同文本不能替代目标输入框的结果。
                        PostActionVerification(
                            verified = readBack?.text == expected,
                            typeTextReadBack = readBack,
                        )
                    }
                    DeviceNodeAction.TAP,
                    DeviceNodeAction.SWIPE,
                    -> PostActionVerification(verified = after.windowGeneration != beforeGeneration)
                }
            },
            successMessage = "节点动作已执行，并完成后置界面观察",
        )
    }

    private suspend fun captureAfterAction(
        action: String,
        beforeSnapshotId: String?,
        beforeGeneration: Long,
        verify: (DeviceSnapshotCapture.Success) -> PostActionVerification,
        successMessage: String,
    ): DeviceActionCapture {
        waitForWindowSettled(beforeGeneration)
        val after = when (val capture = captureAfterWindowTransition()) {
            is DeviceSnapshotCapture.Success -> capture
            is DeviceSnapshotCapture.Failed -> {
                return actionFailed(
                    DeviceActionFailure.POST_ACTION_OBSERVATION_FAILED,
                    "动作已发送，但后置观察失败：${capture.message}",
                )
            }
        }
        val verification = verify(after)
        return DeviceActionCapture.Success(
            DeviceActionOutcome(
                action = action,
                beforeSnapshotId = beforeSnapshotId,
                afterSnapshot = after.snapshot,
                verified = verification.verified,
                message = if (verification.verified) successMessage else "动作已发送，但后置观察不足以证明界面已按预期变化",
                typeTextReadBack = verification.typeTextReadBack,
            ),
        )
    }

    private suspend fun captureAfterWindowTransition(): DeviceSnapshotCapture {
        repeat(POST_ACTION_CAPTURE_ATTEMPTS) { attempt ->
            val capture = capture()
            val retryable = capture is DeviceSnapshotCapture.Failed && capture.reason in setOf(
                DeviceSnapshotFailure.NO_ACTIVE_WINDOW,
                DeviceSnapshotFailure.WINDOW_CHANGED,
            )
            if (!retryable || attempt == POST_ACTION_CAPTURE_ATTEMPTS - 1) return capture
            // long: 应用首次启动或系统权限页切换时会短暂没有 rootInActiveWindow；只重试瞬时窗口状态，隐私拒绝和授权失效仍立即失败。
            delay(POST_ACTION_CAPTURE_RETRY_DELAY_MS)
        }
        error("后置观察重试次数必须大于零")
    }

    private suspend fun waitForWindowSettled(initialGeneration: Long) {
        var previous = initialGeneration
        var observedChange = false
        var stablePolls = 0
        repeat(25) {
            delay(100)
            val current = gateway.currentWindowGeneration()
            if (current != initialGeneration) observedChange = true
            if (observedChange && current == previous) {
                stablePolls += 1
                if (stablePolls >= 2) return
            } else {
                previous = current
                stablePolls = 0
            }
        }
    }

    private fun healthFailureOrNull(): DeviceActionCapture.Failed? {
        return when (health()) {
            DeviceAgentHealthState.AGENT_DISABLED -> actionFailed(
                DeviceActionFailure.AGENT_DISABLED,
                "设备 Agent 尚未启用，请先在设置中明确开启",
            )
            DeviceAgentHealthState.ACCESSIBILITY_NOT_AUTHORIZED -> actionFailed(
                DeviceActionFailure.ACCESSIBILITY_NOT_AUTHORIZED,
                "无障碍服务未授权或授权已失效，请前往系统设置重新确认",
            )
            DeviceAgentHealthState.SERVICE_DISCONNECTED -> actionFailed(
                DeviceActionFailure.SERVICE_DISCONNECTED,
                "无障碍服务已授权但尚未连接，请稍后刷新或重新启用服务",
            )
            DeviceAgentHealthState.READY -> null
        }
    }

    private fun actionFailed(reason: DeviceActionFailure, message: String): DeviceActionCapture.Failed {
        // long: 动作失败后旧 ref 不再可信；即使 Android 没有上报窗口事件，也要求下一次操作从新的观察开始。
        referenceStore.clear()
        return DeviceActionCapture.Failed(reason, message)
    }

    private fun failedAndClearReferences(reason: DeviceSnapshotFailure, message: String): DeviceSnapshotCapture.Failed {
        // long: 任一失败都代表当前页面证据不可继续信任；立即撤销旧 ref，避免后续动作阶段误用上一次成功观察留下的节点路径。
        referenceStore.clear()
        return DeviceSnapshotCapture.Failed(reason, message)
    }

    private companion object {
        const val POST_ACTION_CAPTURE_ATTEMPTS = 6
        const val POST_ACTION_CAPTURE_RETRY_DELAY_MS = 100L
    }

    private data class PostActionVerification(
        val verified: Boolean,
        val typeTextReadBack: DeviceTypeTextReadBack? = null,
    )
}
