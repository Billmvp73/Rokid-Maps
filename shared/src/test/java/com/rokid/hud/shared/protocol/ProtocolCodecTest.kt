package com.rokid.hud.shared.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Smoke + round-trip tests for [ProtocolCodec], the JSON codec for the
 * phone<->glasses Bluetooth SPP protocol. These are the repo's first unit
 * tests: they prove the JUnit4 runner and the real org.json artifact are on
 * the plain-JVM test classpath (the mockable android.jar stubs would throw).
 */
class ProtocolCodecTest {

    @Test
    fun stateMessageRoundTrip() {
        val original = StateMessage(
            latitude = 47.6062095,
            longitude = -122.3320708,
            bearing = 135.5f,
            speed = 4.2f,
            accuracy = 8.5f,
            speedLimitKmh = 50,
            distToNextStep = 320.75
        )
        val encoded = ProtocolCodec.encodeState(original)
        val decoded = ProtocolCodec.decode(encoded)
        assertTrue("Expected ParsedMessage.State but got $decoded", decoded is ParsedMessage.State)
        val msg = (decoded as ParsedMessage.State).msg
        assertEquals(original.latitude, msg.latitude, 1e-9)
        assertEquals(original.longitude, msg.longitude, 1e-9)
        assertEquals(original.bearing, msg.bearing, 1e-4f)
        assertEquals(original.speed, msg.speed, 1e-4f)
        assertEquals(original.accuracy, msg.accuracy, 1e-4f)
        assertEquals(original.speedLimitKmh, msg.speedLimitKmh)
        assertEquals(original.distToNextStep, msg.distToNextStep, 1e-9)
    }

    @Test
    fun malformedLineDecodesToUnknown() {
        val decoded = ProtocolCodec.decode("not json at all")
        assertTrue("Expected ParsedMessage.Unknown but got $decoded", decoded is ParsedMessage.Unknown)
    }
}
