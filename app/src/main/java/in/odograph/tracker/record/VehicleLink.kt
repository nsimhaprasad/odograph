package `in`.odograph.tracker.record

import io.windsor.telematics.Status
import io.windsor.telematics.TelematicsClient
import io.windsor.telematics.Vehicle

/**
 * How the app talks to the car's servers — behind one door, so the door can be changed.
 *
 * Everything the app needs from MG is a sign-in and a status frame. Today those are answered by
 * the TAP/gateway protocol the community reverse-engineered from the iSMART app. SAIC is one app
 * store update away from retiring that protocol, and the only thing between the box and a blank
 * battery tile would be a new implementation of this interface. The rest of the app never sees
 * which one is in use, and [Status] keeps its shape: a new backend is mapped into it, not the
 * other way round.
 *
 * Two things this door adds over a raw client, both aimed at one problem — the box is powered by
 * the car, so it restarts on every drive, and a fresh sign-in on every drive is exactly what a
 * verification-code challenge is built to trip:
 *  - a [session] can be saved and handed back through [create], so the box signs in rarely;
 *  - [OtpRequired][io.windsor.telematics.OtpRequiredException] from sign-in is distinct, so the
 *    driver can be shown a prompt for the code instead of a dead battery tile, and [loginWithOtp]
 *    completes the sign-in with it.
 */
interface VehicleLink {

    /** Which protocol this is, for the crumb log and the configure page. */
    val kind: String

    suspend fun login()

    /** Completes a sign-in the server challenged, using the code texted to the phone. */
    suspend fun loginWithOtp(code: String)

    suspend fun vehicles(): List<Vehicle>

    suspend fun status(includeCharge: Boolean): Status

    /** The signed-in session, to persist and hand back next time; null if not signed in. */
    fun session(): SavedSession?

    /** Forget the session, so the next call signs in afresh. */
    fun clearSession()

    /** A completed sign-in, small enough to keep in preferences and survive a restart. */
    data class SavedSession(val uid: String, val token: String)

    companion object {
        /** The protocol the library speaks today. */
        const val TAP_GATEWAY = "tap"

        /** Every protocol this build knows. A settings value outside this list falls back to the first. */
        val KNOWN = listOf(TAP_GATEWAY)

        /**
         * The link for [kind]. Unknown kinds fall back to [TAP_GATEWAY] rather than failing, so a
         * mistyped setting on a car touchscreen degrades to "what worked yesterday" and not to
         * "no battery data until somebody notices". [savedSession] seeds a previously issued
         * session so no sign-in happens until one is actually needed.
         */
        fun create(
            kind: String,
            phone: String,
            password: String,
            vin: String?,
            savedSession: SavedSession? = null,
            onRawResponse: (label: String, content: String) -> Unit = { _, _ -> }
        ): VehicleLink {
            val seed = savedSession?.let { TelematicsClient.Session(it.uid, it.token) }
            val client = TelematicsClient.create(phone, password, vin, seed, onRawResponse)
            return when (kind.trim().lowercase()) {
                TAP_GATEWAY -> TapGatewayLink(client)
                else -> TapGatewayLink(client)
            }
        }
    }
}

/** The TAP v1.1 login, TAP v2.1 status and `api.app/v1` gateway the library speaks now. */
class TapGatewayLink(private val client: TelematicsClient) : VehicleLink {
    override val kind: String get() = VehicleLink.TAP_GATEWAY
    override suspend fun login() = client.login()
    override suspend fun loginWithOtp(code: String) = client.loginWithOtp(code)
    override suspend fun vehicles(): List<Vehicle> = client.vehicles()
    override suspend fun status(includeCharge: Boolean): Status = client.status(includeCharge)
    override fun session(): VehicleLink.SavedSession? =
        client.currentSession()?.let { VehicleLink.SavedSession(it.uid, it.token) }
    override fun clearSession() = client.clearSession()
}
