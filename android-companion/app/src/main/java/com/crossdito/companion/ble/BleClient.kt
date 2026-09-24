package com.crossdito.companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.crossdito.companion.protocol.CompanionProtocol
import java.util.UUID

/**
 * GATT client for the X4 Pro companion service. All public methods must be
 * called from the main thread; BLE callbacks are marshalled back onto it.
 * Only one async operation is ever in flight (state machine + pending op flag).
 *
 * Wire protocol and UUIDs: docs/companion-protocol.md.
 */
class BleClient(context: Context, private val listener: Listener) {

    interface Listener {
        fun onStateChanged(state: ConnectionState, message: String)
    }

    companion object {
        private const val TAG = "BleClient"
        val SERVICE_UUID: UUID = UUID.fromString("c0de0001-5b1e-4c7d-8a9f-000000000001")
        val DATA_CHAR_UUID: UUID = UUID.fromString("c0de0001-5b1e-4c7d-8a9f-000000000002")
        val STATUS_CHAR_UUID: UUID = UUID.fromString("c0de0001-5b1e-4c7d-8a9f-000000000003")
        const val DEVICE_NAME = "CrossDiTo-X4"
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val CHUNK_PACING_MS = 30L
        private val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val context = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter

    var state: ConnectionState = ConnectionState.Disconnected
        private set

    private var gatt: BluetoothGatt? = null
    private var dataCharacteristic: BluetoothGattCharacteristic? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var writeChunkSize = CompanionProtocol.SAFE_CHUNK_SIZE
    private var operationPending = false
    private var timedOut = false
    private var pendingChunks: List<ByteArray> = emptyList()
    private var sendListener: ((Boolean, String) -> Unit)? = null

    private fun setState(newState: ConnectionState, message: String) {
        state = newState
        listener.onStateChanged(newState, message)
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    fun isConnected(): Boolean = state == ConnectionState.Ready || state == ConnectionState.Sending

    // --- Scanning ------------------------------------------------------------

    @SuppressLint("MissingPermission") // callers check BLUETOOTH_SCAN/CONNECT before calling in
    fun startScan() {
        val ble = adapter
        if (ble == null || !ble.isEnabled) {
            setState(ConnectionState.Error, "Bluetooth is off")
            return
        }
        if (state != ConnectionState.Disconnected && state != ConnectionState.Error) return

        val scanner = ble.bluetoothLeScanner ?: run {
            setState(ConnectionState.Error, "BLE scanning unavailable")
            return
        }

        timedOut = false
        setState(ConnectionState.Scanning, "Scanning for $DEVICE_NAME…")
        try {
            // Deliberately unfiltered: 128-bit service UUID ScanFilters are
            // unreliable on several phone stacks because the UUID lands in the
            // scan response. Match by name/UUID in code instead.
            scanner.startScan(null, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build(), scanCallback)
        } catch (e: Exception) {
            Log.e(TAG, "startScan failed", e)
            setState(ConnectionState.Error, "Scan failed to start: ${e.message}")
            return
        }
        mainHandler.postDelayed({
            if (state == ConnectionState.Scanning) {
                stopScanInternal()
                setState(
                    ConnectionState.Error,
                    "X4 not found. Is the reader showing \"Waiting for phone…\" on its Phone Companion screen?",
                )
            }
        }, SCAN_TIMEOUT_MS)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (state != ConnectionState.Scanning) return
            val advertisedUuids = result.scanRecord?.serviceUuids.orEmpty()
            val name = result.scanRecord?.deviceName ?: result.device?.name
            val isX4 = name == DEVICE_NAME || advertisedUuids.any { it.uuid == SERVICE_UUID }
            if (!isX4) return
            stopScanInternal()
            connect(result.device, "Found $DEVICE_NAME (${result.device.address}) — connecting…")
        }

        override fun onScanFailed(errorCode: Int) {
            if (state != ConnectionState.Scanning) return
            val reason = when (errorCode) {
                1 -> "already started"
                2 -> "Bluetooth off"
                3 -> "internal error"
                4 -> "feature unsupported"
                5 -> "app registration lost"
                else -> "unknown"
            }
            setState(ConnectionState.Error, "Scan failed ($reason)")
        }
    }

    private fun stopScanInternal() {
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan failed", e)
        }
    }

    fun cancelScan() {
        if (state == ConnectionState.Scanning) {
            stopScanInternal()
            setState(ConnectionState.Disconnected, "Scan cancelled")
        }
    }

    // --- Connection ----------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, foundMessage: String) {
        timedOut = false
        setState(ConnectionState.Connecting, foundMessage)
        gatt = try {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (e: SecurityException) {
            setState(ConnectionState.Error, "Permission missing: ${e.message}")
            return
        }
        mainHandler.postDelayed({
            if (state == ConnectionState.Connecting || state == ConnectionState.DiscoveringServices) {
                timedOut = true
                closeGattQuietly()
                setState(ConnectionState.Error, "Connection timed out")
            }
        }, CONNECT_TIMEOUT_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post {
                if (g !== gatt) return@post
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        operationPending = false
                        setState(ConnectionState.DiscoveringServices, "Discovering services…")
                        if (!g.discoverServices()) {
                            closeGattQuietly()
                            setState(ConnectionState.Error, "Service discovery failed to start")
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val wasTimedOut = timedOut
                        closeGattQuietly()
                        if (!wasTimedOut && state != ConnectionState.Disconnected) {
                            setState(ConnectionState.Disconnected, "X4 disconnected")
                        }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            mainHandler.post {
                if (g !== gatt || state != ConnectionState.DiscoveringServices) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    closeGattQuietly()
                    setState(ConnectionState.Error, "Service discovery failed (code $status)")
                    return@post
                }
                val service = g.getService(SERVICE_UUID)
                dataCharacteristic = service?.getCharacteristic(DATA_CHAR_UUID)
                statusCharacteristic = service?.getCharacteristic(STATUS_CHAR_UUID)
                if (service == null || dataCharacteristic == null || statusCharacteristic == null) {
                    closeGattQuietly()
                    setState(ConnectionState.Error, "Companion service not found on X4 (old firmware?)")
                    return@post
                }
                // Larger MTU lets a weather frame go out in one write; framing
                // works either way because the X4 reassembles.
                g.requestMtu(185)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            mainHandler.post {
                if (g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    writeChunkSize = minOf(mtu - 3, CompanionProtocol.MAX_FRAME_BYTES)
                }
                subscribeStatus(g)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            mainHandler.post {
                if (g !== gatt) return@post
                operationPending = false
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    setState(ConnectionState.Ready, "Connected to X4")
                } else {
                    closeGattQuietly()
                    setState(ConnectionState.Error, "Could not enable status notifications")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            mainHandler.post {
                if (g !== gatt) return@post
                operationPending = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    val receiver = sendListener
                    sendListener = null
                    setState(ConnectionState.Error, "Write failed (code $status)")
                    receiver?.invoke(false, "Write failed (code $status)")
                    return@post
                }
                sendNextChunk(g)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            mainHandler.post {
                if (g !== gatt || characteristic.uuid != STATUS_CHAR_UUID) return@post
                if (value.size >= 3 && value[2].toInt() != 0) {
                    Log.w(TAG, "X4 reported protocol error ${value[2]}")
                    listener.onStateChanged(state, "X4 rejected last packet (error ${value[2]})")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeStatus(g: BluetoothGatt) {
        val status = statusCharacteristic ?: return
        g.setCharacteristicNotification(status, true)
        val descriptor = status.getDescriptor(CCC_DESCRIPTOR_UUID)
        if (descriptor == null) {
            operationPending = false
            setState(ConnectionState.Ready, "Connected to X4")
            return
        }
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        operationPending = true
        if (!g.writeDescriptor(descriptor)) {
            operationPending = false
            closeGattQuietly()
            setState(ConnectionState.Error, "Could not subscribe to status")
        }
    }

    // --- Sending -------------------------------------------------------------

    /**
     * Sends a frame to the DATA characteristic. [onResult] fires on the main
     * thread with success and a human-readable message.
     */
    @SuppressLint("MissingPermission")
    fun sendFrame(frame: ByteArray, onResult: (Boolean, String) -> Unit) {
        val g = gatt
        val data = dataCharacteristic
        if (state != ConnectionState.Ready || g == null || data == null) {
            onResult(false, "Not connected to X4 yet")
            return
        }
        if (operationPending) {
            onResult(false, "Another BLE operation is in flight")
            return
        }
        val chunks = CompanionProtocol.chunk(frame, writeChunkSize)
        pendingChunks = chunks
        sendListener = onResult
        setState(ConnectionState.Sending, "Sending…")
        sendNextChunkInternal(g, data)
    }

    @SuppressLint("MissingPermission")
    private fun sendNextChunk(g: BluetoothGatt) {
        val data = dataCharacteristic ?: return
        sendNextChunkInternal(g, data)
    }

    @SuppressLint("MissingPermission")
    private fun sendNextChunkInternal(g: BluetoothGatt, data: BluetoothGattCharacteristic) {
        val receiver = sendListener
        if (pendingChunks.isEmpty()) {
            sendListener = null
            setState(ConnectionState.Ready, "Connected to X4")
            receiver?.invoke(true, "Sent to X4")
            return
        }
        val chunk = pendingChunks.first()
        data.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val ok = try {
            // API 33+ overload returns an Int status (GATT_SUCCESS on accept).
            g.writeCharacteristic(data, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } catch (e: SecurityException) {
            receiver?.invoke(false, "Permission missing: ${e.message}")
            setState(ConnectionState.Error, "Permission missing")
            return
        }
        if (ok != BluetoothGatt.GATT_SUCCESS) {
            sendListener = null
            setState(ConnectionState.Error, "Write rejected by the stack")
            receiver?.invoke(false, "Write rejected by the stack")
            return
        }
        pendingChunks = pendingChunks.drop(1)
        // WRITE_TYPE_NO_RESPONSE writes only sometimes invoke the callback on
        // all stack versions; pacing both paces the queue and guarantees
        // progress for chunked frames.
        operationPending = true
        mainHandler.postDelayed({
            operationPending = false
            if (state == ConnectionState.Sending) sendNextChunkInternal(g, data)
        }, CHUNK_PACING_MS)
    }

    // --- Teardown ------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun disconnect() {
        stopScanInternal()
        if (state == ConnectionState.Sending) setState(ConnectionState.Disconnected, "Disconnected")
        val g = gatt
        gatt = null
        dataCharacteristic = null
        statusCharacteristic = null
        pendingChunks = emptyList()
        sendListener = null
        operationPending = false
        if (g != null) {
            try {
                g.disconnect()
                g.close()
            } catch (e: SecurityException) {
                Log.w(TAG, "disconnect failed", e)
            }
        }
        if (state != ConnectionState.Disconnected && state != ConnectionState.Error) {
            setState(ConnectionState.Disconnected, "Disconnected")
        } else if (state == ConnectionState.Error) {
            setState(ConnectionState.Disconnected, state_message_reset())
        }
    }

    private fun state_message_reset(): String = "Disconnected"

    private fun closeGattQuietly() {
        val g = gatt
        gatt = null
        dataCharacteristic = null
        statusCharacteristic = null
        pendingChunks = emptyList()
        sendListener = null
        operationPending = false
        try {
            g?.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "close failed", e)
        }
    }
}
