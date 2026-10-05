package dev.rotatebrowser

import dev.rotatebrowser.bridge.VideoState
import dev.rotatebrowser.gesture.SeekMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekMathTest {

    private fun makeState(duration: Double, currentTime: Double = 0.0, isLive: Boolean = false) =
        VideoState(
            frameId = "test",
            playing = true,
            duration = duration,
            currentTime = currentTime,
            isLive = isLive,
            videoW = 1920,
            videoH = 1080,
            rect = floatArrayOf(0f, 0f, 1920f, 1080f),
            dpr = 1f,
            isTop = true,
            origin = "https://example.com",
            updatedAt = System.currentTimeMillis()
        )

    @Test
    fun `delta scales with duration`() {
        val d1 = SeekMath.delta(dx = 500f, widthPx = 1000, duration = 100.0)
        assertEquals(10.0, d1, 0.01)

        val d2 = SeekMath.delta(dx = 500f, widthPx = 1000, duration = 600.0)
        assertEquals(60.0, d2, 0.01)
    }

    @Test
    fun `delta caps at 300 seconds per width`() {
        val d = SeekMath.delta(dx = 1000f, widthPx = 1000, duration = 7200.0)
        assertEquals(300.0, d, 0.01)
    }

    @Test
    fun `delta floors at 30 seconds per width`() {
        val d = SeekMath.delta(dx = 1000f, widthPx = 1000, duration = 60.0)
        assertEquals(12.0, d, 0.01)
    }

    @Test
    fun `delta for short video does not exceed duration`() {
        val d = SeekMath.delta(dx = 1000f, widthPx = 1000, duration = 10.0)
        assertEquals(10.0, d, 0.01)
    }

    @Test
    fun `negative dx gives negative delta`() {
        val d = SeekMath.delta(dx = -500f, widthPx = 1000, duration = 300.0)
        assertTrue(d < 0)
    }

    @Test
    fun `clamp keeps time in bounds`() {
        val state = makeState(duration = 120.0)
        assertEquals(0.0, SeekMath.clamp(-5.0, state), 0.01)
        assertEquals(120.0, SeekMath.clamp(200.0, state), 0.01)
        assertEquals(60.0, SeekMath.clamp(60.0, state), 0.01)
    }

    @Test
    fun `clamp handles infinite duration`() {
        val state = makeState(duration = Double.POSITIVE_INFINITY, isLive = true)
        assertEquals(50.0, SeekMath.clamp(50.0, state), 0.01)
        assertEquals(0.0, SeekMath.clamp(-5.0, state), 0.01)
    }

    @Test
    fun `formatTime formats correctly`() {
        assertEquals("0:00", SeekMath.formatTime(0.0))
        assertEquals("1:05", SeekMath.formatTime(65.0))
        assertEquals("1:00:00", SeekMath.formatTime(3600.0))
        assertEquals("1:23:45", SeekMath.formatTime(5025.0))
    }

    @Test
    fun `formatDelta adds sign`() {
        assertEquals("+0:45", SeekMath.formatDelta(45.0))
        assertEquals("-0:30", SeekMath.formatDelta(-30.0))
        assertEquals("+0:00", SeekMath.formatDelta(0.0))
    }
}
