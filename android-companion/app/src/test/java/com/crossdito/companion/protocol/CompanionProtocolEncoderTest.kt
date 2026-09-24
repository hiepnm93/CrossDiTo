package com.crossdito.companion.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Protocol tests. The reference bytes come from docs/companion-protocol.md and
 * must stay in sync with the firmware decoder tests in
 * test/companion_protocol/CompanionProtocolTest.cpp.
 */
class CompanionProtocolEncoderTest {

    private fun sample() = WeatherData(
        condition = WeatherCondition.CLOUDY,
        temperatureDeciC = 270,
        feelsLikeDeciC = 290,
        tempMinDeciC = 240,
        tempMaxDeciC = 300,
        humidity = 76,
        timestamp = 1789986000L,
        location = "Hanoi",
    )

    @Test
    fun encodesDocumentedExampleBytes() {
        val frame = CompanionProtocol.encodeWeatherFrame(sample())
        val expected = (
            listOf(0x01, 0x01, 0x00, 0x18) + listOf(
                0x02, 0x01, 0x0E, 0x01, 0x22, 0x00, 0xF0, 0x01, 0x2C, 0x4C,
                0x00, 0x00, 0x00, 0x00, 0x6A, 0xB1, 0x04, 0xD0, 0x05,
            ) + "Hanoi".toByteArray(Charsets.US_ASCII).map { it.toInt() and 0xFF }
            ).map { it.toByte() }.toByteArray()
        org.junit.Assert.assertArrayEquals(expected, frame)
    }

    @Test
    fun frameMatchesLayoutFields() {
        val frame = CompanionProtocol.encodeWeatherFrame(sample())
        assertEquals(CompanionProtocol.PROTOCOL_VERSION, frame[0].toInt())
        assertEquals(CompanionProtocol.TYPE_WEATHER, frame[1].toInt())
        val payloadLen = ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)
        assertEquals(frame.size - CompanionProtocol.FRAME_HEADER_BYTES, payloadLen)
        assertEquals(24, payloadLen)
    }

    @Test
    fun utf8LocationSurvivesEncoding() {
        val data = sample().copy(location = "Hà Nội — 河内")
        val payload = CompanionProtocol.encodeWeatherPayload(data)
        val locationLen = payload[18].toInt() and 0xFF
        val location = payload.slice(19 until 19 + locationLen).toByteArray().toString(Charsets.UTF_8)
        assertEquals("Hà Nội — 河内", location)
    }

    @Test
    fun oversizeLocationTruncatesOnCharacterBoundary() {
        val data = sample().copy(location = "à".repeat(40)) // 2 bytes per char
        val payload = CompanionProtocol.encodeWeatherPayload(data)
        val locationLen = payload[18].toInt() and 0xFF
        org.junit.Assert.assertTrue(locationLen <= CompanionProtocol.WEATHER_LOCATION_MAX_BYTES)
        assertEquals(0, locationLen % 2) // no split multi-byte sequence
    }

    @Test
    fun humidityAbove100IsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CompanionProtocol.encodeWeatherFrame(sample().copy(humidity = 101))
        }
    }

    @Test
    fun negativeTemperaturesEncodeTwoComplement() {
        val payload = CompanionProtocol.encodeWeatherPayload(sample().copy(temperatureDeciC = -50))
        val raw = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        assertEquals(-50, raw.toShort().toInt())
    }

    @Test
    fun chunkingPreservesAllBytes() {
        val frame = CompanionProtocol.encodeWeatherFrame(sample())
        val chunks = CompanionProtocol.chunk(frame, 7)
        assertEquals(frame.toList(), chunks.flatMap { it.toList() })
        org.junit.Assert.assertTrue(chunks.all { it.size <= 7 })
    }
}
