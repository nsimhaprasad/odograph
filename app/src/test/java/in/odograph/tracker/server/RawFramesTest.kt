package `in`.odograph.tracker.server

import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class RawFramesTest {

    private lateinit var dir: java.io.File

    @Before
    fun setUp() {
        dir = java.nio.file.Files.createTempDirectory("raw-frames-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `round trips entries in order, newest last`() {
        RawFrames.record(dir, "status.raw", "AAFF12")
        RawFrames.record(dir, "charge.decoded", "ChargeStatus(isCharging=true)")
        RawFrames.record(dir, "status.raw", "00FFFF0123")

        val entries = RawFrames.read(dir)
        assertThat(entries.map { it.label }).containsExactly("status.raw", "charge.decoded", "status.raw")
        assertThat(entries.last().content).isEqualTo("00FFFF0123")
    }

    @Test
    fun `keeps only the most recent blocks`() {
        repeat(30) { RawFrames.record(dir, "l$it", "x") }

        val entries = RawFrames.read(dir)
        assertThat(entries).hasSize(24)
        assertThat(entries.first().label).isEqualTo("l6")
        assertThat(entries.last().label).isEqualTo("l29")
    }

    @Test
    fun `blank content is not recorded`() {
        RawFrames.record(dir, "status.raw", "   ")

        assertThat(RawFrames.read(dir)).isEmpty()
    }
}