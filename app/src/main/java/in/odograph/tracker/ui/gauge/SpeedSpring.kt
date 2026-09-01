package `in`.odograph.tracker.ui.gauge

/**
 * Exponential smoother standing in for a critically-damped spring.
 *
 * Visually it gives the needle mass, which is why it reads as expensive — real instruments have
 * inertia. Mechanically it is a low-pass filter that removes the 2-3 km/h of GNSS jitter that
 * would otherwise make a cruising readout twitch. The thing that makes it beautiful is the same
 * thing that makes it accurate.
 */
class SpeedSpring(private val k: Float = 1.7f) {
    private var value = 0f

    fun update(target: Float, dtS: Float): Float {
        value += (target - value) * minOf(1f, dtS * k)
        return value
    }
}
