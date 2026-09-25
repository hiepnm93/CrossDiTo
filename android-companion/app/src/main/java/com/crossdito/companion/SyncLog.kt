package com.crossdito.companion

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory ring of recent sync events shown on the Reader tab. Kept out of
 * the service/notification path so the log survives activity recreation but
 * not process death — it is a convenience view, not a record.
 */
object SyncLog {
    private const val MAX_LINES = 30
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun add(line: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(lines) {
            lines.addFirst("$time  $line")
            while (lines.size > MAX_LINES) lines.removeLast()
        }
        mainHandler.post { listeners.forEach { it() } }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }
}
