package app.eightcloud.gallery.vlm

/** Pure pause rules for the long pass. Heat has hysteresis so it does not flap. */
object VlmGate {
    const val HOT_C = 45f
    const val COOL_C = 41f
    const val LOW_BATTERY_PCT = 15

    fun hotNext(wasHot: Boolean, tempC: Float, thermalStatus: Int): Boolean =
        if (wasHot) !(tempC <= COOL_C && thermalStatus < 3) else (tempC >= HOT_C || thermalStatus >= 3)

    fun lowBattery(charging: Boolean, pct: Int): Boolean = !charging && pct < LOW_BATTERY_PCT
}
