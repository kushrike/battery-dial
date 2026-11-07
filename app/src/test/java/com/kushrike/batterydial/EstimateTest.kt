package com.kushrike.batterydial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EstimateTest {
    private val min = 60_000L

    // Unplugged at 80% at t=0. The first drop (5 min) is a partial percent, then 1% every 10 min.
    private val discharging = listOf(
        Sample(0, 80, false), Sample(5 * min, 79, false), Sample(15 * min, 78, false), Sample(25 * min, 77, false),
    )

    @Test fun noDataMeansNoEstimate() = assertNull(timeLeft(emptyList(), 0, 80, false))

    @Test fun oneMoveIsNotEnough() = assertNull(timeLeft(discharging.take(2), 5 * min, 79, false))

    @Test fun sessionRateSkipsThePartialFirstPercent() = assertEquals(770 * min, timeLeft(discharging, 25 * min, 77, false))

    @Test fun countsDownBetweenLevelChanges() = assertEquals(765 * min, timeLeft(discharging, 30 * min, 77, false))

    @Test fun neverCountsPastTheCurrentPercent() = assertEquals(760 * min, timeLeft(discharging, 60 * min, 77, false))

    @Test fun fallsBackToPastSessionsOfTheSameKind() {
        // Earlier discharge at 1% per 10 min, a charge, then unplugged again with no moves yet.
        val h = listOf(
            Sample(0, 90, false), Sample(10 * min, 89, false), Sample(20 * min, 88, false),
            Sample(30 * min, 88, true), Sample(60 * min, 95, true), Sample(70 * min, 95, false),
        )
        assertEquals(950 * min, timeLeft(h, 70 * min, 95, false))
    }

    @Test fun chargingCountsUpToFull() {
        val h = listOf(Sample(0, 50, true), Sample(1 * min, 51, true), Sample(3 * min, 52, true), Sample(5 * min, 53, true))
        assertEquals(94 * min, timeLeft(h, 5 * min, 53, true))
    }

    @Test fun formatsDurations() {
        assertEquals("2h 11m", formatDuration(131 * min))
        assertEquals("3m", formatDuration(3 * min))
    }
}
