package com.filetransfer.minimal

import android.content.Context
import android.content.SharedPreferences

class TransferSettings private constructor(context: Context) {
    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "transfer_settings"

        // Preference keys
        private const val KEY_USB_ENABLED = "usb_enabled"
        private const val KEY_WIFI_ENABLED = "wifi_enabled"
        private const val KEY_USB_HOST = "usb_host"
        private const val KEY_USB_PORT = "usb_port"
        private const val KEY_WIFI_HOST = "wifi_host"
        private const val KEY_WIFI_PORT = "wifi_port"
        private const val KEY_CHUNK_SIZE = "chunk_size"
        private const val KEY_AUTO_RESUME = "auto_resume"
        private const val KEY_BACKGROUND_TRANSFERS = "background_transfers"
        private const val KEY_NOTIFICATION_ENABLED = "notification_enabled"
        private const val KEY_DYNAMIC_CHUNK_SIZE = "dynamic_chunk_size"
        private const val KEY_MAX_CONCURRENT_TRANSFERS = "max_concurrent_transfers"

        // Default values
        private const val DEFAULT_USB_ENABLED = true
        private const val DEFAULT_WIFI_ENABLED = true
        private const val DEFAULT_USB_HOST = "127.0.0.1"
        private const val DEFAULT_USB_PORT = 8888
        private const val DEFAULT_WIFI_HOST = "192.168.1.100"
        private const val DEFAULT_WIFI_PORT = 8889
        private const val DEFAULT_CHUNK_SIZE = 2 * 1024 * 1024 // 2MB
        private const val DEFAULT_AUTO_RESUME = true
        private const val DEFAULT_BACKGROUND_TRANSFERS = true
        private const val DEFAULT_NOTIFICATION_ENABLED = true
        private const val DEFAULT_DYNAMIC_CHUNK_SIZE = true
        private const val DEFAULT_MAX_CONCURRENT_TRANSFERS = 3

        @Volatile
        private var INSTANCE: TransferSettings? = null

        fun getInstance(context: Context): TransferSettings {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TransferSettings(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // USB Settings
    var usbEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_USB_ENABLED, DEFAULT_USB_ENABLED)
        set(value) = sharedPreferences.edit().putBoolean(KEY_USB_ENABLED, value).apply()

    var usbHost: String
        get() = sharedPreferences.getString(KEY_USB_HOST, DEFAULT_USB_HOST) ?: DEFAULT_USB_HOST
        set(value) = sharedPreferences.edit().putString(KEY_USB_HOST, value).apply()

    var usbPort: Int
        get() = sharedPreferences.getInt(KEY_USB_PORT, DEFAULT_USB_PORT)
        set(value) = sharedPreferences.edit().putInt(KEY_USB_PORT, value).apply()

    // WiFi Settings
    var wifiEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_WIFI_ENABLED, DEFAULT_WIFI_ENABLED)
        set(value) = sharedPreferences.edit().putBoolean(KEY_WIFI_ENABLED, value).apply()

    var wifiHost: String
        get() = sharedPreferences.getString(KEY_WIFI_HOST, DEFAULT_WIFI_HOST) ?: DEFAULT_WIFI_HOST
        set(value) = sharedPreferences.edit().putString(KEY_WIFI_HOST, value).apply()

    var wifiPort: Int
        get() = sharedPreferences.getInt(KEY_WIFI_PORT, DEFAULT_WIFI_PORT)
        set(value) = sharedPreferences.edit().putInt(KEY_WIFI_PORT, value).apply()

    // Transfer Settings
    var chunkSize: Int
        get() = sharedPreferences.getInt(KEY_CHUNK_SIZE, DEFAULT_CHUNK_SIZE)
        set(value) = sharedPreferences.edit().putInt(KEY_CHUNK_SIZE, value).apply()

    var autoResume: Boolean
        get() = sharedPreferences.getBoolean(KEY_AUTO_RESUME, DEFAULT_AUTO_RESUME)
        set(value) = sharedPreferences.edit().putBoolean(KEY_AUTO_RESUME, value).apply()

    var backgroundTransfers: Boolean
        get() = sharedPreferences.getBoolean(KEY_BACKGROUND_TRANSFERS, DEFAULT_BACKGROUND_TRANSFERS)
        set(value) = sharedPreferences.edit().putBoolean(KEY_BACKGROUND_TRANSFERS, value).apply()

    var notificationEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_NOTIFICATION_ENABLED, DEFAULT_NOTIFICATION_ENABLED)
        set(value) = sharedPreferences.edit().putBoolean(KEY_NOTIFICATION_ENABLED, value).apply()

    var dynamicChunkSize: Boolean
        get() = sharedPreferences.getBoolean(KEY_DYNAMIC_CHUNK_SIZE, DEFAULT_DYNAMIC_CHUNK_SIZE)
        set(value) = sharedPreferences.edit().putBoolean(KEY_DYNAMIC_CHUNK_SIZE, value).apply()

    var maxConcurrentTransfers: Int
        get() = sharedPreferences.getInt(KEY_MAX_CONCURRENT_TRANSFERS, DEFAULT_MAX_CONCURRENT_TRANSFERS)
        set(value) = sharedPreferences.edit().putInt(KEY_MAX_CONCURRENT_TRANSFERS, value).apply()

    fun getTransferConfig(): TransferConfig {
        return TransferConfig(
            usbEnabled = usbEnabled,
            wifiEnabled = wifiEnabled,
            usbHost = usbHost,
            usbPort = usbPort,
            wifiHost = wifiHost,
            wifiPort = wifiPort,
            chunkSize = chunkSize
        )
    }

    fun saveTransferConfig(config: TransferConfig) {
        sharedPreferences.edit().apply {
            putBoolean(KEY_USB_ENABLED, config.usbEnabled)
            putBoolean(KEY_WIFI_ENABLED, config.wifiEnabled)
            putString(KEY_USB_HOST, config.usbHost)
            putInt(KEY_USB_PORT, config.usbPort)
            putString(KEY_WIFI_HOST, config.wifiHost)
            putInt(KEY_WIFI_PORT, config.wifiPort)
            putInt(KEY_CHUNK_SIZE, config.chunkSize)
            apply()
        }
    }

    fun resetToDefaults() {
        sharedPreferences.edit().clear().apply()
    }

    fun exportSettings(): Map<String, Any> {
        return mapOf(
            KEY_USB_ENABLED to usbEnabled,
            KEY_WIFI_ENABLED to wifiEnabled,
            KEY_USB_HOST to usbHost,
            KEY_USB_PORT to usbPort,
            KEY_WIFI_HOST to wifiHost,
            KEY_WIFI_PORT to wifiPort,
            KEY_CHUNK_SIZE to chunkSize,
            KEY_AUTO_RESUME to autoResume,
            KEY_BACKGROUND_TRANSFERS to backgroundTransfers,
            KEY_NOTIFICATION_ENABLED to notificationEnabled,
            KEY_DYNAMIC_CHUNK_SIZE to dynamicChunkSize,
            KEY_MAX_CONCURRENT_TRANSFERS to maxConcurrentTransfers
        )
    }
}
