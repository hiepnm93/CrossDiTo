package com.crossdito.companion.protocol

import java.io.ByteArrayOutputStream

/**
 * Encoder for the CrossDiTo Companion protocol, version 1.
 * Wire format: docs/companion-protocol.md (shared with the firmware decoder in
 * src/companion/CompanionProtocol.cpp).
 */
object CompanionProtocol {
    const val PROTOCOL_VERSION: Int = 1

    const val TYPE_WEATHER: Int = 0x01
    const val TYPE_CALENDAR: Int = 0x02
    const val TYPE_NOTIFICATION: Int = 0x03
    const val TYPE_PHONE_BATTERY: Int = 0x04
    const val TYPE_CUSTOM_TEXT: Int = 0x05

    const val FRAME_HEADER_BYTES: Int = 4
    const val MAX_PAYLOAD_BYTES: Int = 480
    const val MAX_FRAME_BYTES: Int = FRAME_HEADER_BYTES + MAX_PAYLOAD_BYTES

    const val WEATHER_LOCATION_MAX_BYTES: Int = 31

    /** BLE writes below the negotiated MTU; the X4 reassembles any split. */
    const val SAFE_CHUNK_SIZE: Int = 20

    /** Encodes a full WEATHER frame. Throws [IllegalArgumentException] when [data] cannot fit the wire limits. */
    fun encodeWeatherFrame(data: WeatherData): ByteArray {
        val payload = encodeWeatherPayload(data)
        val out = ByteArrayOutputStream(FRAME_HEADER_BYTES + payload.size)
        out.write(PROTOCOL_VERSION)
        out.write(TYPE_WEATHER)
        out.write((payload.size shr 8) and 0xFF)
        out.write(payload.size and 0xFF)
        out.write(payload)
        return out.toByteArray()
    }

    /** Encodes the WEATHER payload body (no frame header). */
    fun encodeWeatherPayload(data: WeatherData): ByteArray {
        require(WeatherCondition.fromValue(data.condition.value) != null) { "invalid condition" }
        require(data.humidity in 0..100) { "humidity out of range: ${data.humidity}" }
        val locationBytes = truncateUtf8(data.location, WEATHER_LOCATION_MAX_BYTES)
        require(locationBytes.decodeToString().length >= 0) // UTF-8 was validated by decodeToString

        val out = ByteArrayOutputStream(19 + locationBytes.size)
        out.write(data.condition.value)
        writeU16(out, data.temperatureDeciC)
        writeU16(out, data.feelsLikeDeciC)
        writeU16(out, data.tempMinDeciC)
        writeU16(out, data.tempMaxDeciC)
        out.write(data.humidity)
        writeU64(out, data.timestamp)
        out.write(locationBytes.size)
        out.write(locationBytes)
        return out.toByteArray()
    }

    /** Splits a frame into BLE-safe write chunks. */
    fun chunk(frame: ByteArray, chunkSize: Int = SAFE_CHUNK_SIZE): List<ByteArray> {
        require(chunkSize > 0)
        if (frame.isEmpty()) return emptyList()
        return frame.toList().chunked(chunkSize).map { it.toByteArray() }
    }

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeU64(out: ByteArrayOutputStream, v: Long) {
        for (shift in 56 downTo 0 step 8) {
            out.write(((v shr shift) and 0xFF).toInt())
        }
    }

    /** Truncates to at most maxBytes on a UTF-8 character boundary. */
    private fun truncateUtf8(value: String, maxBytes: Int): ByteArray {
        val raw = value.toByteArray(Charsets.UTF_8)
        if (raw.size <= maxBytes) return raw
        var len = maxBytes
        while (len > 0 && (raw[len].toInt() and 0xC0) == 0x80) {
            len-- // do not split a multi-byte sequence
        }
        val result = raw.copyOf(len)
        // Sanity: reject anything that is not valid UTF-8 after truncation.
        result.toString(Charsets.UTF_8)
        return result
    }
}
