package com.longdev.xiaoling.system

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ProcessExitObservationPolicyTest {
    @Test
    fun onlySystemReportedLowMemoryIsDirectLmkEvidence() {
        val observation = ProcessExitObservationPolicy.classify(
            raw = rawExit(reasonCode = 3),
            lowMemoryReportSupported = true,
        )

        assertEquals("LOW_MEMORY", observation.reasonName)
        assertEquals(ProcessExitEvidenceKind.DIRECT_LOW_MEMORY, observation.evidenceKind)
    }

    @Test
    fun sigkillIsOnlyCandidateWhenDeviceCannotReportLowMemoryDirectly() {
        val unsupported = ProcessExitObservationPolicy.classify(
            raw = rawExit(reasonCode = 2, status = 9),
            lowMemoryReportSupported = false,
        )
        val supported = ProcessExitObservationPolicy.classify(
            raw = rawExit(reasonCode = 2, status = 9),
            lowMemoryReportSupported = true,
        )

        assertEquals(ProcessExitEvidenceKind.LOW_MEMORY_CANDIDATE, unsupported.evidenceKind)
        assertEquals(ProcessExitEvidenceKind.UNATTRIBUTED, supported.evidenceKind)
    }

    @Test
    fun userAndPackageMaintenanceExitsCannotMasqueradeAsNaturalReclamation() {
        val userRequested = ProcessExitObservationPolicy.classify(
            raw = rawExit(reasonCode = 10),
            lowMemoryReportSupported = true,
        )
        val packageUpdated = ProcessExitObservationPolicy.classify(
            raw = rawExit(reasonCode = 16),
            lowMemoryReportSupported = true,
        )

        assertEquals(ProcessExitEvidenceKind.CONTROLLED_OR_MAINTENANCE, userRequested.evidenceKind)
        assertEquals(ProcessExitEvidenceKind.CONTROLLED_OR_MAINTENANCE, packageUpdated.evidenceKind)
    }

    @Test
    fun unknownFutureReasonRemainsTypedWithoutInventingSystemCause() {
        val observation = ProcessExitObservationPolicy.classify(
            raw = rawExit(reasonCode = 9_999),
            lowMemoryReportSupported = true,
        )

        assertEquals("UNRECOGNIZED", observation.reasonName)
        assertEquals(ProcessExitEvidenceKind.UNATTRIBUTED, observation.evidenceKind)
    }

    @Test
    fun mapsDocumentedAndroidReasonsToStableNamesAndEvidenceFamilies() {
        val names = (0..16).associateWith { reasonCode ->
            ProcessExitObservationPolicy.classify(
                raw = rawExit(reasonCode = reasonCode),
                lowMemoryReportSupported = true,
            ).reasonName
        }

        assertEquals("UNKNOWN", names[0])
        assertEquals("SIGNALED", names[2])
        assertEquals("CRASH", names[4])
        assertEquals("ANR", names[6])
        assertEquals("EXCESSIVE_RESOURCE_USAGE", names[9])
        assertEquals("DEPENDENCY_DIED", names[12])
        assertEquals("FREEZER", names[14])
        assertEquals("PACKAGE_UPDATED", names[16])

        val crash = ProcessExitObservationPolicy.classify(rawExit(4), lowMemoryReportSupported = true)
        val freezer = ProcessExitObservationPolicy.classify(rawExit(14), lowMemoryReportSupported = true)
        assertEquals(ProcessExitEvidenceKind.APP_FAILURE, crash.evidenceKind)
        assertEquals(ProcessExitEvidenceKind.SYSTEM_RESOURCE, freezer.evidenceKind)
    }

    @Test
    fun bestEffortCollectionIsolatesDiagnosticFailure() = runBlocking {
        collectProcessExitObservationsBestEffort {
            error("diagnostic storage unavailable")
        }
    }

    @Test
    fun bestEffortCollectionPreservesCoroutineCancellation() = runBlocking {
        try {
            collectProcessExitObservationsBestEffort {
                throw CancellationException("worker stopped")
            }
            fail("CancellationException should propagate")
        } catch (_: CancellationException) {
            // long: Worker 被系统停止时必须结束协程，旁路诊断不能把取消误当作可忽略的采集失败。
        }
    }

    private fun rawExit(reasonCode: Int, status: Int = 0) = RawProcessExitObservation(
        timestamp = 1_700_000_000_000L,
        processName = "com.longdev.xiaoling",
        pid = 1234,
        reasonCode = reasonCode,
        status = status,
        importance = 400,
        pssKb = 12_345L,
        rssKb = 23_456L,
    )
}
