package app.eightcloud.gallery.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedMeterTest {

    @Test
    fun noSamplesMeansZeroRate() {
        val meter = SpeedMeter { 0L }
        assertEquals(0.0, meter.bytesPerSecond(), 0.001)
        assertEquals(-1L, meter.etaSeconds(1000))
    }

    @Test
    fun constantRateIsMeasured() {
        var t = 0L
        val meter = SpeedMeter { t }
        meter.add(0L)
        t = 5_000L
        meter.add(5_000_000L) // 5 MB in 5 s = 1 MB/s
        assertEquals(1_000_000.0, meter.bytesPerSecond(), 1.0)
        assertEquals(10L, meter.etaSeconds(10_000_000L))
    }

    @Test
    fun oldSamplesFallOutOfWindow() {
        var t = 0L
        val meter = SpeedMeter(windowMs = 10_000L) { t }
        meter.add(0L)
        t = 9_000L
        meter.add(9_000_000L)
        // Jump far ahead: only the recent segment should count.
        t = 20_000L
        meter.add(11_000_000L) // +2 MB over the last 11 s window start... window keeps t=9000..20000
        val rate = meter.bytesPerSecond()
        // Samples kept: (9000, 9e6) and (20000, 11e6) -> 2 MB / 11 s
        assertEquals(2_000_000.0 / 11.0, rate, 1.0)
    }

    @Test
    fun etaIsZeroWhenNothingRemains() {
        var t = 0L
        val meter = SpeedMeter { t }
        meter.add(0L)
        t = 1_000L
        meter.add(1000L)
        assertEquals(0L, meter.etaSeconds(0L))
    }

    @Test
    fun rateNeverNegative() {
        var t = 0L
        val meter = SpeedMeter { t }
        meter.add(100L)
        t = 1_000L
        meter.add(100L)
        assertTrue(meter.bytesPerSecond() >= 0.0)
    }
}

class BackupProgressFormatTest {

    @Test
    fun speedLabels() {
        assertEquals("512 B/s", BackupProgress.formatSpeed(512.0))
        assertEquals("5 KB/s", BackupProgress.formatSpeed(5_000.0))
        assertEquals("1.5 MB/s", BackupProgress.formatSpeed(1_500_000.0))
    }

    @Test
    fun etaLabels() {
        assertEquals("", BackupProgress.formatEta(-1))
        assertEquals("<1 min", BackupProgress.formatEta(30))
        assertEquals("~5 min", BackupProgress.formatEta(300))
        assertEquals("~1 h 5 min", BackupProgress.formatEta(3_900))
    }
}
