package com.crossdito.companion.protocol

/** Weather snapshot pushed to the X4 Pro. Units mirror the wire format. */
data class WeatherData(
    val condition: WeatherCondition = WeatherCondition.UNKNOWN,
    /** Degrees Celsius x 10 (27.5 C -> 275). */
    val temperatureDeciC: Int = 0,
    val feelsLikeDeciC: Int = 0,
    val tempMinDeciC: Int = 0,
    val tempMaxDeciC: Int = 0,
    /** 0..100 percent. */
    val humidity: Int = 0,
    /** Observation time as Unix epoch seconds. */
    val timestamp: Long = 0,
    /** UTF-8 display location, max 31 bytes after encoding. */
    val location: String = "",
)
