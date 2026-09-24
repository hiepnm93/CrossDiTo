package com.crossdito.companion.weather

import com.crossdito.companion.protocol.WeatherData
import com.crossdito.companion.protocol.wmoCodeToCondition
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Key-free automatic weather via Open-Meteo (geocoding + forecast). Runs on a
 * caller-supplied worker thread; no Android-specific APIs, so it is also unit
 * testable in plain JVM tests by injecting [urlOpener].
 */
class OpenMeteoWeatherProvider(
    private val urlOpener: (String) -> String = ::httpGet,
) : WeatherProvider {

    override fun fetch(displayLocationQuery: String): WeatherData {
        val query = displayLocationQuery.trim()
        if (query.isEmpty()) throw IllegalArgumentException("Enter a city name first")

        val geoBody = urlOpener(
            "https://geocoding-api.open-meteo.com/v1/search?name=" +
                URLEncoder.encode(query, "UTF-8") + "&count=1&language=en&format=json",
        )
        val geo = JSONObject(geoBody)
        val results = geo.optJSONArray("results")
            ?: throw IllegalArgumentException("City \"$query\" not found")
        val place = results.getJSONObject(0)
        val lat = place.getDouble("latitude")
        val lon = place.getDouble("longitude")
        val resolvedName = place.optString("name", query)

        val body = urlOpener(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1",
        )
        val forecast = JSONObject(body)
        val current = forecast.getJSONObject("current")
        val daily = forecast.getJSONObject("daily")

        return WeatherData(
            condition = wmoCodeToCondition(current.optInt("weather_code", -1)),
            temperatureDeciC = Math.round(current.getDouble("temperature_2m") * 10).toInt(),
            feelsLikeDeciC = Math.round(current.getDouble("apparent_temperature") * 10).toInt(),
            tempMaxDeciC = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0) * 10).toInt(),
            tempMinDeciC = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0) * 10).toInt(),
            humidity = current.optInt("relative_humidity_2m", 0).coerceIn(0, 100),
            timestamp = System.currentTimeMillis() / 1000,
            location = resolvedName,
        )
    }

    companion object {
        /** Minimal blocking GET; separated for test injection. */
        fun httpGet(url: String): String {
            val connection = URL(url).openConnection() as HttpURLConnection
            return try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                val code = connection.responseCode
                if (code != 200) throw java.io.IOException("HTTP $code")
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }
}
