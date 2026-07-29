package com.longdev.xiaoling.ui

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AnswerabilityShadowObservationWindowGateTest {
    @Test
    fun concurrentConsumersCanStartOnlyOneObservation() = runTest {
        val enabled = AtomicBoolean(true)
        val gate = AnswerabilityShadowObservationWindowGate()

        val results = List(20) {
            async(Dispatchers.Default) {
                gate.tryConsume(
                    isEnabled = enabled::get,
                    consume = { enabled.set(false) },
                )
            }
        }.awaitAll()

        assertEquals(1, results.count { it })
        assertFalse(enabled.get())
    }
}
