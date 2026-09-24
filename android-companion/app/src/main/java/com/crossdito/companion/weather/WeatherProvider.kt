package com.crossdito.companion.weather

import com.crossdito.companion.protocol.WeatherData

/**
 * Source of weather snapshots. BLE code never knows where data came from;
 * providers only produce [WeatherData] values and throw on failure.
 */
interface WeatherProvider {
    fun fetch(displayLocationQuery: String): WeatherData
}
