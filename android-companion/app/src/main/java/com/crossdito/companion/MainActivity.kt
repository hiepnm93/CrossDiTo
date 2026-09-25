package com.crossdito.companion

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.crossdito.companion.ble.BleClient
import com.crossdito.companion.ble.BleHolder
import com.crossdito.companion.ble.ConnectionState
import com.crossdito.companion.protocol.CompanionProtocol
import com.crossdito.companion.protocol.WeatherCondition
import com.crossdito.companion.weather.ManualWeatherProvider
import com.crossdito.companion.weather.OpenMeteoWeatherProvider

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_BLE_PERMS = 1
        private const val REQUEST_ENABLE_BT = 2
        private const val REQUEST_NOTIF_PERMS = 3
        private const val PREFS = "companion"
    }

    private val stateListener = object : BleClient.Listener {
        override fun onStateChanged(state: ConnectionState, message: String) {
            runOnUiThread { renderState(state, message) }
            when (state) {
                ConnectionState.Ready, ConnectionState.Disconnected, ConnectionState.Error ->
                    SyncLog.add(message)
                else -> {}
            }
        }
    }

    private val syncLogListener = { refreshSyncLog() }

    private lateinit var bleClient: BleClient
    private lateinit var statusText: TextView
    private lateinit var readerAddressText: TextView
    private lateinit var syncLogView: TextView
    private lateinit var fetchStatus: TextView
    private lateinit var connectButton: Button
    private lateinit var sendButton: Button
    private lateinit var fetchButton: Button
    private lateinit var autoSendCheck: android.widget.CheckBox
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
        // App version in the title bar; the reader shows its matching
        // companion version on the Phone Companion screen.
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        title = "CrossDiTo Companion v$appVersion"

        statusText = findViewById(R.id.statusText)
        readerAddressText = findViewById(R.id.readerAddressText)
        syncLogView = findViewById(R.id.syncLogView)
        fetchStatus = findViewById(R.id.fetchStatus)
        connectButton = findViewById(R.id.connectButton)
        sendButton = findViewById(R.id.sendButton)
        fetchButton = findViewById(R.id.fetchButton)
        autoSendCheck = findViewById(R.id.autoSendCheck)
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

        BleHolder.init(applicationContext)
        bleClient = BleHolder.client
        bleClient.addListener(stateListener)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        autoSendCheck.isChecked = prefs.getBoolean("auto_send", false)
        autoSendCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_send", checked).apply()
            if (checked) {
                requestNotifPermissionIfNeeded()
                AutoSendService.start(this)
            } else {
                AutoSendService.stop(this)
            }
        }
        if (autoSendCheck.isChecked) AutoSendService.start(this)

        findViewById<RadioGroup>(R.id.tabBar).setOnCheckedChangeListener { _, checkedId ->
            val reader = checkedId == R.id.tabReader
            findViewById<View>(R.id.pageReader).visibility = if (reader) View.VISIBLE else View.GONE
            findViewById<View>(R.id.pageWeather).visibility = if (reader) View.GONE else View.VISIBLE
        }

        connectButton.setOnClickListener { onConnectPressed() }
        sendButton.setOnClickListener { onSendPressed() }
        fetchButton.setOnClickListener { onFetchPressed() }
        SyncLog.addListener(syncLogListener)
        refreshSyncLog()
        renderState(ConnectionState.Disconnected, getString(R.string.status_disconnected))
    }

    private fun refreshSyncLog() {
        syncLogView.text = SyncLog.text().ifEmpty { getString(R.string.sync_log_empty) }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIF_PERMS,
        )
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

    private fun isLocationServiceOn(): Boolean {
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        return try {
            lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            true // do not block scanning if providers cannot be queried
        }
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
            Build.VERSION.SDK_INT < 31 && !isLocationServiceOn() -> {
                // Pre-Android-12 stacks report BLE scan results only with
                // location services enabled; without it the scan simply
                // returns nothing.
                statusText.text = "Turn location (GPS) on in quick settings — Android ≤11 needs it for BLE scanning"
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
        readerAddressText.text =
            getSharedPreferences(PREFS, MODE_PRIVATE).getString("last_address", null)
                ?: getString(R.string.no_reader_paired)
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
        // Keyboards on comma-decimal locales (vi, de, fr…) submit "27,5".
        field.text.toString().trim().replace(',', '.').toDoubleOrNull()
            ?: throw IllegalArgumentException("$name is not a number")

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
                    SyncLog.add(if (ok) "✓ manual send OK" else "✗ $message")
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
        // Locale.US so the field never renders "27,5", which readDouble
        // would still accept but keeps the value copy-paste stable.
        if (deciC % 10 == 0) (deciC / 10).toString() else String.format(java.util.Locale.US, "%.1f", deciC / 10.0)

    override fun onPause() {
        super.onPause()
        // The auto-send service reads the city from prefs every minute.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("city", locationField.text.toString().trim())
            .apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        bleClient.removeListener(stateListener)
        SyncLog.removeListener(syncLogListener)
        // Keep the link alive when the auto-send service owns it; the
        // service drops it in its own onDestroy.
        if (!AutoSendService.running) bleClient.disconnect()
    }
}
