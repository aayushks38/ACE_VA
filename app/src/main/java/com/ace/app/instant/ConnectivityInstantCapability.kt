package com.ace.app.instant

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings

class ConnectivityInstantCapability : InstantCapability {
    override val id: String = "device_connectivity"
    override val name: String = "Connectivity Instant Intelligence"
    override val source: String = "connectivity_api"

    override fun confidence(command: String): Float {
        val lower = command.lowercase().trim()
        if (lower.startsWith("turn ") || lower.startsWith("open ") || lower.startsWith("enable ") || lower.startsWith("disable ") || lower.startsWith("toggle ")) {
            return 0.0f
        }
        if (lower.contains("wi-fi") || lower.contains("wifi") || lower.contains("bluetooth") ||
            lower.contains("airplane mode") || lower.contains("mobile data") || lower.contains("mobile connectivity")) {
            return 0.95f
        }
        return 0.0f
    }

    override suspend fun execute(context: Context?, command: String): InstantResult {
        if (context == null) {
            return InstantResult(
                isHandled = false,
                capabilityId = id,
                message = "Device context unavailable for connectivity status.",
                source = source,
                error = "Null context"
            )
        }

        val lower = command.lowercase().trim()

        if (lower.contains("bluetooth")) {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            val isEnabled = adapter?.isEnabled == true
            val msg = if (isEnabled) "Bluetooth is currently enabled." else "Bluetooth is currently disabled."
            return InstantResult(
                isHandled = true,
                capabilityId = id,
                message = msg,
                source = "bluetooth_api",
                outputData = mapOf("bluetooth_enabled" to "$isEnabled")
            )
        }

        if (lower.contains("airplane")) {
            val isAirplaneOn = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            val msg = if (isAirplaneOn) "Airplane mode is currently enabled." else "Airplane mode is currently disabled."
            return InstantResult(
                isHandled = true,
                capabilityId = id,
                message = msg,
                source = "airplane_mode_api",
                outputData = mapOf("airplane_mode_enabled" to "$isAirplaneOn")
            )
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNetwork)

        if (lower.contains("wi-fi") || lower.contains("wifi")) {
            val isWifiConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val msg = if (isWifiConnected) "You are currently connected to Wi-Fi." else "Wi-Fi is not connected."
            return InstantResult(
                isHandled = true,
                capabilityId = id,
                message = msg,
                source = "wifi_api",
                outputData = mapOf("wifi_connected" to "$isWifiConnected")
            )
        }

        val isMobileConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val msg = if (isMobileConnected) "Mobile connectivity is available and active." else "Mobile cellular data is not connected."

        return InstantResult(
            isHandled = true,
            capabilityId = id,
            message = msg,
            source = source,
            outputData = mapOf("mobile_connected" to "$isMobileConnected")
        )
    }
}
