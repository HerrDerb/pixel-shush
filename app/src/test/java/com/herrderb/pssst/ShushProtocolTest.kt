package com.herrderb.pssst

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ShushProtocolTest {

    private fun ByteArray.le() = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun startMessage_hasGmsLayout() {
        val b = ShushProtocol.start(screenOn = true).le()
        assertEquals(77, b.capacity())
        assertEquals(0.1f, b.getFloat(0), 0f) // batching delay
        assertEquals(0.4f, b.getFloat(4), 0f) // averaging rate
        assertEquals(0.15f, b.getFloat(8), 0f) // variation with proximity
        assertEquals(0.1f, b.getFloat(12), 0f) // angle tolerance
        assertEquals(30f, b.getFloat(24), 0f) // quota size
        assertEquals(2.0f, b.getFloat(32), 0f) // proximity transition window
        assertEquals(4, b.get(36).toInt()) // flags: 0cm detection only
        assertEquals(3.0f, b.getFloat(37), 0f) // lenient accel diff
        assertEquals(0.065f, b.getFloat(61), 0f) // variation without proximity
        assertEquals(5.0f, b.getFloat(73), 0f) // last field
    }

    @Test
    fun startMessage_screenOffUsesProximity() {
        assertEquals(6, ShushProtocol.start(screenOn = false)[36].toInt())
    }

    @Test
    fun screenMessage() {
        assertArrayEquals(byteArrayOf(0), ShushProtocol.screen(true))
        assertArrayEquals(byteArrayOf(1), ShushProtocol.screen(false))
    }
}
