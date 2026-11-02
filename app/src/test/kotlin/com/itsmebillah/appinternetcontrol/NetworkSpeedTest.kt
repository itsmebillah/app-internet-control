package com.itsmebillah.appinternetcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkSpeedTest {
    @Test
    fun calculatesAggregateRatesFromMonotonicCounterDeltas() {
        val rates = NetworkSpeedCalculator.calculate(
            NetworkCounters(1_000, 500, 1_000),
            NetworkCounters(5_000, 1_500, 3_000),
        )

        assertEquals(2_000.0, rates?.downloadBytesPerSecond ?: -1.0, 0.0)
        assertEquals(500.0, rates?.uploadBytesPerSecond ?: -1.0, 0.0)
    }

    @Test
    fun rejectsUnsupportedCountersAndCounterResets() {
        assertNull(NetworkSpeedCalculator.calculate(
            NetworkCounters(-1, 0, 1_000),
            NetworkCounters(100, 100, 2_000),
        ))
        assertNull(NetworkSpeedCalculator.calculate(
            NetworkCounters(1_000, 1_000, 1_000),
            NetworkCounters(500, 1_500, 2_000),
        ))
    }

    @Test
    fun rejectsNonPositiveElapsedTime() {
        assertNull(NetworkSpeedCalculator.calculate(
            NetworkCounters(10, 10, 2_000),
            NetworkCounters(20, 20, 2_000),
        ))
    }

    @Test
    fun formatsRatesWithReadableBinaryUnits() {
        assertEquals("700 B/s", NetworkRateFormatter.format(700.0))
        assertEquals("2.0 KB/s", NetworkRateFormatter.format(2_048.0))
        assertEquals("1.5 MB/s", NetworkRateFormatter.format(1_572_864.0))
    }
}
