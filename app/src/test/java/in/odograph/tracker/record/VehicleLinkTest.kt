package `in`.odograph.tracker.record

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * The one door to the car's servers. Nothing here touches the network: these pin which
 * implementation a setting selects, and that a bad setting cannot select nothing.
 */
class VehicleLinkTest {

    @Test
    fun `the default is the protocol the library speaks today`() {
        val link = VehicleLink.create(VehicleLink.TAP_GATEWAY, "9876543210", "secret", null)

        assertThat(link).isInstanceOf(TapGatewayLink::class.java)
        assertThat(link.kind).isEqualTo("tap")
    }

    /**
     * A mistyped or unknown protocol on a car touchscreen must degrade to what worked yesterday,
     * not to a battery tile that stays blank until somebody notices.
     */
    @Test
    fun `an unknown protocol falls back rather than failing`() {
        listOf("", "  ", "iov2", "TAP ", "nonsense").forEach { kind ->
            val link = VehicleLink.create(kind, "9876543210", "secret", null)
            assertThat(link).`as`("kind %s", kind).isInstanceOf(TapGatewayLink::class.java)
        }
    }

    @Test
    fun `every known protocol can be created`() {
        VehicleLink.KNOWN.forEach { kind ->
            assertThat(VehicleLink.create(kind, "9876543210", "secret", "MYVIN").kind).isEqualTo(kind)
        }
    }
}
