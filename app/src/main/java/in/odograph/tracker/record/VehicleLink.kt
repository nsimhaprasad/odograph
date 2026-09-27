package `in`.odograph.tracker.record

import io.windsor.telematics.Status
import io.windsor.telematics.TelematicsClient
import io.windsor.telematics.Vehicle

/**
 * How the app talks to the car's servers — behind one door, so the door can be changed.
 *
 * Everything the app needs from MG is three calls: sign in, list the vehicles, read a status
 * frame. Today those are answered by the TAP/gateway protocol the community reverse-engineered
 * from the iSMART app, impersonating an old iOS build. SAIC is one app store update away from
 * retiring that protocol, and the only thing between the box and a blank battery tile would be a
 * new implementation of this interface. The rest of the app never sees which one is in use.
 *
 * [Status] and its charge frame stay the shape they are: a new backend is mapped *into* them,
 * not the other way round, so the recorder, the frames on disk and the sheet do not change when
 * the wire does.
 */
interface VehicleLink {

    /** Which protocol this is, for the crumb log and the configure page. */
    val kind: String

    suspend fun login()

    suspend fun vehicles(): List<Vehicle>

    suspend fun status(includeCharge: Boolean): Status

    companion object {
        /** The protocol the library speaks today. */
        const val TAP_GATEWAY = "tap"

        /** Every protocol this build knows. A settings value outside this list falls back to the first. */
        val KNOWN = listOf(TAP_GATEWAY)

        /**
         * The link for [kind]. Unknown kinds fall back to [TAP_GATEWAY] rather than failing, so a
         * mistyped setting on a car touchscreen degrades to "what worked yesterday" and not to
         * "no battery data until somebody notices".
         */
        fun create(
            kind: String,
            phone: String,
            password: String,
            vin: String?,
            onRawResponse: (label: String, content: String) -> Unit = { _, _ -> }
        ): VehicleLink = when (kind.trim().lowercase()) {
            TAP_GATEWAY -> TapGatewayLink(TelematicsClient.create(phone, password, vin, onRawResponse = onRawResponse))
            else -> TapGatewayLink(TelematicsClient.create(phone, password, vin, onRawResponse = onRawResponse))
        }
    }
}

/** The TAP v1.1 login, TAP v2.1 status and `api.app/v1` gateway the library speaks now. */
class TapGatewayLink(private val client: TelematicsClient) : VehicleLink {
    override val kind: String get() = VehicleLink.TAP_GATEWAY
    override suspend fun login() = client.login()
    override suspend fun vehicles(): List<Vehicle> = client.vehicles()
    override suspend fun status(includeCharge: Boolean): Status = client.status(includeCharge)
}
