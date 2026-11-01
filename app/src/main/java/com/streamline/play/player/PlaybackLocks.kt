package com.streamline.play.player

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/**
 * Keeps the CPU and Wi-Fi radio awake while a live channel plays. Without these the
 * network interface can be parked after a couple of minutes of low throughput, which
 * surfaces as `HttpDataSourceException` or `ERROR_CODE_IO_UNSPECIFIED`.
 */
class PlaybackLocks(context: Context) {

    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    fun acquire() {
        if (cpuLock == null) {
            cpuLock = runCatching {
                powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CPU_TAG)?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.getOrNull()
        }
        if (wifiLock == null) {
            wifiLock = runCatching { createWifiLock()?.apply { acquire() } }.getOrNull()
        }
    }

    @Synchronized
    fun release() {
        runCatching { cpuLock?.takeIf { it.isHeld }?.release() }
        cpuLock = null
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    @Suppress("DEPRECATION")
    private fun createWifiLock(): WifiManager.WifiLock? {
        val manager = wifiManager ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                return manager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_TAG)
            }.onFailure { /* device rejects low latency mode, fall back below */ }
        }
        return runCatching {
            manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_TAG)
        }.getOrNull()
    }

    private companion object {
        const val CPU_TAG = "StreamLine:live-playback"
        const val WIFI_TAG = "StreamLine:live-wifi"
    }
}
