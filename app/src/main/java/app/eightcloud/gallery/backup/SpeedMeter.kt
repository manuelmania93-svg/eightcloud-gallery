package app.eightcloud.gallery.backup

/**
 * Rolling-window throughput meter. Feed absolute byte counters; it returns
 * bytes/second over the last [windowMs] and an ETA for a remaining byte count.
 * Pure Kotlin so it is unit-testable without Android.
 */
class SpeedMeter(
    private val windowMs: Long = 10_000L,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    private val samples = ArrayDeque<Pair<Long, Long>>() // (timeMs, totalBytes)

    @Synchronized
    fun add(totalBytes: Long) {
        val t = now()
        samples.addLast(t to totalBytes)
        while (samples.size > 2 && t - samples.first().first > windowMs) {
            samples.removeFirst()
        }
    }

    /** Bytes per second over the window; 0 when there is not enough data. */
    @Synchronized
    fun bytesPerSecond(): Double {
        if (samples.size < 2) return 0.0
        val first = samples.first()
        val last = samples.last()
        val spanMs = last.first - first.first
        if (spanMs < 500) return 0.0
        return (last.second - first.second).toDouble() / (spanMs / 1000.0)
    }

    /** Seconds to finish [remainingBytes] at the current rate; -1 when unknown. */
    fun etaSeconds(remainingBytes: Long): Long {
        val rate = bytesPerSecond()
        if (rate <= 0.0 || remainingBytes <= 0) return if (remainingBytes <= 0) 0 else -1
        return (remainingBytes / rate).toLong()
    }
}
