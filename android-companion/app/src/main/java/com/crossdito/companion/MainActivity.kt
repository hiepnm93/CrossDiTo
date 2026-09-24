package com.crossdito.companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.crossdito.companion.ble.BleClient
import com.crossdito.companion.ble.ConnectionState
import com.crossdito.companion.protocol.CompanionProtocol
import com.crossdito.companion.protocol.WeatherCondition
import com.crossdito.companion.weather.ManualWeatherProvider
import com.crossdito.companion.weather.OpenMeteoWeatherProvider

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_BLE_PERMS = 1
        private const val REQUEST_ENABLE_BT = 2
    }

    private lateinit var bleClient: BleClient
    private lateinit var statusText: TextView
    private lateinit var fetchStatus: TextView
    private lateinit var connectButton: Button
    private lateinit var sendButton: Button
    private lateinit var fetchButton: Button
    private lateinit var locationField: EditText
    private lateinit var temperatureField: EditText
    private lateinit var feelsLikeField: EditText
    private lateinit var tempMinField: EditText
    private lateinit var tempMaxField: EditText
    private lateinit var humidityField: EditText
    private lateinit var conditionSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        fetchStatus = findViewById(R.id.fetchStatus)
        connectButton = findViewById(R.id.connectButton)
        sendButton = findViewById(R.id.sendButton)
        fetchButton = findViewById(R.id.fetchButton)
        locationField = findViewById(R.id.locationField)
        temperatureField = findViewById(R.id.temperatureField)
        feelsLikeField = findViewById(R.id.feelsLikeField)
        tempMinField = findViewById(R.id.tempMinField)
        tempMaxField = findViewById(R.id.tempMaxField)
        humidityField = findViewById(R.id.humidityField)
        conditionSpinner = findViewById(R.id.conditionSpinner)

        conditionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            WeatherCondition.entries.map { it.name },
        )
        conditionSpinner.setSelection(WeatherCondition.CLOUDY.value)

        bleClient = BleClient(applicationContext, object : BleClient.Listener {
            override fun onStateChanged(state: ConnectionState, message: String) {
                runOnUiThread { renderState(state, message) }
            }
        })

        connectButton.setOnClickListener { onConnectPressed() }
        sendButton.setOnClickListener { onSendPressed() }
        fetchButton.setOnClickListener { onFetchPressed() }
        renderState(ConnectionState.Disconnected, getString(R.string.status_disconnected))
    }

    // --- Connection ----------------------------------------------------------

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    private fun hasAllPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun onConnectPressed() {
        if (bleClient.isConnected() || bleClient.state == ConnectionState.Connecting ||
            bleClient.state == ConnectionState.DiscoveringServices
        ) {
            bleClient.disconnect()
            return
        }
        when {
            !bleClient.isBluetoothEnabled() -> {
                statusText.text = "Bluetooth is off — enable it and try again"
                @Suppress("DEPRECATION")
                startActivityForResult(
                    android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE),
                    REQUEST_ENABLE_BT,
                )
            }
            !hasAllPermissions() -> {
                ActivityCompat.requestPermissions(this, requiredPermissions().toTypedArray(), REQUEST_BLE_PERMS)
            }
            else -> bleClient.startScan()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK && hasAllPermissions()) {
                bleClient.startScan()
            } else if (resultCode != RESULT_OK) {
                statusText.text = "Bluetooth stays off — cannot scan"
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLE_PERMS) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (bleClient.isBluetoothEnabled()) bleClient.startScan()
        } else {
            statusText.text = "Bluetooth permission denied — cannot scan"
        }
    }

    private fun renderState(state: ConnectionState, message: String) {
        statusText.text = message
        connectButton.setText(
            if (state == ConnectionState.Ready || state == ConnectionState.Sending ||
                state == ConnectionState.Connecting || state == ConnectionState.DiscoveringServices ||
                state == ConnectionState.Scanning
            ) {
                R.string.disconnect
            } else {
                R.string.connect
            },
        )
        // Only allow sending once the DATA characteristic is usable.
        sendButton.isEnabled = state == ConnectionState.Ready
    }

    // --- Sending -------------------------------------------------------------

    private fun readDouble(field: EditText, name: String): Double =
        field.text.toString().trim().toDoubleOrNull() ?: throw IllegalArgumentException("$name is not a number")

    private fun buildManualProvider(): ManualWeatherProvider {
        val humidity = humidityField.text.toString().trim().toIntOrNull()
            ?: throw IllegalArgumentException("Humidity is not a number")
        return ManualWeatherProvider(
            condition = WeatherCondition.entries[conditionSpinner.selectedItemPosition],
            temperatureC = readDouble(temperatureField, "Temperature"),
            feelsLikeC = readDouble(feelsLikeField, "Feels like"),
            minC = readDouble(tempMinField, "Min"),
            maxC = readDouble(tempMaxField, "Max"),
            humidity = humidity,
            location = locationField.text.toString().trim().ifEmpty { "Unknown" },
        )
    }

    @SuppressLint("MissingPermission")
    private fun onSendPressed() {
        try {
            val weather = buildManualProvider().fetch("")
            val frame = CompanionProtocol.encodeWeatherFrame(weather)
            bleClient.sendFrame(frame) { ok, message ->
                runOnUiThread {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    if (!ok) renderState(ConnectionState.Error, message)
                }
            }
        } catch (e: IllegalArgumentException) {
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun onFetchPressed() {
        val query = locationField.text.toString().trim()
        if (query.isEmpty()) {
            Toast.makeText(this, "Enter a city name first", Toast.LENGTH_SHORT).show()
            return
        }
        fetchStatus.text = "Fetching weather…"
        fetchButton.isEnabled = false
        Thread {
            try {
                val weather = OpenMeteoWeatherProvider().fetch(query)
                runOnUiThread {
                    fillForm(weather)
                    fetchStatus.text = "Fetched — check values, then press Send"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    fetchStatus.text = "Fetch failed: ${e.message}"
                }
            } finally {
                runOnUiThread { fetchButton.isEnabled = true }
            }
        }.start()
    }

    private fun fillForm(weather: com.crossdito.companion.protocol.WeatherData) {
        locationField.setText(weather.location)
        temperatureField.setText(formatTenths(weather.temperatureDeciC))
        feelsLikeField.setText(formatTenths(weather.feelsLikeDeciC))
        tempMinField.setText(formatTenths(weather.tempMinDeciC))
        tempMaxField.setText(formatTenths(weather.tempMaxDeciC))
        humidityField.setText(weather.humidity.toString())
        conditionSpinner.setSelection(weather.condition.value)
    }

    private fun formatTenths(deciC: Int): String =
        if (deciC % 10 == 0) (deciC / 10).toString() else String.format("%.1f", deciC / 10.0)

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        bleClient.disconnect()
    }
}
