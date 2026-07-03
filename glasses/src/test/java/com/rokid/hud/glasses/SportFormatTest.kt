package com.rokid.hud.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Worked-vector tests for [SportFormat], the pure-Kotlin formatter behind the
 * SPORT layout mode. These are the glasses module's first unit tests: they
 * prove the JUnit4 runner is on the plain-JVM test classpath (SportFormat has
 * no android.* imports, so no mockable-android.jar stubs are involved).
 */
class SportFormatTest {

    @Test
    fun formatElapsedRendersHoursMinutesSecondsWithUnboundedHourRollover() {
        assertEquals("0:00:00", SportFormat.formatElapsed(0L))
        assertEquals("0:15:45", SportFormat.formatElapsed(945_000L))
        assertEquals("0:59:59", SportFormat.formatElapsed(3_599_000L))
        assertEquals("1:00:00", SportFormat.formatElapsed(3_600_000L))
    }

    @Test
    fun formatPaceRendersMinutesSecondsPerUnitWithUnsetSentinel() {
        // 294000 ms/km = 4:54 min/km
        assertEquals("4:54", SportFormat.formatPace(294_000L, imperial = false))
        // 294000 x 1.609344 = 473147 ms/mi -> 473 s -> 7:53 min/mi
        assertEquals("7:53", SportFormat.formatPace(294_000L, imperial = true))
        // ap=0 is the phone's below-100m unset sentinel (Pitfall 9)
        assertEquals("--:--", SportFormat.formatPace(0L, imperial = false))
        assertEquals("--:--", SportFormat.formatPace(-5L, imperial = true))
    }

    @Test
    fun paceUnitMatchesImperialToggle() {
        assertEquals("/km", SportFormat.paceUnit(imperial = false))
        assertEquals("/mi", SportFormat.paceUnit(imperial = true))
    }

    @Test
    fun formatSpeedRendersOneDecimalInBothUnitSystems() {
        assertEquals("22.3", SportFormat.formatSpeed(6.2, imperial = false))
        assertEquals("13.9", SportFormat.formatSpeed(6.2, imperial = true))
        assertEquals("0.0", SportFormat.formatSpeed(0.0, imperial = false))
    }

    @Test
    fun speedUnitMatchesImperialToggle() {
        assertEquals("km/h", SportFormat.speedUnit(imperial = false))
        assertEquals("mph", SportFormat.speedUnit(imperial = true))
    }

    @Test
    fun movingDotIsFilledOnlyStrictlyAboveThreshold() {
        assertEquals("●", SportFormat.movingDot(0.8))
        // Strict > 0.7: the threshold itself renders as stopped
        assertEquals("○", SportFormat.movingDot(0.7))
        assertEquals("○", SportFormat.movingDot(0.0))
    }
}
