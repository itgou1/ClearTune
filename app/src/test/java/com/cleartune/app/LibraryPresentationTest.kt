package com.cleartune.app

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPresentationTest {
    private val timeZone = TimeZone.getTimeZone("Asia/Shanghai")

    @Test
    fun syncTimeUsesChineseRelativeFormatting() {
        val now = timestamp(2026, Calendar.AUGUST, 31, 19, 30)

        assertEquals(
            "今天 06:27",
            formatLibrarySyncTime(
                epochMillis = timestamp(2026, Calendar.AUGUST, 31, 6, 27),
                nowMillis = now,
                timeZone = timeZone,
            ),
        )
        assertEquals(
            "8月30日 23:10",
            formatLibrarySyncTime(
                epochMillis = timestamp(2026, Calendar.AUGUST, 30, 23, 10),
                nowMillis = now,
                timeZone = timeZone,
            ),
        )
        assertEquals(
            "2025年12月1日 08:00",
            formatLibrarySyncTime(
                epochMillis = timestamp(2025, Calendar.DECEMBER, 1, 8, 0),
                nowMillis = now,
                timeZone = timeZone,
            ),
        )
    }

    private fun timestamp(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(timeZone).apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis
}
