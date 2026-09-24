package com.crossdito.companion.ble

/** Explicit BLE connection state machine (single-threaded, main looper). */
enum class ConnectionState {
    Disconnected,
    Scanning,
    Connecting,
    DiscoveringServices,
    Ready,
    Sending,
    Error,
}
