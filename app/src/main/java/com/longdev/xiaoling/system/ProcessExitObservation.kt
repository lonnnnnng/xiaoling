package com.longdev.xiaoling.system

import android.content.Context
import androidx.room.withTransaction
import com.longdev.xiaoling.data.ProcessExitObservationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.CancellationException

data class RawProcessExitObservation(
    val timestamp: Long,
    val processName: String,
    val pid: Int,
    val reasonCode: Int,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
)

enum class ProcessExitEvidenceKind {
    DIRECT_LOW_MEMORY,
    LOW_MEMORY_CANDIDATE,
    APP_FAILURE,
    SYSTEM_RESOURCE,
    CONTROLLED_OR_MAINTENANCE,
    UNATTRIBUTED,
}

data class ProcessExitObservation(
    val raw: RawProcessExitObservation,
    val reasonName: String,
    val evidenceKind: ProcessExitEvidenceKind,
    val lowMemoryReportSupported: Boolean,
    val observedAt: Long = 0L,
)

data class ProcessExitObservationBatch(
    val apiSupported: Boolean,
    val lowMemoryReportSupported: Boolean,
    val exits: List<RawProcessExitObservation>,
)

data class ProcessExitObservationCollection(
    val apiSupported: Boolean,
    val lowMemoryReportSupported: Boolean,
    val observations: List<ProcessExitObservation>,
)

fun interface ProcessExitObservationSource {
    fun read(): ProcessExitObservationBatch
}

internal suspend fun collectProcessExitObservationsBestEffort(
    collect: suspend () -> Unit,
) {
    try {
        collect()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // long: 系统退出诊断属于旁路证据；平台或存储异常只跳过本次采集，不能阻断聊天、恢复或 Workflow 主流程。
    }
}

class RoomProcessExitObservationStore(
    private val database: XiaoLingDatabase,
    private val source: ProcessExitObservationSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        database = XiaoLingDatabase.getInstance(context.applicationContext),
        source = AndroidProcessExitObservationSource(context.applicationContext),
    )

    private val dao = database.processExitObservationDao()

    suspend fun collect(): ProcessExitObservationCollection {
        val batch = source.read()
        if (!batch.apiSupported) {
            return ProcessExitObservationCollection(
                apiSupported = false,
                lowMemoryReportSupported = false,
                observations = emptyList(),
            )
        }

        val observedAt = clock()
        val observations = batch.exits.map { raw ->
            ProcessExitObservationPolicy.classify(raw, batch.lowMemoryReportSupported)
                .copy(observedAt = observedAt)
        }
        database.withTransaction {
            // long: 系统历史会在每次启动重复返回；稳定 ID + IGNORE 保留首次观察时间，同一事务裁剪到最新 30 条，避免诊断账本无限增长。
            dao.insertAll(observations.map(ProcessExitObservation::toEntity))
            dao.pruneToLatest(MAX_STORED_PROCESS_EXIT_OBSERVATIONS)
        }
        return ProcessExitObservationCollection(
            apiSupported = true,
            lowMemoryReportSupported = batch.lowMemoryReportSupported,
            observations = latest(),
        )
    }

    suspend fun latest(limit: Int = MAX_STORED_PROCESS_EXIT_OBSERVATIONS): List<ProcessExitObservation> {
        return dao.latest(limit.coerceIn(1, MAX_STORED_PROCESS_EXIT_OBSERVATIONS))
            .map(ProcessExitObservationEntity::toObservation)
    }
}

object ProcessExitObservationPolicy {
    fun classify(
        raw: RawProcessExitObservation,
        lowMemoryReportSupported: Boolean,
    ): ProcessExitObservation {
        val evidenceKind = when {
            raw.reasonCode == REASON_LOW_MEMORY -> ProcessExitEvidenceKind.DIRECT_LOW_MEMORY
            !lowMemoryReportSupported && raw.reasonCode == REASON_SIGNALED && raw.status == SIGKILL -> {
                ProcessExitEvidenceKind.LOW_MEMORY_CANDIDATE
            }
            raw.reasonCode in APP_FAILURE_REASONS -> ProcessExitEvidenceKind.APP_FAILURE
            raw.reasonCode in SYSTEM_RESOURCE_REASONS -> ProcessExitEvidenceKind.SYSTEM_RESOURCE
            raw.reasonCode in CONTROLLED_OR_MAINTENANCE_REASONS -> {
                ProcessExitEvidenceKind.CONTROLLED_OR_MAINTENANCE
            }
            else -> ProcessExitEvidenceKind.UNATTRIBUTED
        }
        return ProcessExitObservation(
            raw = raw,
            reasonName = reasonName(raw.reasonCode),
            evidenceKind = evidenceKind,
            lowMemoryReportSupported = lowMemoryReportSupported,
        )
    }

    private fun reasonName(reasonCode: Int): String = when (reasonCode) {
        REASON_UNKNOWN -> "UNKNOWN"
        REASON_EXIT_SELF -> "EXIT_SELF"
        REASON_SIGNALED -> "SIGNALED"
        REASON_LOW_MEMORY -> "LOW_MEMORY"
        REASON_CRASH -> "CRASH"
        REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        REASON_ANR -> "ANR"
        REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        REASON_USER_REQUESTED -> "USER_REQUESTED"
        REASON_USER_STOPPED -> "USER_STOPPED"
        REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        REASON_OTHER -> "OTHER"
        REASON_FREEZER -> "FREEZER"
        REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        else -> "UNRECOGNIZED"
    }

    private val APP_FAILURE_REASONS = setOf(
        REASON_CRASH,
        REASON_CRASH_NATIVE,
        REASON_ANR,
        REASON_INITIALIZATION_FAILURE,
    )

    private val SYSTEM_RESOURCE_REASONS = setOf(
        REASON_EXCESSIVE_RESOURCE_USAGE,
        REASON_FREEZER,
    )

    private val CONTROLLED_OR_MAINTENANCE_REASONS = setOf(
        REASON_EXIT_SELF,
        REASON_PERMISSION_CHANGE,
        REASON_USER_REQUESTED,
        REASON_USER_STOPPED,
        REASON_PACKAGE_STATE_CHANGE,
        REASON_PACKAGE_UPDATED,
    )

    private const val REASON_UNKNOWN = 0
    private const val REASON_EXIT_SELF = 1
    private const val REASON_SIGNALED = 2
    private const val REASON_LOW_MEMORY = 3
    private const val REASON_CRASH = 4
    private const val REASON_CRASH_NATIVE = 5
    private const val REASON_ANR = 6
    private const val REASON_INITIALIZATION_FAILURE = 7
    private const val REASON_PERMISSION_CHANGE = 8
    private const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
    private const val REASON_USER_REQUESTED = 10
    private const val REASON_USER_STOPPED = 11
    private const val REASON_DEPENDENCY_DIED = 12
    private const val REASON_OTHER = 13
    private const val REASON_FREEZER = 14
    private const val REASON_PACKAGE_STATE_CHANGE = 15
    private const val REASON_PACKAGE_UPDATED = 16
    private const val SIGKILL = 9
}

private fun ProcessExitObservation.toEntity(): ProcessExitObservationEntity {
    val raw = raw
    return ProcessExitObservationEntity(
        id = "${raw.timestamp}|${raw.pid}|${raw.reasonCode}|${raw.status}|${raw.processName}",
        timestamp = raw.timestamp,
        processName = raw.processName,
        pid = raw.pid,
        reasonCode = raw.reasonCode,
        reasonName = reasonName,
        status = raw.status,
        importance = raw.importance,
        pssKb = raw.pssKb,
        rssKb = raw.rssKb,
        lowMemoryReportSupported = lowMemoryReportSupported,
        evidenceKind = evidenceKind.name,
        observedAt = observedAt,
    )
}

private fun ProcessExitObservationEntity.toObservation(): ProcessExitObservation {
    return ProcessExitObservation(
        raw = RawProcessExitObservation(
            timestamp = timestamp,
            processName = processName,
            pid = pid,
            reasonCode = reasonCode,
            status = status,
            importance = importance,
            pssKb = pssKb,
            rssKb = rssKb,
        ),
        reasonName = reasonName,
        evidenceKind = runCatching { ProcessExitEvidenceKind.valueOf(evidenceKind) }
            .getOrDefault(ProcessExitEvidenceKind.UNATTRIBUTED),
        lowMemoryReportSupported = lowMemoryReportSupported,
        observedAt = observedAt,
    )
}

const val MAX_STORED_PROCESS_EXIT_OBSERVATIONS = 30
