package com.itsmebillah.appinternetcontrol

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

object WifiNetworkIdentityReader {
    /** Reads the connected Wi-Fi transport even when another app's VPN is the default network. */
    fun currentConnectedWifi(context: Context): WifiNetworkIdentity? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val networks = buildList<Network> {
            connectivity.activeNetwork?.let(::add)
            connectivity.allNetworks.forEach { if (it !in this) add(it) }
        }
        var wifiTransports = 0
        var wifiInfoObjects = 0
        val identityFromNetwork = networks.asSequence()
            .mapNotNull(connectivity::getNetworkCapabilities)
            .mapNotNull { capabilities ->
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
                wifiTransports++
                val info = capabilities.transportInfo as? WifiInfo ?: return@mapNotNull null
                wifiInfoObjects++
                fromWifiInfo(info)
            }
            .firstOrNull()
        @Suppress("DEPRECATION")
        val legacyWifiInfo = context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo
        val identity = identityFromNetwork ?: fromWifiInfo(legacyWifiInfo)
        Log.i(
            TAG,
            "wifi_identity_available=${identity != null} wifiTransports=$wifiTransports wifiInfoObjects=$wifiInfoObjects " +
                "legacyInfoPresent=${legacyWifiInfo != null} fineLocationGranted=${hasPermission(context, "android.permission.ACCESS_FINE_LOCATION")}",
        )
        identity
    }.getOrNull()

    fun fromCapabilities(capabilities: NetworkCapabilities): WifiNetworkIdentity? = runCatching {
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        fromWifiInfo(capabilities.transportInfo as? WifiInfo ?: return null)
    }.getOrNull()

    private fun fromWifiInfo(info: WifiInfo?): WifiNetworkIdentity? = runCatching {
        info ?: return null
        val networkId = info.networkId
        val ssid = info.ssid
            ?.removeSurrounding("\"", "\"")
            ?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID || it == "<unknown ssid>" }
        if (networkId < 0 || ssid == null) return null
        val securityType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { info.currentSecurityType }.getOrNull()
        } else null
        WifiNetworkIdentity(networkId = networkId, ssid = ssid, securityType = securityType)
    }.getOrNull()

    private fun hasPermission(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private const val TAG = "AppIC.WifiIdentity"
}

/** Process-local runtime view. It is intentionally not persisted; after restart identity is unknown until re-read. */
object WifiAccessRuntimeState {
    @Volatile var currentDefaultWifi: WifiNetworkIdentity? = null
        private set

    fun update(identity: WifiNetworkIdentity?) {
        currentDefaultWifi = identity
    }
}
