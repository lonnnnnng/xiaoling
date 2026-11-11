package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.InstalledAppDirectoryReadResult
import com.longdev.xiaoling.device.DeviceAgentHealthState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PersonalTaskEnvironmentCoordinatorTest {
    @Test fun newerRefreshAndInvalidationRejectNonCooperativeOldResult() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val coordinator = PersonalTaskEnvironmentCoordinator(this) {
            if (++calls == 1) withContext(NonCancellable) { release.await() }
            PersonalTaskEnvironment(InstalledAppDirectoryReadResult.Failed, DeviceAgentHealthState.READY)
        }
        val first = mutableListOf<PersonalTaskEnvironment>()
        coordinator.refresh(first::add)
        runCurrent()
        val second = mutableListOf<PersonalTaskEnvironment>()
        coordinator.refresh(second::add).join()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(PersonalTaskEnvironment()), first)
        assertEquals(2, second.size)
        coordinator.invalidate(second::add)
        assertEquals(PersonalTaskEnvironment(), second.last())
    }
}
