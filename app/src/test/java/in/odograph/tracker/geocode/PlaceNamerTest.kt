package `in`.odograph.tracker.geocode

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Robolectric supplies the real org.json; the JVM stub in android.jar returns nothing useful. */
@RunWith(RobolectricTestRunner::class)
class PlaceNamerTest {

    @Test
    fun `it prefers the neighbourhood and appends the city`() {
        val json = """
            {"address":{"neighbourhood":"Koramangala","suburb":"Bengaluru South",
             "city":"Bengaluru","state":"Karnataka","country":"India"}}
        """.trimIndent()
        assertThat(PlaceNamer.shortNameFrom(json)).isEqualTo("Koramangala, Bengaluru")
    }

    @Test
    fun `it falls back down the list when the neighbourhood is absent`() {
        val json = """{"address":{"road":"Hosur Road","city":"Bengaluru"}}"""
        assertThat(PlaceNamer.shortNameFrom(json)).isEqualTo("Hosur Road, Bengaluru")
    }

    @Test
    fun `it does not repeat itself when the local part is the city`() {
        val json = """{"address":{"town":"Mysuru","city":"Mysuru"}}"""
        assertThat(PlaceNamer.shortNameFrom(json)).isEqualTo("Mysuru")
    }

    @Test
    fun `a city with no local part still yields a name`() {
        assertThat(PlaceNamer.shortNameFrom("""{"address":{"city":"Bengaluru"}}"""))
            .isEqualTo("Bengaluru")
    }

    @Test
    fun `an address with nothing usable yields null rather than an empty string`() {
        assertThat(PlaceNamer.shortNameFrom("""{"address":{"country":"India"}}""")).isNull()
    }

    @Test
    fun `a response with no address at all yields null`() {
        assertThat(PlaceNamer.shortNameFrom("""{"error":"Unable to geocode"}""")).isNull()
    }

    @Test
    fun `malformed json yields null rather than throwing`() {
        assertThat(PlaceNamer.shortNameFrom("not json at all")).isNull()
        assertThat(PlaceNamer.shortNameFrom("")).isNull()
    }

    @Test
    fun `the postal display_name is never used, it is far too long for a car screen`() {
        val json = """
            {"display_name":"12, 5th Block, Koramangala, Bengaluru South, Bengaluru Urban,
             Karnataka, 560095, India","address":{"neighbourhood":"Koramangala","city":"Bengaluru"}}
        """.trimIndent()
        val name = PlaceNamer.shortNameFrom(json)!!
        assertThat(name).doesNotContain("560095")
        assertThat(name.length).isLessThan(40)
    }
}
