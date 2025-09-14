// MainActivity.kt - Updated for optimized transfer engine
package com.filetransfer.minimal

import StreamingTransferEngine
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
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

    private var selectedFileUri: Uri? = null
    private val transferEngine = StreamingTransferEngine() // Using optimized engine
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

        requestPermissions()
        initViews()
        setupListeners()
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
        scrollView = findViewById(R.id.scrollView) // FIX: Initialize scrollView

        // Set defaults
        usbHostEdit.setText("127.0.0.1")
        usbPortEdit.setText("8888")
        wifiHostEdit.setText("192.168.1.9") // User needs to change this to PC's IP
        wifiPortEdit.setText("8889")
        usbCheckBox.isChecked = true
        wifiCheckBox.isChecked = true // Enable both channels by default for optimization
        transferBtn.isEnabled = false

        // Clear log with optimization info
        logText.text = """
🚀 OPTIMIZED FILE TRANSFER
✨ Features:
• 2MB chunks (32x larger)
• 20-chunk pipeline
• Dynamic load balancing
• Real-time performance monitoring

Ready to transfer files at maximum speed!

""".trimIndent()
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
                startOptimizedTransfer()
            }
        }

        // Validate at least one channel is selected
        val channelValidator = {
            if (!usbCheckBox.isChecked && !wifiCheckBox.isChecked) {
                usbCheckBox.isChecked = true
                Toast.makeText(this, "At least one channel must be selected", Toast.LENGTH_SHORT).show()
            }
        }

        usbCheckBox.setOnCheckedChangeListener { _, _ -> channelValidator() }
        wifiCheckBox.setOnCheckedChangeListener { _, _ -> channelValidator() }
    }

    private fun startOptimizedTransfer() {
        val fileUri = selectedFileUri ?: return
        val fileName = getFileName(fileUri)

        // Validate WiFi host if WiFi is enabled
        if (wifiCheckBox.isChecked) {
            val wifiHost = wifiHostEdit.text.toString()
            if (wifiHost == "0.0.0.0" || wifiHost == "192.168.1.100") {
                Toast.makeText(this, "Please enter your PC's IP address for WiFi transfer", Toast.LENGTH_LONG).show()
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
            chunkSize = 2 * 1024 * 1024 // 2MB chunks (will be used by optimized engine)
        )

        isTransferring = true
        transferBtn.isEnabled = false
        selectFileBtn.isEnabled = false

        // Clear previous logs but keep header info
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
                            progressText.text = "Progress: ${progress.percentComplete}% (${formatBytes(progress.transferredBytes)}/${formatBytes(progress.totalBytes)})"
                            statusText.text = "Speed: ${String.format("%.1f", speedMbps)} Mbps (${formatBytes(progress.speedBps)}/s)"
                        }
                    },
                    logCallback = { message ->
                        runOnUiThread {
                            logText.append("$message\n")
                            // Auto-scroll to bottom with null safety check
                            scrollView.post {
                                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                            }
                        }
                    }
                )

                runOnUiThread {
                    statusText.text = "🎉 OPTIMIZED TRANSFER COMPLETED!"
                    Toast.makeText(this@MainActivity, "Transfer completed at maximum speed! 🚀", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Transfer failed: ${e.message}"
                    logText.append("❌ ERROR: ${e.message}\n")
                    Toast.makeText(this@MainActivity, "Transfer failed: ${e.message}", Toast.LENGTH_LONG).show()
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