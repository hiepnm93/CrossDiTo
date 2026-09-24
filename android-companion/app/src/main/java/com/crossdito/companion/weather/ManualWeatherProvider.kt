package com.crossdito.companion.weather

import com.crossdito.companion.protocol.WeatherCondition
import com.crossdito.companion.protocol.WeatherData

/**
 * The REQUIRED manual mode: the user types values into the form; no network,
 * no location permission. Implemented as a trivial provider so the send path
 * has exactly one input type.
 */
class ManualWeatherProvider(
    private val condition: WeatherCondition,
    private val temperatureC: Double,
    private val feelsLikeC: Double,
    private val minC: Double,
    private val maxC: Double,
    private val humidity: Int,
    private val location: String,
) : WeatherProvider {
    override fun fetch(displayLocationQuery: String): WeatherData =
        WeatherData(
            condition = condition,
            temperatureDeciC = (temperatureC * 10).toInt(),
            feelsLikeDeciC = (feelsLikeC * 10).toInt(),
            tempMinDeciC = (minC * 10).toInt(),
            tempMaxDeciC = (maxC * 10).toInt(),
            humidity = humidity,
            timestamp = System.currentTimeMillis() / 1000,
            location = location,
        )
}
