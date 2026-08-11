package com.longdev.xiaoling.agent

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class AndroidCalendarEventReaderInstrumentedTest {
    @Test
    fun grantedCalendarProviderReadsBackTheSameOccurrenceIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue(
            "请先在小灵的“日历访问”页面授权只读日历",
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val nowMillis = System.currentTimeMillis()
        val reader = AndroidCalendarEventReader(context.contentResolver)
        val listed = reader.listEvents(
            startAtMillis = nowMillis,
            endAtMillis = nowMillis + TimeUnit.DAYS.toMillis(30),
            limit = 20,
        )
        assertTrue(listed is CalendarEventReadResult.Success)
        val occurrence = (listed as CalendarEventReadResult.Success).events.firstOrNull { event ->
            event.startAtMillis > nowMillis
        }
        assumeTrue("未来30天没有可用于 occurrence 回读的日程", occurrence != null)

        val detail = reader.getOccurrence(
            eventId = requireNotNull(occurrence).eventId,
            startAtMillis = occurrence.startAtMillis,
        )

        // long: 答案导航携带的是 occurrence 身份；真实 Provider 回读必须保持实例起止和重复状态一致，不能悄悄退回 Events master。
        assertTrue(detail is CalendarEventDetailReadResult.Success)
        val event = (detail as CalendarEventDetailReadResult.Success).event
        assertEquals(occurrence.eventId, event.eventId)
        assertEquals(occurrence.startAtMillis, event.startAtMillis)
        assertEquals(occurrence.endAtMillis, event.endAtMillis)
        assertEquals(occurrence.allDay, event.allDay)
        assertEquals(occurrence.recurring, event.recurring)
    }

    @Test
    fun grantedCalendarProviderReturnsOnlyUniqueFutureNextOccurrence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue(
            "请先在小灵的“日历访问”页面授权只读日历",
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val nowMillis = System.currentTimeMillis()
        val result = AndroidCalendarEventReader(context.contentResolver).nextEvent(
            nowMillis = nowMillis,
            endAtMillis = nowMillis + TimeUnit.DAYS.toMillis(30),
        )

        // long: 真机只读当前 Provider；唯一结果必须严格位于执行时刻之后，空结果和同刻歧义也是合法的 fail-closed 结论。
        when (result) {
            is CalendarNextEventReadResult.Success -> assertTrue(result.event.startAtMillis > nowMillis)
            CalendarNextEventReadResult.NoUpcomingEvent -> Unit
            is CalendarNextEventReadResult.AmbiguousStartTime -> assertTrue(result.occurrenceCount >= 2)
            else -> error("已授权日历返回失败：$result")
        }
    }

    @Test
    fun grantedCalendarProviderReturnsBoundedChronologicalResult() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue(
            "请先在小灵的“日历访问”页面授权只读日历",
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val startAtMillis = System.currentTimeMillis()
        val result = AndroidCalendarEventReader(context.contentResolver).listEvents(
            startAtMillis = startAtMillis,
            endAtMillis = startAtMillis + TimeUnit.DAYS.toMillis(7),
            limit = 10,
        )

        // long: 真机验收只证明系统 Provider 可被只读查询并遵守数量/时间排序边界，不为测试创建或修改用户日程。
        assertTrue(result is CalendarEventReadResult.Success)
        val events = (result as CalendarEventReadResult.Success).events
        assertTrue(events.size <= 10)
        assertTrue(events.all { event -> event.eventId > 0L })
        assertTrue(events.zipWithNext().all { (left, right) -> left.startAtMillis <= right.startAtMillis })
    }

    @Test
    fun grantedCalendarProviderSearchReturnsOnlyMatchingMinimalEvents() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue(
            "请先在小灵的“日历访问”页面授权只读日历",
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val startAtMillis = System.currentTimeMillis()
        val result = AndroidCalendarEventReader(context.contentResolver).searchEvents(
            startAtMillis = startAtMillis,
            endAtMillis = startAtMillis + TimeUnit.DAYS.toMillis(7),
            query = "__xiaoling_stage149_no_such_title__",
            limit = 10,
        )

        // long: 真机不写入用户日历；用不存在的标题验证真实 Provider 搜索链只返回最小事件集合和稳定空结果。
        assertTrue(result is CalendarEventReadResult.Success)
        assertTrue((result as CalendarEventReadResult.Success).events.isEmpty())
    }
}
