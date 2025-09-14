// TransferSettings.kt - Settings persistence using SharedPreferences
package com.filetransfer.minimal

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

class TransferSettings(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val tag = "TransferSettings"

    companion object {
        private const val PREFS_NAME = "file_transfer_settings"

        // Keys for SharedPreferences
        private const val KEY_USB_HOST = "usb_host"
        private const val KEY_WIFI_HOST = "wifi_host"
        private const val KEY_USB_PORT = "usb_port"
        private const val KEY_WIFI_PORT = "wifi_port"
        private const val KEY_USB_ENABLED = "usb_enabled"
        private const val KEY_WIFI_ENABLED = "wifi_enabled"
        private const val KEY_CHUNK_SIZE = "chunk_size"
        private const val KEY_FIRST_LAUNCH = "first_launch"

        // Default values
        private const val DEFAULT_USB_HOST = "127.0.0.1"
        private const val DEFAULT_WIFI_HOST = "192.168.1.9"
        private const val DEFAULT_USB_PORT = 8888
        private const val DEFAULT_WIFI_PORT = 8889
        private const val DEFAULT_USB_ENABLED = true
        private const val DEFAULT_WIFI_ENABLED = true
        private const val DEFAULT_CHUNK_SIZE = 2 * 1024 * 1024 // 2MB
    }

    // Data class to hold all settings
    data class Settings(
        val usbHost: String,
        val wifiHost: String,
        val usbPort: Int,
        val wifiPort: Int,
        val usbEnabled: Boolean,
        val wifiEnabled: Boolean,
        val chunkSize: Int,
        val isFirstLaunch: Boolean
    )

    /**
     * Load all settings from SharedPreferences
     */
    fun loadSettings(): Settings {
        val settings = Settings(
            usbHost = prefs.getString(KEY_USB_HOST, DEFAULT_USB_HOST) ?: DEFAULT_USB_HOST,
            wifiHost = prefs.getString(KEY_WIFI_HOST, DEFAULT_WIFI_HOST) ?: DEFAULT_WIFI_HOST,
            usbPort = prefs.getInt(KEY_USB_PORT, DEFAULT_USB_PORT),
            wifiPort = prefs.getInt(KEY_WIFI_PORT, DEFAULT_WIFI_PORT),
            usbEnabled = prefs.getBoolean(KEY_USB_ENABLED, DEFAULT_USB_ENABLED),
            wifiEnabled = prefs.getBoolean(KEY_WIFI_ENABLED, DEFAULT_WIFI_ENABLED),
            chunkSize = prefs.getInt(KEY_CHUNK_SIZE, DEFAULT_CHUNK_SIZE),
            isFirstLaunch = prefs.getBoolean(KEY_FIRST_LAUNCH, true)
        )

        Log.d(tag, "Settings loaded: USB=${settings.usbHost}:${settings.usbPort}, WiFi=${settings.wifiHost}:${settings.wifiPort}")
        return settings
    }

    /**
     * Save USB host setting
     */
    fun saveUsbHost(host: String) {
        prefs.edit().putString(KEY_USB_HOST, host).apply()
        Log.d(tag, "USB host saved: $host")
    }

    /**
     * Save WiFi host setting
     */
    fun saveWifiHost(host: String) {
        prefs.edit().putString(KEY_WIFI_HOST, host).apply()
        Log.d(tag, "WiFi host saved: $host")
    }

    /**
     * Save USB port setting
     */
    fun saveUsbPort(port: Int) {
        prefs.edit().putInt(KEY_USB_PORT, port).apply()
        Log.d(tag, "USB port saved: $port")
    }

    /**
     * Save WiFi port setting
     */
    fun saveWifiPort(port: Int) {
        prefs.edit().putInt(KEY_WIFI_PORT, port).apply()
        Log.d(tag, "WiFi port saved: $port")
    }

    /**
     * Save USB enabled state
     */
    fun saveUsbEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USB_ENABLED, enabled).apply()
        Log.d(tag, "USB enabled saved: $enabled")
    }

    /**
     * Save WiFi enabled state
     */
    fun saveWifiEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ENABLED, enabled).apply()
        Log.d(tag, "WiFi enabled saved: $enabled")
    }

    /**
     * Save chunk size setting
     */
    fun saveChunkSize(chunkSize: Int) {
        prefs.edit().putInt(KEY_CHUNK_SIZE, chunkSize).apply()
        Log.d(tag, "Chunk size saved: $chunkSize")
    }

    /**
     * Save all settings at once (bulk save)
     */
    fun saveAllSettings(settings: Settings) {
        prefs.edit()
            .putString(KEY_USB_HOST, settings.usbHost)
            .putString(KEY_WIFI_HOST, settings.wifiHost)
            .putInt(KEY_USB_PORT, settings.usbPort)
            .putInt(KEY_WIFI_PORT, settings.wifiPort)
            .putBoolean(KEY_USB_ENABLED, settings.usbEnabled)
            .putBoolean(KEY_WIFI_ENABLED, settings.wifiEnabled)
            .putInt(KEY_CHUNK_SIZE, settings.chunkSize)
            .putBoolean(KEY_FIRST_LAUNCH, false) // Mark as not first launch
            .apply()

        Log.d(tag, "All settings saved")
    }

    /**
     * Reset all settings to defaults
     */
    fun resetToDefaults() {
        prefs.edit()
            .putString(KEY_USB_HOST, DEFAULT_USB_HOST)
            .putString(KEY_WIFI_HOST, DEFAULT_WIFI_HOST)
            .putInt(KEY_USB_PORT, DEFAULT_USB_PORT)
            .putInt(KEY_WIFI_PORT, DEFAULT_WIFI_PORT)
            .putBoolean(KEY_USB_ENABLED, DEFAULT_USB_ENABLED)
            .putBoolean(KEY_WIFI_ENABLED, DEFAULT_WIFI_ENABLED)
            .putInt(KEY_CHUNK_SIZE, DEFAULT_CHUNK_SIZE)
            .putBoolean(KEY_FIRST_LAUNCH, false)
            .apply()

        Log.d(tag, "Settings reset to defaults")
    }

    /**
     * Check if this is the first app launch
     */
    fun isFirstLaunch(): Boolean {
        return prefs.getBoolean(KEY_FIRST_LAUNCH, true)
    }

    /**
     * Mark first launch as complete
     */
    fun markFirstLaunchComplete() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply()
    }

    /**
     * Get individual setting values (convenience methods)
     */
    fun getUsbHost(): String = prefs.getString(KEY_USB_HOST, DEFAULT_USB_HOST) ?: DEFAULT_USB_HOST
    fun getWifiHost(): String = prefs.getString(KEY_WIFI_HOST, DEFAULT_WIFI_HOST) ?: DEFAULT_WIFI_HOST
    fun getUsbPort(): Int = prefs.getInt(KEY_USB_PORT, DEFAULT_USB_PORT)
    fun getWifiPort(): Int = prefs.getInt(KEY_WIFI_PORT, DEFAULT_WIFI_PORT)
    fun isUsbEnabled(): Boolean = prefs.getBoolean(KEY_USB_ENABLED, DEFAULT_USB_ENABLED)
    fun isWifiEnabled(): Boolean = prefs.getBoolean(KEY_WIFI_ENABLED, DEFAULT_WIFI_ENABLED)
    fun getChunkSize(): Int = prefs.getInt(KEY_CHUNK_SIZE, DEFAULT_CHUNK_SIZE)

    /**
     * Export settings to a string for debugging/sharing
     */
    fun exportSettings(): String {
        val settings = loadSettings()
        return """
            Transfer Settings:
            USB: ${settings.usbHost}:${settings.usbPort} (${if (settings.usbEnabled) "enabled" else "disabled"})
            WiFi: ${settings.wifiHost}:${settings.wifiPort} (${if (settings.wifiEnabled) "enabled" else "disabled"})
            Chunk Size: ${settings.chunkSize / (1024 * 1024)}MB
        """.trimIndent()
    }
}
