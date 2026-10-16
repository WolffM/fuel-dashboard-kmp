package com.angussoftware.fueldashboard.ui.components

import com.angussoftware.fueldashboard.model.ProviderReport
import com.angussoftware.fueldashboard.model.ProviderType
import com.angussoftware.fueldashboard.model.ReportWindow
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class LimitingWindowGaugeTest {

    private val zone = TimeZone.UTC

    // Friday 2026-10-16 13:27 UTC.
    private val now = LocalDateTime(2026, 10, 16, 13, 27).toInstant(zone).toEpochMilliseconds()
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime(2026, 10, day, hour, minute).toInstant(zone).toEpochMilliseconds()

    private val fiveHour = ReportWindow("5-hour", remainingPct = 97, resetsAt = at(16, 14, 50), windowHours = 5.0)
    private val weekly = ReportWindow("Weekly", remainingPct = 2, resetsAt = at(16, 14, 0), windowHours = 168.0)

    private fun report(vararg windows: ReportWindow, limiting: String?) = ProviderReport(
        providerId = "p",
        displayName = "Claude",
        type = ProviderType.WINDOW_CREDIT,
        windows = windows.toList(),
        limitingWindow = limiting,
    )

    @Test
    fun theNamedWindowOwnsTheGauge() {
        assertSame(weekly, limitingWindowOf(report(fiveHour, weekly, limiting = "Weekly")))
    }

    @Test
    fun aSingleWindowOwnsTheGaugeWithoutBeingNamed() {
        assertSame(weekly, limitingWindowOf(report(weekly, limiting = null)))
    }

    @Test
    fun noReadingMeansNoGauge() {
        val unknown = weekly.copy(remainingPct = null)
        assertNull(limitingWindowOf(report(unknown, limiting = null)))
    }

    @Test
    fun aLowWindowIsTheLimitAndSaysWhenItResets() {
        assertEquals(
            "Weekly is the limit · resets 14:00 (in 33m)",
            limitCaption(weekly, multipleWindows = true, now = now, zone = zone),
        )
    }

    @Test
    fun anEmptyWindowIsBlockingAndSaysWhenWorkComesBack() {
        val empty = ReportWindow("5-hour", remainingPct = 0, resetsAt = at(16, 14, 59), windowHours = 5.0)
        assertEquals(
            "Blocked by 5-hour · back 14:59 (in 1h 32m)",
            limitCaption(empty, multipleWindows = true, now = now, zone = zone),
        )
    }

    @Test
    fun aResetOnAnotherDayNamesTheDay() {
        val nextWeek = weekly.copy(remainingPct = 78, resetsAt = at(22, 19, 0))
        assertEquals(
            "Weekly is the limit · resets Thu 19:00 (in 6d 5h)",
            limitCaption(nextWeek, multipleWindows = true, now = now, zone = zone),
        )
    }

    @Test
    fun anEstimatedResetIsMarkedApproximate() {
        val estimated = weekly.copy(resetEstimated = true)
        assertEquals(
            "Weekly is the limit · resets ~14:00 (in 33m)",
            limitCaption(estimated, multipleWindows = true, now = now, zone = zone),
        )
    }

    @Test
    fun aLoneWindowIsNotCalledTheLimit() {
        assertEquals(
            "Weekly · resets 14:00 (in 33m)",
            limitCaption(weekly, multipleWindows = false, now = now, zone = zone),
        )
    }

    @Test
    fun countdownsPastADayAreInDays() {
        assertEquals("6d 5h", formatCountdown(at(22, 18, 27), now))
        assertEquals("23h 59m", formatCountdown(now + (23 * 60 + 59) * 60_000L, now))
    }
}
