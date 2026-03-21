package io.github.smyrgeorge.freepath.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TimeUtilTest {

    private fun now() = Clock.System.now().toEpochMilliseconds()

    @Test
    fun returnsJustNow_forRecentTimestamp() {
        val epochMs = now() - 30.seconds.inWholeMilliseconds
        assertEquals("just now", formatRelativeTime(epochMs))
    }

    @Test
    fun returnsMinutes_forFiveMinutesAgo() {
        val epochMs = now() - 5.minutes.inWholeMilliseconds
        assertEquals("5m ago", formatRelativeTime(epochMs))
    }

    @Test
    fun returnsHours_forTwoHoursAgo() {
        val epochMs = now() - 2.hours.inWholeMilliseconds
        assertEquals("2h ago", formatRelativeTime(epochMs))
    }

    @Test
    fun returnsDays_forThreeDaysAgo() {
        val epochMs = now() - 3.days.inWholeMilliseconds
        assertEquals("3d ago", formatRelativeTime(epochMs))
    }
}
