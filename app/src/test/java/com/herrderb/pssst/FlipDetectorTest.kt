package com.herrderb.pssst

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FlipDetectorTest {

    private val g = FlipDetector.GRAVITY
    private val stepNs = 100_000_000L // 10 Hz, like the service
    private var t = 0L

    /** Feeds [seconds] of samples and returns every event that fired. */
    private fun FlipDetector.feed(x: Float, y: Float, z: Float, seconds: Float): List<FlipDetector.Event> {
        val events = mutableListOf<FlipDetector.Event>()
        repeat((seconds * 10).toInt()) {
            t += stepNs
            onAccel(x, y, z, t)?.let { events += it }
        }
        return events
    }

    /** Gravity reading for a phone tilted [deg] away from lying perfectly face down. */
    private fun FlipDetector.feedTilted(deg: Double, seconds: Float): List<FlipDetector.Event> {
        val r = Math.toRadians(deg)
        return feed((g * sin(r)).toFloat(), 0f, (-g * cos(r)).toFloat(), seconds)
    }

    @Test
    fun faceDownAndStill_flipsAfterOneSecond() {
        val d = FlipDetector()
        assertTrue(d.feed(0f, 0f, -g, 0.8f).isEmpty())
        assertEquals(listOf(FlipDetector.Event.FLIPPED), d.feed(0f, 0f, -g, 0.5f))
        assertTrue(d.isFlipped)
    }

    @Test
    fun faceUp_neverFlips() {
        val d = FlipDetector()
        assertTrue(d.feed(0f, 0f, g, 5f).isEmpty())
    }

    @Test
    fun tiltBeyondTolerance_neverFlips() {
        val d = FlipDetector()
        assertTrue(d.feedTilted(10.0, 5f).isEmpty()) // 10° > 0.1 rad (5.7°)
    }

    @Test
    fun slightTilt_stillFlips() {
        val d = FlipDetector()
        assertEquals(listOf(FlipDetector.Event.FLIPPED), d.feedTilted(3.0, 2f))
    }

    @Test
    fun shakingFaceDown_doesNotFlip() {
        val d = FlipDetector()
        repeat(30) { i ->
            t += stepNs
            val jitter = if (i % 2 == 0) 1.5f else -1.5f
            assertNull(d.onAccel(jitter, 0f, -g, t))
        }
        assertFalse(d.isFlipped)
    }

    @Test
    fun pickingUp_unflips() {
        val d = FlipDetector()
        d.feed(0f, 0f, -g, 2f)
        assertTrue(d.isFlipped)
        assertTrue(d.feedTilted(10.0, 1f).isEmpty()) // small wobble keeps the session
        assertEquals(listOf(FlipDetector.Event.UNFLIPPED), d.feed(0f, g, 0f, 1f))
        assertFalse(d.isFlipped)
    }

    @Test
    fun filterResetWhileFlipped_keepsSession() {
        val d = FlipDetector()
        d.feed(0f, 0f, -g, 2f)
        d.resetFilter()
        assertTrue(d.feed(0f, 0f, -g, 1f).isEmpty())
        assertTrue(d.isFlipped)
        assertEquals(listOf(FlipDetector.Event.UNFLIPPED), d.feed(0f, 0f, g, 1f))
    }

    @Test
    fun quota_limitsRapidTriggers() {
        val d = FlipDetector()
        var flips = 0
        repeat(40) {
            flips += d.feed(0f, 0f, -g, 3f).size
            d.feed(0f, 0f, g, 1f)
        }
        // 30 tokens plus 0.04/s over ~160 s ≈ 6 more.
        assertTrue("flips=$flips", flips in 30..37)
    }
}
