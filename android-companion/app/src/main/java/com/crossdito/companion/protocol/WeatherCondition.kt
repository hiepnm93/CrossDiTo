package com.crossdito.companion.protocol

/** Weather condition enum; values are the wire values (docs/companion-protocol.md). */
enum class WeatherCondition(val value: Int) {
    CLEAR(0),
    PARTLY_CLOUDY(1),
    CLOUDY(2),
    RAIN(3),
    HEAVY_RAIN(4),
    THUNDERSTORM(5),
    SNOW(6),
    FOG(7),
    UNKNOWN(8);

    companion object {
        fun fromValue(value: Int): WeatherCondition? = entries.firstOrNull { it.value == value }
    }
}

/** WMO weather interpretation codes (Open-Meteo `current.weather_code`). */
fun wmoCodeToCondition(code: Int): WeatherCondition = when (code) {
    0 -> WeatherCondition.CLEAR
    1, 2 -> WeatherCondition.PARTLY_CLOUDY
    3 -> WeatherCondition.CLOUDY
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> WeatherCondition.RAIN
    95, 96, 99 -> WeatherCondition.THUNDERSTORM
    71, 73, 75, 77, 85, 86 -> WeatherCondition.SNOW
    45, 48 -> WeatherCondition.FOG
    else -> WeatherCondition.UNKNOWN
}
