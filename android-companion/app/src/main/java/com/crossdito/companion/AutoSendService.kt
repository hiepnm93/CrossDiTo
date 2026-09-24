package com.crossdito.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.crossdito.companion.ble.BleClient
import com.crossdito.companion.ble.BleHolder
import com.crossdito.companion.ble.ConnectionState
import com.crossdito.companion.protocol.CompanionProtocol
import com.crossdito.companion.weather.OpenMeteoWeatherProvider

/**
 * Foreground service that keeps the X4 link up and pushes the current
 * Open-Meteo weather once a minute. Toggled from MainActivity; the activity
 * and this service share one BleClient via [BleHolder].
 */
class AutoSendService : Service() {

    companion object {
        private const val TAG = "AutoSend"
        private const val CHANNEL_ID = "auto_send"
        private const val NOTIF_ID = 1
        private const val INTERVAL_MS = 60_000L

        var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AutoSendService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutoSendService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val client get() = BleHolder.client

    private val stateListener = object : BleClient.Listener {
        override fun onStateChanged(state: ConnectionState, message: String) {
            if (state == ConnectionState.Error) updateNotif("Auto-send: $message")
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            tickOnce()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        client.addListener(stateListener)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Auto-send", NotificationManager.IMPORTANCE_LOW),
        )
        val notif = buildNotif("Auto-send: connecting…")
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
        handler.post(tick)
    }

    private fun tickOnce() {
        if (client.isConnected()) {
            sendWeather()
        } else if (!client.connectAddress(prefs().getString("last_address", null))) {
            // No saved address yet — discover one the normal way.
            client.startScan()
        }
    }

    private fun sendWeather() {
        val city = prefs().getString("city", null)?.trim().orEmpty()
        if (city.isEmpty()) {
            updateNotif("Auto-send: no city set — enter one in the app")
            return
        }
        Thread {
            try {
                val weather = OpenMeteoWeatherProvider().fetch(city)
                handler.post {
                    if (!running) return@post
                    client.sendFrame(CompanionProtocol.encodeWeatherFrame(weather)) { ok, msg ->
                        updateNotif(if (ok) "Auto-send: sent \"$city\"" else "Auto-send failed: $msg")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "weather fetch failed", e)
                handler.post { if (running) updateNotif("Auto-send: fetch failed (${e.message})") }
            }
        }.start()
    }

    private fun prefs() = getSharedPreferences("companion", MODE_PRIVATE)

    private fun buildNotif(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("CrossDiTo Companion")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        client.removeListener(stateListener)
        // Auto-send off (or service killed): drop the link so the X4
        // advertises again for the next session.
        client.disconnect()
        super.onDestroy()
    }
}
