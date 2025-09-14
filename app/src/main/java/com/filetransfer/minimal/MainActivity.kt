// MainActivity.kt
package com.filetransfer.minimal

import StreamingTransferEngine
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var filePathText: TextView
    private lateinit var selectFileBtn: Button
    private lateinit var usbHostEdit: EditText
    private lateinit var wifiHostEdit: EditText
    private lateinit var usbPortEdit: EditText
    private lateinit var wifiPortEdit: EditText
    private lateinit var usbCheckBox: CheckBox
    private lateinit var wifiCheckBox: CheckBox
    private lateinit var transferBtn: Button
    private lateinit var progressText: TextView
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var transferSettings: TransferSettings
    private var selectedFileUri: Uri? = null
    private val transferEngine = StreamingTransferEngine()
    private var isTransferring = false
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedFileUri = result.data?.data
            selectedFileUri?.let { uri ->
                val fileName = getFileName(uri)
                val fileSize = getFileSize(uri)
                filePathText.text = "Selected: $fileName (${formatBytes(fileSize)})"
                transferBtn.isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        transferSettings = TransferSettings(this)

        requestPermissions()
        initViews()
        setupListeners()
        if (transferSettings.isFirstLaunch()) {
            showFirstLaunchWelcome()
            transferSettings.markFirstLaunchComplete()
        }
    }

    private fun initViews() {
        filePathText = findViewById(R.id.filePathText)
        selectFileBtn = findViewById(R.id.selectFileBtn)
        usbHostEdit = findViewById(R.id.usbHostEdit)
        wifiHostEdit = findViewById(R.id.wifiHostEdit)
        usbPortEdit = findViewById(R.id.usbPortEdit)
        wifiPortEdit = findViewById(R.id.wifiPortEdit)
        usbCheckBox = findViewById(R.id.usbCheckBox)
        wifiCheckBox = findViewById(R.id.wifiCheckBox)
        transferBtn = findViewById(R.id.transferBtn)
        progressText = findViewById(R.id.progressText)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        scrollView = findViewById(R.id.scrollView)

        loadSettingsIntoUI()

        transferBtn.isEnabled = false

        val settingsInfo =
            if (transferSettings.isFirstLaunch()) "Using default settings" else "Settings loaded from previous session"
        logText.text = """
🚀 OPTIMIZED FILE TRANSFER
✨ Features:
• 2MB chunks
• 20-chunk pipeline
• Dynamic load balancing
• Real-time performance monitoring

📱 $settingsInfo

Ready to transfer files at maximum speed!

""".trimIndent()
    }

    /**
     * Load settings from SharedPreferences and populate UI fields
     */
    private fun loadSettingsIntoUI() {
        val settings = transferSettings.loadSettings()

        usbHostEdit.setText(settings.usbHost)
        wifiHostEdit.setText(settings.wifiHost)
        usbPortEdit.setText(settings.usbPort.toString())
        wifiPortEdit.setText(settings.wifiPort.toString())
        usbCheckBox.isChecked = settings.usbEnabled
        wifiCheckBox.isChecked = settings.wifiEnabled

        Log.d("MainActivity", "UI populated with saved settings")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permissions denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupListeners() {
        selectFileBtn.setOnClickListener {
            if (!isTransferring) {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                filePickerLauncher.launch(intent)
            }
        }

        transferBtn.setOnClickListener {
            if (!isTransferring) {
                saveCurrentUISettings()
                startTransfer()
            }
        }

        setupAutoSaveListeners()

        val channelValidator = {
            if (!usbCheckBox.isChecked && !wifiCheckBox.isChecked) {
                usbCheckBox.isChecked = true
                Toast.makeText(this, "At least one channel must be selected", Toast.LENGTH_SHORT)
                    .show()
                transferSettings.saveUsbEnabled(true)
            }
        }

        usbCheckBox.setOnCheckedChangeListener { _, isChecked ->
            channelValidator()
            transferSettings.saveUsbEnabled(isChecked)
        }

        wifiCheckBox.setOnCheckedChangeListener { _, isChecked ->
            channelValidator()
            transferSettings.saveWifiEnabled(isChecked)
        }
    }

    /**
     * Setup auto-save listeners for all input fields
     */
    private fun setupAutoSaveListeners() {
        // USB Host - save when focus changes or text editing finishes
        usbHostEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val host = usbHostEdit.text.toString().trim()
                if (host.isNotEmpty() && host != transferSettings.getUsbHost()) {
                    transferSettings.saveUsbHost(host)
                    Toast.makeText(this, "USB host saved", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // WiFi Host - save when focus changes
        wifiHostEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val host = wifiHostEdit.text.toString().trim()
                if (host.isNotEmpty() && host != transferSettings.getWifiHost()) {
                    transferSettings.saveWifiHost(host)
                    Toast.makeText(this, "WiFi host saved", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // USB Port - save when focus changes
        usbPortEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val portText = usbPortEdit.text.toString().trim()
                if (portText.isNotEmpty()) {
                    try {
                        val port = portText.toInt()
                        if (port in 1..65535 && port != transferSettings.getUsbPort()) {
                            transferSettings.saveUsbPort(port)
                            Toast.makeText(this, "USB port saved", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: NumberFormatException) {
                        // Reset to saved value if invalid
                        usbPortEdit.setText(transferSettings.getUsbPort().toString())
                        Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // WiFi Port - save when focus changes
        wifiPortEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val portText = wifiPortEdit.text.toString().trim()
                if (portText.isNotEmpty()) {
                    try {
                        val port = portText.toInt()
                        if (port in 1..65535 && port != transferSettings.getWifiPort()) {
                            transferSettings.saveWifiPort(port)
                            Toast.makeText(this, "WiFi port saved", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: NumberFormatException) {
                        // Reset to saved value if invalid
                        wifiPortEdit.setText(transferSettings.getWifiPort().toString())
                        Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /**
     * Save current UI state to settings (called before transfer)
     */
    private fun saveCurrentUISettings() {
        val settings = TransferSettings.Settings(
            usbHost = usbHostEdit.text.toString().trim().takeIf { it.isNotEmpty() }
                ?: transferSettings.getUsbHost(),
            wifiHost = wifiHostEdit.text.toString().trim().takeIf { it.isNotEmpty() }
                ?: transferSettings.getWifiHost(),
            usbPort = try {
                usbPortEdit.text.toString().toInt()
            } catch (e: Exception) {
                transferSettings.getUsbPort()
            },
            wifiPort = try {
                wifiPortEdit.text.toString().toInt()
            } catch (e: Exception) {
                transferSettings.getWifiPort()
            },
            usbEnabled = usbCheckBox.isChecked,
            wifiEnabled = wifiCheckBox.isChecked,
            chunkSize = transferSettings.getChunkSize(),
            isFirstLaunch = false
        )

        transferSettings.saveAllSettings(settings)
        Log.d("MainActivity", "Current UI settings saved")
    }

    /**
     * Show welcome message for first-time users
     */
    private fun showFirstLaunchWelcome() {
        Toast.makeText(
            this,
            "Welcome! Your connection settings will be automatically saved for future use.",
            Toast.LENGTH_LONG
        ).show()
    }

    // Add menu for settings management
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.add("Reset Settings")?.setOnMenuItemClickListener {
            showResetSettingsDialog()
            true
        }
        menu?.add("Export Settings")?.setOnMenuItemClickListener {
            showExportedSettings()
            true
        }
        return true
    }

    private fun showResetSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset Settings")
            .setMessage("This will reset all connection settings to defaults. Continue?")
            .setPositiveButton("Reset") { _, _ ->
                transferSettings.resetToDefaults()
                loadSettingsIntoUI()
                Toast.makeText(this, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showExportedSettings() {
        val settingsText = transferSettings.exportSettings()
        AlertDialog.Builder(this)
            .setTitle("Current Settings")
            .setMessage(settingsText)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun startTransfer() {
        val fileUri = selectedFileUri ?: return
        val fileName = getFileName(fileUri)

        // Validate WiFi host if WiFi is enabled
        if (wifiCheckBox.isChecked) {
            val wifiHost = wifiHostEdit.text.toString()
            if (wifiHost == "0.0.0.0" || wifiHost == "192.168.1.100") {
                Toast.makeText(
                    this,
                    "Please enter your PC's IP address for WiFi transfer",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
        }

        val config = TransferConfig(
            usbEnabled = usbCheckBox.isChecked,
            wifiEnabled = wifiCheckBox.isChecked,
            usbHost = usbHostEdit.text.toString(),
            usbPort = usbPortEdit.text.toString().toIntOrNull() ?: 8888,
            wifiHost = wifiHostEdit.text.toString(),
            wifiPort = wifiPortEdit.text.toString().toIntOrNull() ?: 8889,
            chunkSize = 2 * 1024 * 1024 // 2MB chunks
        )

        isTransferring = true
        transferBtn.isEnabled = false
        selectFileBtn.isEnabled = false

        logText.text = """
🚀 OPTIMIZED FILE TRANSFER
✨ Features: 2MB chunks • 20-chunk pipeline • Dynamic load balancing

TRANSFER STARTED
================

""".trimIndent()

        lifecycleScope.launch {
            try {
                val inputStream = contentResolver.openInputStream(fileUri)!!
                val fileSize = getFileSize(fileUri)

                transferEngine.startTransfer(
                    config = config,
                    fileName = fileName,
                    fileSize = fileSize,
                    inputStream = inputStream,
                    progressCallback = { progress ->
                        runOnUiThread {
                            val speedMbps = (progress.speedBps * 8.0) / (1024 * 1024)
                            progressText.text =
                                "Progress: ${progress.percentComplete}% (${formatBytes(progress.transferredBytes)}/${
                                    formatBytes(progress.totalBytes)
                                })"
                            statusText.text = "Speed: ${String.format("%.1f", speedMbps)} Mbps (${
                                formatBytes(progress.speedBps)
                            }/s)"
                        }
                    },
                    logCallback = { message ->
                        runOnUiThread {
                            logText.append("$message\n")

                            scrollView.post {
                                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                            }
                        }
                    }
                )

                runOnUiThread {
                    statusText.text = "🎉 OPTIMIZED TRANSFER COMPLETED!"
                    Toast.makeText(
                        this@MainActivity,
                        "Transfer completed at maximum speed! 🚀",
                        Toast.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Transfer failed: ${e.message}"
                    logText.append("❌ ERROR: ${e.message}\n")
                    Toast.makeText(
                        this@MainActivity,
                        "Transfer failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.e("MainActivity", "Optimized transfer failed", e)
                }
            } finally {
                runOnUiThread {
                    isTransferring = false
                    transferBtn.isEnabled = selectedFileUri != null
                    selectFileBtn.isEnabled = true
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String {
        var fileName = "unknown"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                fileName = cursor.getString(nameIndex)
            }
        }
        return fileName
    }

    private fun getFileSize(uri: Uri): Long {
        var fileSize = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (cursor.moveToFirst() && sizeIndex >= 0) {
                fileSize = cursor.getLong(sizeIndex)
            }
        }
        return fileSize
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return String.format("%.1f %s", size, units[unitIndex])
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
        }
    }
}