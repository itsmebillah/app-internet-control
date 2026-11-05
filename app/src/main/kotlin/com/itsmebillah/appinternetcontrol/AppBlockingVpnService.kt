package com.itsmebillah.appinternetcontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.IOException

class AppBlockingVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var discardingPackets = false
    @Volatile private var readerGeneration = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "service_stop requested")
            stopFiltering()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val wifiRulesEnabled = WifiAccessRuleStore(this).read().any { it.enabled }
        startForegroundCompat(getString(R.string.wifi_monitor_preparing), includeLocationType = wifiRulesEnabled)
        if (wifiRulesEnabled) startWifiNetworkMonitor() else stopWifiNetworkMonitor()
        val selected = ScheduleCoordinator.packagesForVpnServiceStart(this).toList()
        Log.i(TAG, "service_rules_reconciled selectedPackageCount=${selected.size}")
        if (selected.isEmpty()) {
            if (wifiRulesEnabled && isWifiMonitorActive) {
                stopTunnelOnly()
                updateForeground(getString(R.string.wifi_monitor_active))
                return START_STICKY
            }
            stopFiltering()
            stopSelf(startId)
            return START_NOT_STICKY
        }

        return try {
            establishDiscardingTunnel(selected)
            START_STICKY
        } catch (_: Exception) {
            stopFiltering()
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    private fun establishDiscardingTunnel(selectedPackages: List<String>) {
        val installedPackages = selectedPackages.filter { packageName ->
            packageName != packageNameOfThisApp() && try {
                packageManager.getApplicationInfo(packageName, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }.toSet()
        val packagesToRoute = VpnRoutingPolicy.selectedInstalledPackages(
            selection = AppSelection.of(installedPackages),
            installedPackages = installedPackages,
            ownPackage = packageNameOfThisApp(),
        )
        check(packagesToRoute.isNotEmpty()) { "No selected installed apps remain." }

        val builder = Builder()
            .setSession(getString(R.string.vpn_session_name))
            .setMtu(VPN_MTU)
            .addAddress(IPV4_TUN_ADDRESS, 32)
            .addRoute(IPV4_DEFAULT_ROUTE, 0)
            .addAddress(IPV6_TUN_ADDRESS, 128)
            .addRoute(IPV6_DEFAULT_ROUTE, 0)

        packagesToRoute.forEach(builder::addAllowedApplication)

        stopTunnelReader()
        val currentGeneration = readerGeneration
        val newTunnel = checkNotNull(builder.establish()) { "Android did not establish the VPN interface." }
        tunnel?.close()
        tunnel = newTunnel
        discardingPackets = true
        isVpnActive = true
        activePackageNames = packagesToRoute.toSet()
        Log.i(TAG, "vpn_interface_established selectedPackageCount=${packagesToRoute.size}; packet forwarding is not implemented")
        updateForeground(getString(R.string.vpn_notification_active))
        startDiscardReader(newTunnel, currentGeneration)
        notifyStatusChanged()
    }

    private fun startDiscardReader(descriptor: ParcelFileDescriptor, generation: Int) {
        Thread({
            try {
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    val packetBuffer = ByteArray(PACKET_BUFFER_SIZE)
                    while (discardingPackets && input.read(packetBuffer) >= 0) {
                        // Deliberately discard every packet routed here; there is no upstream tunnel.
                    }
                }
            } catch (_: IOException) {
                // Closing or revoking the TUN descriptor ends this reader.
            } finally {
                if (discardingPackets && generation == readerGeneration) {
                    isVpnActive = false
                    activePackageNames = emptySet()
                    notifyStatusChanged()
                    stopSelf()
                }
            }
        }, "AppInternetControl-PacketDiscard").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopFiltering() {
        stopWifiNetworkMonitor()
        stopTunnelOnly()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        notifyStatusChanged()
    }

    private fun stopTunnelOnly() {
        isVpnActive = false
        activePackageNames = emptySet()
        stopTunnelReader()
        tunnel?.close()
        tunnel = null
        notifyStatusChanged()
    }

    private fun stopTunnelReader() {
        discardingPackets = false
        readerGeneration += 1
    }

    private fun startForegroundCompat(contentText: String, includeLocationType: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.vpn_notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            var foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            if (includeLocationType) foregroundTypes = foregroundTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            startForeground(NOTIFICATION_ID, notification, foregroundTypes)
        } else if (Build.VERSION.SDK_INT >= 29 && includeLocationType) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateForeground(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setOngoing(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startWifiNetworkMonitor() {
        if (networkCallback != null) return
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO
            } else 0,
        ) {
            override fun onAvailable(network: Network) {
                WifiAccessRuntimeState.update(WifiNetworkIdentityReader.currentConnectedWifi(this@AppBlockingVpnService))
                reconcileAfterNetworkChange()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                WifiAccessRuntimeState.update(
                    WifiNetworkIdentityReader.currentConnectedWifi(this@AppBlockingVpnService)
                        ?: WifiNetworkIdentityReader.fromCapabilities(networkCapabilities),
                )
                reconcileAfterNetworkChange()
            }

            override fun onLost(network: Network) {
                WifiAccessRuntimeState.update(WifiNetworkIdentityReader.currentConnectedWifi(this@AppBlockingVpnService))
                reconcileAfterNetworkChange()
            }
        }
        try {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                callback,
            )
            networkCallback = callback
            isWifiMonitorActive = true
            WifiAccessRuntimeState.update(WifiNetworkIdentityReader.currentConnectedWifi(this))
            Log.i(TAG, "wifi_monitor=registered currentNetworkKnown=${WifiAccessRuntimeState.currentDefaultWifi != null}")
        } catch (exception: SecurityException) {
            isWifiMonitorActive = false
            WifiAccessRuntimeState.update(null)
            Log.w(TAG, "wifi_monitor=unavailable reason=permission_missing type=${exception.javaClass.simpleName}")
        } catch (exception: RuntimeException) {
            isWifiMonitorActive = false
            WifiAccessRuntimeState.update(null)
            Log.w(TAG, "wifi_monitor=unavailable reason=registration_failed type=${exception.javaClass.simpleName}")
        }
    }

    private fun reconcileAfterNetworkChange() {
        Log.i(TAG, "wifi_network_changed identityKnown=${WifiAccessRuntimeState.currentDefaultWifi != null}")
        ScheduleCoordinator.reconcile(this)
    }

    private fun stopWifiNetworkMonitor() {
        networkCallback?.let { callback ->
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        isWifiMonitorActive = false
        WifiAccessRuntimeState.update(null)
    }

    private fun notifyStatusChanged() {
        sendBroadcast(Intent(ACTION_STATUS_CHANGED).setPackage(packageName))
    }

    private fun packageNameOfThisApp(): String = applicationContext.packageName

    override fun onRevoke() {
        stopFiltering()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopFiltering()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STATUS_CHANGED = "com.itsmebillah.appinternetcontrol.VPN_STATUS_CHANGED"
        const val EXTRA_SELECTED_PACKAGES = "selected_packages"
        const val ACTION_STOP = "com.itsmebillah.appinternetcontrol.STOP_VPN"

        @Volatile
        var isVpnActive: Boolean = false
            private set

        @Volatile
        var activePackageNames: Set<String> = emptySet()
            private set

        @Volatile
        var isWifiMonitorActive: Boolean = false
            private set

        fun requestStop(context: android.content.Context) {
            val stopIntent = Intent(context, AppBlockingVpnService::class.java).setAction(ACTION_STOP)
            if (!isVpnActive) {
                context.stopService(stopIntent)
                return
            }
            try {
                context.startService(stopIntent)
            } catch (_: RuntimeException) {
                // Keep the safe fallback if Android disallows starting the existing service.
                context.stopService(stopIntent)
            }
        }

        private const val CHANNEL_ID = "app_blocking_vpn"
        private const val TAG = "AppIC.Vpn"
        private const val NOTIFICATION_ID = 41
        private const val VPN_MTU = 1500
        private const val PACKET_BUFFER_SIZE = 32 * 1024
        private const val IPV4_TUN_ADDRESS = "10.20.0.2"
        private const val IPV4_DEFAULT_ROUTE = "0.0.0.0"
        private const val IPV6_TUN_ADDRESS = "fd20::2"
        private const val IPV6_DEFAULT_ROUTE = "::"
    }
}
