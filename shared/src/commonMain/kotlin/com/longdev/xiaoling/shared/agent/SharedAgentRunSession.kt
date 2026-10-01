package com.longdev.xiaoling.shared.agent

/**
 * long: Session Contract 只保存跨入口恢复所需的身份、状态和事件边界，不携带 Android Context、Room、凭据或平台执行对象。
 */
data class SharedAgentRunIdentity(
    val runId: String,
    val rootRunId: String = runId,
    val parentRunId: String? = null,
) {
    init {
        require(runId.isNotBlank()) { "Run ID 不能为空" }
        require(rootRunId.isNotBlank()) { "根 Run ID 不能为空" }
        require(parentRunId != runId) { "父 Run 不能引用自身" }
        require(parentRunId == null || rootRunId != runId) {
            "带父 Run 的子任务必须使用独立的 rootRunId"
        }
    }
}

enum class SharedAgentRunState {
    CREATED,
    WAITING_APPROVAL,
    RUNNING,
    WAITING_INPUT,
    CANCEL_REQUESTED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class SharedAgentRunEvent(
    val sequence: Long,
    val type: String,
    val message: String,
)

data class SharedAgentRunSnapshot(
    val identity: SharedAgentRunIdentity,
    val state: SharedAgentRunState,
    val eventSequence: Long,
    val cancelRequested: Boolean,
    val events: List<SharedAgentRunEvent>,
)

object SharedAgentRunEventTypes {
    const val CREATED = "run.created"
    const val STATE_CHANGED = "run.state_changed"
    const val CANCEL_REQUESTED = "run.cancel_requested"
    const val RESTORED = "run.restored"
}

/**
 * long: 统一状态机先在 shared 层冻结，避免 Android、后台、远程和子 Agent 各自定义“完成/取消”语义；终态后拒绝所有迟到状态和事件。
 */
class SharedAgentRunSession private constructor(
    val identity: SharedAgentRunIdentity,
    initialState: SharedAgentRunState,
    initialEventSequence: Long,
    initialEvents: List<SharedAgentRunEvent>,
    initialCancelRequested: Boolean,
) {
    private var currentState = initialState
    private var currentEventSequence = initialEventSequence
    private val eventLog = initialEvents.toMutableList()
    private var currentCancelRequested = initialCancelRequested

    val state: SharedAgentRunState
        get() = currentState

    val cancelRequested: Boolean
        get() = currentCancelRequested

    fun transition(next: SharedAgentRunState, message: String) {
        require(message.isNotBlank()) { "状态事件说明不能为空" }
        require(next != currentState) { "Run 状态不能重复迁移：$next" }
        require(isAllowedTransition(currentState, next)) {
            "不允许的 Run 状态迁移：$currentState -> $next"
        }
        val previous = currentState
        appendEvent(
            type = SharedAgentRunEventTypes.STATE_CHANGED,
            message = "$previous -> $next：$message",
        )
        currentState = next
    }

    fun requestCancel(reason: String): Boolean {
        if (currentState.isTerminal()) return false
        if (currentState == SharedAgentRunState.CANCEL_REQUESTED) return true
        require(reason.isNotBlank()) { "取消原因不能为空" }
        val previous = currentState
        currentCancelRequested = true
        currentState = SharedAgentRunState.CANCEL_REQUESTED
        appendEvent(
            type = SharedAgentRunEventTypes.CANCEL_REQUESTED,
            message = "$previous：$reason",
        )
        return true
    }

    fun appendEvent(type: String, message: String) {
        require(!currentState.isTerminal()) { "Run 已进入终态，不能追加事件" }
        require(type.isNotBlank()) { "事件类型不能为空" }
        require(message.isNotBlank()) { "事件说明不能为空" }
        currentEventSequence += 1
        eventLog += SharedAgentRunEvent(currentEventSequence, type, message)
    }

    fun snapshot(): SharedAgentRunSnapshot = SharedAgentRunSnapshot(
        identity = identity,
        state = currentState,
        eventSequence = currentEventSequence,
        cancelRequested = cancelRequested,
        events = eventLog.toList(),
    )

    companion object {
        fun create(identity: SharedAgentRunIdentity): SharedAgentRunSession {
            return SharedAgentRunSession(
                identity = identity,
                initialState = SharedAgentRunState.CREATED,
                initialEventSequence = 0L,
                initialEvents = emptyList(),
                initialCancelRequested = false,
            ).also {
                it.appendEvent(SharedAgentRunEventTypes.CREATED, "Run Session 已创建")
            }
        }

        fun restore(snapshot: SharedAgentRunSnapshot): SharedAgentRunSession {
            require(snapshot.eventSequence == snapshot.events.maxOfOrNull { it.sequence } ?: 0L) {
                "Run Session 事件序号不连续"
            }
            require(snapshot.events.map { it.sequence }.distinct().size == snapshot.events.size) {
                "Run Session 事件序号重复"
            }
            require(snapshot.events.zipWithNext().all { (left, right) -> right.sequence > left.sequence }) {
                "Run Session 事件序号未递增"
            }
            require(snapshot.events.mapIndexed { index, event -> event.sequence == index + 1L }.all { it }) {
                "Run Session 事件序号存在缺口"
            }
            require(
                when (snapshot.state) {
                    SharedAgentRunState.CANCEL_REQUESTED -> snapshot.cancelRequested
                    SharedAgentRunState.CANCELLED -> true
                    else -> !snapshot.cancelRequested
                },
            ) {
                "Run Session 的取消标记与状态不一致"
            }
            return SharedAgentRunSession(
                identity = snapshot.identity,
                initialState = snapshot.state,
                initialEventSequence = snapshot.eventSequence,
                initialEvents = snapshot.events,
                initialCancelRequested = snapshot.cancelRequested,
            )
        }
    }
}

private fun SharedAgentRunState.isTerminal(): Boolean = when (this) {
    SharedAgentRunState.COMPLETED,
    SharedAgentRunState.FAILED,
    SharedAgentRunState.CANCELLED -> true
    else -> false
}

private fun isAllowedTransition(
    from: SharedAgentRunState,
    to: SharedAgentRunState,
): Boolean = when (from) {
    SharedAgentRunState.CREATED -> to in setOf(
        SharedAgentRunState.WAITING_APPROVAL,
        SharedAgentRunState.RUNNING,
        SharedAgentRunState.FAILED,
    )
    SharedAgentRunState.WAITING_APPROVAL -> to in setOf(
        SharedAgentRunState.RUNNING,
        SharedAgentRunState.WAITING_INPUT,
        SharedAgentRunState.FAILED,
    )
    SharedAgentRunState.RUNNING -> to in setOf(
        SharedAgentRunState.WAITING_APPROVAL,
        SharedAgentRunState.WAITING_INPUT,
        SharedAgentRunState.COMPLETED,
        SharedAgentRunState.FAILED,
    )
    SharedAgentRunState.WAITING_INPUT -> to in setOf(
        SharedAgentRunState.RUNNING,
        SharedAgentRunState.FAILED,
    )
    SharedAgentRunState.CANCEL_REQUESTED -> to == SharedAgentRunState.CANCELLED
    SharedAgentRunState.COMPLETED,
    SharedAgentRunState.FAILED,
    SharedAgentRunState.CANCELLED -> false
}
