package `in`.odograph.tracker.alert

data class AlertConfig(
    /** Zero or less disables the feature entirely. */
    val limitKmh: Float,
    /** Half-width of the hysteresis band around the limit. */
    val marginKmh: Float = 3f,
    /** How long speed must stay above the band before the audible alert fires. */
    val sustainMs: Long = 3_000,
    /** Minimum gap between audible alerts while still over. */
    val repeatMs: Long = 25_000
)

enum class AlertPhase { OK, PENDING, ALERTING }

data class AlertOutput(
    val overLimit: Boolean,
    val sound: Boolean,
    val phase: AlertPhase
)

/**
 * Decides when to warn about overspeeding.
 *
 * The obvious implementation — sound whenever speed exceeds the limit — is unusable, because
 * GNSS speed jitters by two or three km/h and cruising at exactly the limit then produces an
 * alert every second. Three mechanisms fix three distinct failures: a hysteresis band stops
 * flapping, a sustain window ignores a brief overtake, and a repeat interval turns nagging into
 * reminding.
 *
 * The two output channels deliberately fire at different times. [AlertOutput.overLimit] drives
 * the gauge colour and goes true the instant the limit is crossed, because a colour change costs
 * no attention. [AlertOutput.sound] waits out the sustain window, so the intrusive channel only
 * fires if the cheap one was not acted on.
 *
 * Pure and clock-injected, so every timing rule is testable without waiting in real time.
 */
class SpeedAlert(private var config: AlertConfig) {

    private var phase = AlertPhase.OK
    private var pendingSinceMs = 0L
    private var lastSoundMs = 0L

    fun reconfigure(newConfig: AlertConfig) {
        config = newConfig
        phase = AlertPhase.OK
    }

    fun update(speedKmh: Float, nowMs: Long): AlertOutput {
        if (config.limitKmh <= 0f) {
            phase = AlertPhase.OK
            return AlertOutput(overLimit = false, sound = false, phase = AlertPhase.OK)
        }

        val upper = config.limitKmh + config.marginKmh
        val lower = config.limitKmh - config.marginKmh
        var sound = false

        when {
            speedKmh >= upper -> when (phase) {
                AlertPhase.OK -> {
                    phase = AlertPhase.PENDING
                    pendingSinceMs = nowMs
                }
                AlertPhase.PENDING ->
                    if (nowMs - pendingSinceMs >= config.sustainMs) {
                        phase = AlertPhase.ALERTING
                        lastSoundMs = nowMs
                        sound = true
                    }
                AlertPhase.ALERTING ->
                    if (nowMs - lastSoundMs >= config.repeatMs) {
                        lastSoundMs = nowMs
                        sound = true
                    }
            }

            speedKmh <= lower -> phase = AlertPhase.OK

            // Inside the band the state is held: this is what stops the alert flapping while
            // cruising at the limit.
            else -> Unit
        }

        return AlertOutput(overLimit = phase != AlertPhase.OK, sound = sound, phase = phase)
    }
}
