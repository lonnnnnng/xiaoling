package com.longdev.xiaoling.system

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProcessExitObservationStoreInstrumentedTest {
    private lateinit var database: XiaoLingDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun collectDeduplicatesSystemHistoryAndKeepsOnlyLatestThirtyEntries() = runBlocking {
        val exits = (1L..35L).map { timestamp ->
            rawExit(timestamp = timestamp, reasonCode = if (timestamp == 35L) 3 else 10)
        }
        val store = RoomProcessExitObservationStore(
            database = database,
            source = ProcessExitObservationSource {
                ProcessExitObservationBatch(
                    apiSupported = true,
                    lowMemoryReportSupported = true,
                    exits = exits + exits.last(),
                )
            },
            clock = { 100L },
        )

        store.collect()
        store.collect()
        val stored = store.latest()

        assertEquals(30, stored.size)
        assertEquals((35L downTo 6L).toList(), stored.map { it.raw.timestamp })
        assertEquals(ProcessExitEvidenceKind.DIRECT_LOW_MEMORY, stored.first().evidenceKind)
        assertEquals(100L, stored.first().observedAt)
    }

    @Test
    fun unsupportedApiDoesNotInventProcessExitRows() = runBlocking {
        val store = RoomProcessExitObservationStore(
            database = database,
            source = ProcessExitObservationSource {
                ProcessExitObservationBatch(
                    apiSupported = false,
                    lowMemoryReportSupported = false,
                    exits = listOf(rawExit(timestamp = 1L, reasonCode = 3)),
                )
            },
        )

        val result = store.collect()

        assertEquals(false, result.apiSupported)
        assertEquals(emptyList<ProcessExitObservation>(), store.latest())
    }

    private fun rawExit(timestamp: Long, reasonCode: Int) = RawProcessExitObservation(
        timestamp = timestamp,
        processName = "com.longdev.xiaoling",
        pid = timestamp.toInt(),
        reasonCode = reasonCode,
        status = 0,
        importance = 400,
        pssKb = 1_000L + timestamp,
        rssKb = 2_000L + timestamp,
    )
}
