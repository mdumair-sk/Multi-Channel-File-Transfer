package com.filetransfer.minimal

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: TransferSettings

    // UI Components
    private lateinit var usbEnabledSwitch: Switch
    private lateinit var wifiEnabledSwitch: Switch
    private lateinit var usbHostEdit: EditText
    private lateinit var usbPortEdit: EditText
    private lateinit var wifiHostEdit: EditText
    private lateinit var wifiPortEdit: EditText
    private lateinit var chunkSizeSeekBar: SeekBar
    private lateinit var chunkSizeText: TextView
    private lateinit var autoResumeSwitch: Switch
    private lateinit var backgroundTransfersSwitch: Switch
    private lateinit var notificationSwitch: Switch
    private lateinit var dynamicChunkSizeSwitch: Switch
    private lateinit var maxConcurrentSeekBar: SeekBar
    private lateinit var maxConcurrentText: TextView
    private lateinit var saveButton: Button
    private lateinit var resetButton: Button
    private lateinit var exportButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = TransferSettings.getInstance(this)

        initViews()
        loadSettings()
        setupListeners()

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Transfer Settings"
    }

    private fun initViews() {
        usbEnabledSwitch = findViewById(R.id.usbEnabledSwitch)
        wifiEnabledSwitch = findViewById(R.id.wifiEnabledSwitch)
        usbHostEdit = findViewById(R.id.usbHostEdit)
        usbPortEdit = findViewById(R.id.usbPortEdit)
        wifiHostEdit = findViewById(R.id.wifiHostEdit)
        wifiPortEdit = findViewById(R.id.wifiPortEdit)
        chunkSizeSeekBar = findViewById(R.id.chunkSizeSeekBar)
        chunkSizeText = findViewById(R.id.chunkSizeText)
        autoResumeSwitch = findViewById(R.id.autoResumeSwitch)
        backgroundTransfersSwitch = findViewById(R.id.backgroundTransfersSwitch)
        notificationSwitch = findViewById(R.id.notificationSwitch)
        dynamicChunkSizeSwitch = findViewById(R.id.dynamicChunkSizeSwitch)
        maxConcurrentSeekBar = findViewById(R.id.maxConcurrentSeekBar)
        maxConcurrentText = findViewById(R.id.maxConcurrentText)
        saveButton = findViewById(R.id.saveButton)
        resetButton = findViewById(R.id.resetButton)
        exportButton = findViewById(R.id.exportButton)
    }

    private fun loadSettings() {
        // Connection settings
        usbEnabledSwitch.isChecked = settings.usbEnabled
        wifiEnabledSwitch.isChecked = settings.wifiEnabled
        usbHostEdit.setText(settings.usbHost)
        usbPortEdit.setText(settings.usbPort.toString())
        wifiHostEdit.setText(settings.wifiHost)
        wifiPortEdit.setText(settings.wifiPort.toString())

        // Transfer settings
        val chunkSizeMB = settings.chunkSize / (1024 * 1024)
        chunkSizeSeekBar.progress = when (chunkSizeMB) {
            1 -> 0
            2 -> 1
            4 -> 2
            8 -> 3
            else -> 1 // Default to 2MB
        }
        updateChunkSizeText(chunkSizeSeekBar.progress)

        // Advanced settings
        autoResumeSwitch.isChecked = settings.autoResume
        backgroundTransfersSwitch.isChecked = settings.backgroundTransfers
        notificationSwitch.isChecked = settings.notificationEnabled
        dynamicChunkSizeSwitch.isChecked = settings.dynamicChunkSize

        maxConcurrentSeekBar.progress = settings.maxConcurrentTransfers - 1
        updateMaxConcurrentText(settings.maxConcurrentTransfers)
    }

    private fun setupListeners() {
        // Chunk size slider
        chunkSizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateChunkSizeText(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Max concurrent transfers slider
        maxConcurrentSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateMaxConcurrentText(progress + 1)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Save button
        saveButton.setOnClickListener {
            saveSettings()
        }

        // Reset button
        resetButton.setOnClickListener {
            showResetDialog()
        }

        // Export button
        exportButton.setOnClickListener {
            exportSettings()
        }

        // Dynamic chunk size switch
        dynamicChunkSizeSwitch.setOnCheckedChangeListener { _, isChecked ->
            chunkSizeSeekBar.isEnabled = !isChecked
            if (isChecked) {
                chunkSizeText.text = "Dynamic (Auto-adjusted)"
            } else {
                updateChunkSizeText(chunkSizeSeekBar.progress)
            }
        }
    }

    private fun updateChunkSizeText(progress: Int) {
        val sizeMB = when (progress) {
            0 -> 1
            1 -> 2
            2 -> 4
            3 -> 8
            else -> 2
        }
        chunkSizeText.text = "${sizeMB}MB"
    }

    private fun updateMaxConcurrentText(count: Int) {
        maxConcurrentText.text = "$count transfers"
    }

    private fun saveSettings() {
        try {
            // Validate inputs
            val usbPort = usbPortEdit.text.toString().toIntOrNull()
            val wifiPort = wifiPortEdit.text.toString().toIntOrNull()

            if (usbPort == null || usbPort !in 1..65535) {
                showError("Invalid USB port. Must be between 1 and 65535.")
                return
            }

            if (wifiPort == null || wifiPort !in 1..65535) {
                showError("Invalid WiFi port. Must be between 1 and 65535.")
                return
            }

            if (usbHostEdit.text.toString().isBlank()) {
                showError("USB host cannot be empty.")
                return
            }

            if (wifiHostEdit.text.toString().isBlank()) {
                showError("WiFi host cannot be empty.")
                return
            }

            // Save settings
            settings.usbEnabled = usbEnabledSwitch.isChecked
            settings.wifiEnabled = wifiEnabledSwitch.isChecked
            settings.usbHost = usbHostEdit.text.toString().trim()
            settings.usbPort = usbPort
            settings.wifiHost = wifiHostEdit.text.toString().trim()
            settings.wifiPort = wifiPort

            // Chunk size
            if (!dynamicChunkSizeSwitch.isChecked) {
                val chunkSizeMB = when (chunkSizeSeekBar.progress) {
                    0 -> 1
                    1 -> 2
                    2 -> 4
                    3 -> 8
                    else -> 2
                }
                settings.chunkSize = chunkSizeMB * 1024 * 1024
            }

            // Advanced settings
            settings.autoResume = autoResumeSwitch.isChecked
            settings.backgroundTransfers = backgroundTransfersSwitch.isChecked
            settings.notificationEnabled = notificationSwitch.isChecked
            settings.dynamicChunkSize = dynamicChunkSizeSwitch.isChecked
            settings.maxConcurrentTransfers = maxConcurrentSeekBar.progress + 1

            Toast.makeText(this, "Settings saved successfully!", Toast.LENGTH_SHORT).show()
            finish()

        } catch (e: Exception) {
            showError("Error saving settings: ${e.message}")
        }
    }

    private fun showResetDialog() {
        AlertDialog.Builder(this)
            .setTitle("Reset Settings")
            .setMessage("This will reset all settings to their default values. Are you sure?")
            .setPositiveButton("Reset") { _, _ ->
                resetSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetSettings() {
        settings.resetToDefaults()
        loadSettings()
        Toast.makeText(this, "Settings reset to defaults", Toast.LENGTH_SHORT).show()
    }

    private fun exportSettings() {
        try {
            val settingsMap = settings.exportSettings()
            val settingsJson = settingsMap.entries.joinToString("\n") { "${it.key}: ${it.value}" }

            // Create share intent
            val shareIntent = android.content.Intent().apply {
                action = android.content.Intent.ACTION_SEND
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, "File Transfer Settings:\n\n$settingsJson")
                putExtra(android.content.Intent.EXTRA_SUBJECT, "File Transfer Settings Export")
            }

            startActivity(android.content.Intent.createChooser(shareIntent, "Export Settings"))

        } catch (e: Exception) {
            showError("Error exporting settings: ${e.message}")
        }
    }

    private fun showError(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
