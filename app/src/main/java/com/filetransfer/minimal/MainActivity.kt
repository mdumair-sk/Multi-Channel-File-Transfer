package com.filetransfer.minimal

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import kotlinx.coroutines.launch
import java.util.*

class MainActivity : AppCompatActivity() {

    // UI Components
    private lateinit var queueRecyclerView: RecyclerView
    private lateinit var addFilesButton: Button
    private lateinit var settingsButton: Button
    private lateinit var clearQueueButton: Button
    private lateinit var statusText: TextView
    private lateinit var queueStatsText: TextView
    private lateinit var queueCountText: TextView
    private lateinit var globalProgressBar: ProgressBar
    private lateinit var startTransferButton: Button
    private lateinit var emptyStateLayout: LinearLayout

    // Service and adapter
    private lateinit var queueAdapter: TransferQueueAdapter
    private var transferService: TransferService? = null
    private var serviceBound = false

    // Settings and state
    private lateinit var settings: TransferSettings
    private val transferQueue = mutableListOf<QueuedTransfer>()

    // Database access
    private val database by lazy { TransferDatabase.getDatabase(this) }
    private val transferDao by lazy { database.transferStateDao() }

    // File picker for multiple files
    private val multipleFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { intent ->
                handleMultipleFileSelection(intent)
            }
        }
    }

    // Service connection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TransferService.TransferBinder
            transferService = binder.getService()
            serviceBound = true

            // Observe service state
            observeServiceState()
            Log.d("MainActivity", "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            transferService = null
            serviceBound = false
            Log.d("MainActivity", "Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = TransferSettings.getInstance(this)

        requestPermissions()
        initViews()
        setupRecyclerView()
        setupListeners()

        // Start and bind to service
        startTransferService()

        // Check for pending transfers
        checkPendingTransfers()
    }

    override fun onStart() {
        super.onStart()
        bindTransferService()
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun initViews() {
        queueRecyclerView = findViewById(R.id.queueRecyclerView)
        addFilesButton = findViewById(R.id.addFilesButton)
        settingsButton = findViewById(R.id.settingsButton)
        clearQueueButton = findViewById(R.id.clearQueueButton)
        statusText = findViewById(R.id.statusText)
        queueStatsText = findViewById(R.id.queueStatsText)
        queueCountText = findViewById(R.id.queueCountText)
        globalProgressBar = findViewById(R.id.globalProgressBar)
        startTransferButton = findViewById(R.id.startTransferButton)
        emptyStateLayout = findViewById(R.id.emptyStateLayout)

        // Initial state
        statusText.text = "Ready to transfer files"
        updateQueueStats()
        updateEmptyState()
    }

    private fun setupRecyclerView() {
        queueAdapter = TransferQueueAdapter(
            onItemClick = { transfer -> showTransferDetails(transfer) },
            onPauseClick = { transfer -> pauseTransfer(transfer) },
            onResumeClick = { transfer -> resumeTransfer(transfer) },
            onCancelClick = { transfer -> cancelTransfer(transfer) },
            onPriorityChange = { transfer, priority -> changePriority(transfer, priority) }
        )

        queueRecyclerView.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = queueAdapter
        }

        // Setup drag and drop for reordering
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition

                if (fromPosition < transferQueue.size && toPosition < transferQueue.size) {
                    Collections.swap(transferQueue, fromPosition, toPosition)
                    queueAdapter.notifyItemMoved(fromPosition, toPosition)
                    updateQueueStats()
                }
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position < transferQueue.size) {
                    val transfer = transferQueue[position]

                    when (direction) {
                        ItemTouchHelper.LEFT -> {
                            // Cancel transfer
                            cancelTransfer(transfer)
                        }
                        ItemTouchHelper.RIGHT -> {
                            // Pause/Resume transfer
                            if (transfer.status == TransferStatus.PAUSED) {
                                resumeTransfer(transfer)
                            } else if (transfer.status == TransferStatus.IN_PROGRESS) {
                                pauseTransfer(transfer)
                            }
                        }
                    }
                }
            }
        })

        itemTouchHelper.attachToRecyclerView(queueRecyclerView)
    }

    private fun setupListeners() {
        addFilesButton.setOnClickListener {
            selectMultipleFiles()
        }

        settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        clearQueueButton.setOnClickListener {
            showClearQueueDialog()
        }

        startTransferButton.setOnClickListener {
            if (transferQueue.isNotEmpty()) {
                startQueuedTransfers()
                Toast.makeText(this, "Starting transfers...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No files in queue to transfer", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun selectMultipleFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }

        multipleFilePickerLauncher.launch(intent)
    }

    private fun handleMultipleFileSelection(intent: Intent) {
        val uris = mutableListOf<Uri>()

        // Handle multiple file selection
        intent.clipData?.let { clipData ->
            for (i in 0 until clipData.itemCount) {
                uris.add(clipData.getItemAt(i).uri)
            }
        } ?: intent.data?.let { uri ->
            uris.add(uri)
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, "No files selected", Toast.LENGTH_SHORT).show()
            return
        }

        // Process selected files
        lifecycleScope.launch {
            try {
                var addedCount = 0

                for (uri in uris) {
                    val filename = getFileName(uri)
                    val fileSize = getFileSize(uri)

                    // Check if file already in queue
                    if (transferQueue.any { it.fileUri == uri.toString() }) {
                        Log.d("MainActivity", "File $filename already in queue, skipping")
                        continue
                    }

                    val queuedTransfer = QueuedTransfer(
                        filename = filename,
                        fileUri = uri.toString(),
                        fileSize = fileSize,
                        priority = 1, // Normal priority
                        status = TransferStatus.PENDING
                    )

                    transferQueue.add(queuedTransfer)
                    addedCount++
                    Log.d("MainActivity", "Added file to queue: $filename ($fileSize bytes)")
                }

                if (addedCount > 0) {
                    // Update UI immediately
                    runOnUiThread {
                        updateQueueDisplay()
                        updateQueueStats()
                        updateEmptyState()

                        Toast.makeText(this@MainActivity, "Added $addedCount file(s) to queue", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "No new files added", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e("MainActivity", "Error handling file selection", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error adding files: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startTransferService() {
        val serviceIntent = Intent(this, TransferService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun bindTransferService() {
        val serviceIntent = Intent(this, TransferService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun observeServiceState() {
        transferService?.let { service ->
            lifecycleScope.launch {
                // Observe queue state
                service.queueState.collect { serviceQueue ->
                    runOnUiThread {
                        updateQueueFromService(serviceQueue)
                    }
                }
            }

            lifecycleScope.launch {
                // Observe transfer progress
                service.transferProgress.collect { progressMap ->
                    runOnUiThread {
                        updateProgressFromService(progressMap)
                    }
                }
            }
        }
    }

    private fun updateQueueFromService(serviceQueue: List<QueuedTransfer>) {
        // Merge service queue with local queue, avoiding duplicates
        for (serviceTransfer in serviceQueue) {
            val existingIndex = transferQueue.indexOfFirst { it.id == serviceTransfer.id }
            if (existingIndex >= 0) {
                // Update existing transfer
                transferQueue[existingIndex] = serviceTransfer
            } else {
                // Add new transfer from service
                transferQueue.add(serviceTransfer)
            }
        }

        updateQueueDisplay()
        updateQueueStats()
        updateEmptyState()
    }

    private fun updateProgressFromService(progressMap: Map<String, TransferProgress>) {
        for ((transferId, progress) in progressMap) {
            queueAdapter.updateProgress(transferId, progress)
        }
        updateGlobalProgress(progressMap)
    }

    private fun updateGlobalProgress(progressMap: Map<String, TransferProgress>) {
        if (progressMap.isEmpty()) {
            globalProgressBar.progress = 0
            return
        }

        val totalBytes = transferQueue.sumOf { it.fileSize }
        val transferredBytes = progressMap.values.sumOf { it.transferredBytes }

        val globalProgress = if (totalBytes > 0) {
            ((transferredBytes * 100) / totalBytes).toInt()
        } else 0

        globalProgressBar.progress = globalProgress

        // Update status
        val activeTransfers = progressMap.size
        if (activeTransfers > 0) {
            val avgSpeed = if (progressMap.isNotEmpty()) {
                progressMap.values.map { it.speedBps }.average().toLong()
            } else 0
            statusText.text = "Transferring $activeTransfers file(s) • ${formatSpeed(avgSpeed)}"
        } else {
            statusText.text = "Ready to transfer files"
        }
    }

//    private fun startQueuedTransfers() {
//        transferService?.let { service ->
//            val pendingTransfers = transferQueue.filter { it.status == TransferStatus.PENDING }
//            for (transfer in pendingTransfers.take(settings.maxConcurrentTransfers)) {
//                service.queueTransfer(transfer)
//            }
//
//            // Update UI
//            statusText.text = "Starting transfers..."
//            globalProgressBar.visibility = ProgressBar.VISIBLE
//            startTransferButton.isEnabled = false
//        } ?: run {
//            Toast.makeText(this, "Transfer service not available", Toast.LENGTH_SHORT).show()
//        }
//    }
    // Add this method to MainActivity.kt to fix the transfer starting issue

    private fun startQueuedTransfers() {

        Log.d("MainActivity", "=== START TRANSFER BUTTON CLICKED ===")
        Log.d("MainActivity", "Service bound: $serviceBound")
        Log.d("MainActivity", "Transfer service: $transferService")
        Log.d("MainActivity", "Queue size: ${transferQueue.size}")
        Log.d("MainActivity", "Pending transfers: ${transferQueue.count { it.status == TransferStatus.PENDING }}")
        if (!serviceBound || transferService == null) {
            Log.w("MainActivity", "Service not bound, cannot start transfers")
            Toast.makeText(this, "Transfer service not ready. Please wait...", Toast.LENGTH_SHORT).show()
            return
        }


        val service = transferService!!
        val pendingTransfers = transferQueue.filter { it.status == TransferStatus.PENDING }

        if (pendingTransfers.isEmpty()) {
            Toast.makeText(this, "No pending transfers to start", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d("MainActivity", "Starting ${pendingTransfers.size} pending transfers")

        // Update UI immediately
        statusText.text = "Starting transfers..."
        globalProgressBar.visibility = ProgressBar.VISIBLE
        startTransferButton.isEnabled = false

        // Queue each transfer with the service
        val maxConcurrent = settings.maxConcurrentTransfers
        val transfersToStart = pendingTransfers.take(maxConcurrent)

        for (transfer in transfersToStart) {
            Log.d("MainActivity", "Queuing transfer: ${transfer.filename}")

            // Update transfer status to indicate it's being processed
            updateTransferStatus(transfer.id, TransferStatus.IN_PROGRESS)
            Log.d("MainActivity", "About to queue transfer: ${transfer.filename} (ID: ${transfer.id})")
            // Queue with service
            service.queueTransfer(transfer)
        }

        // Update UI
        statusText.text = "Starting transfers..."
        globalProgressBar.visibility = ProgressBar.VISIBLE
        startTransferButton.isEnabled = false
        // Show feedback
        Toast.makeText(this, "Started ${transfersToStart.size} transfer(s)", Toast.LENGTH_SHORT).show()

        // Re-enable button after a delay
        startTransferButton.postDelayed({
            startTransferButton.isEnabled = transferQueue.any { it.status == TransferStatus.PENDING }
        }, 2000)
    }

    private fun pauseTransfer(transfer: QueuedTransfer) {
        transferService?.pauseTransfer(transfer.id)
        updateTransferStatus(transfer.id, TransferStatus.PAUSED)
    }

    private fun resumeTransfer(transfer: QueuedTransfer) {
        transferService?.resumeTransfer(transfer.id)
        updateTransferStatus(transfer.id, TransferStatus.IN_PROGRESS)
    }

    private fun cancelTransfer(transfer: QueuedTransfer) {
        AlertDialog.Builder(this)
            .setTitle("Cancel Transfer")
            .setMessage("Are you sure you want to cancel the transfer of ${transfer.filename}?")
            .setPositiveButton("Cancel Transfer") { _, _ ->
                transferService?.cancelTransfer(transfer.id)
                transferQueue.removeAll { it.id == transfer.id }
                updateQueueDisplay()
                updateQueueStats()
                updateEmptyState()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun changePriority(transfer: QueuedTransfer, newPriority: Int) {
        val index = transferQueue.indexOfFirst { it.id == transfer.id }
        if (index != -1) {
            transferQueue[index] = transfer.copy(priority = newPriority)
            // Re-sort queue by priority
            transferQueue.sortByDescending { it.priority }
            updateQueueDisplay()
        }
    }

    private fun updateTransferStatus(transferId: String, status: TransferStatus) {
        val index = transferQueue.indexOfFirst { it.id == transferId }
        if (index != -1) {
            transferQueue[index] = transferQueue[index].copy(status = status)
            queueAdapter.notifyItemChanged(index)
            updateQueueStats()
        }
    }

    private fun updateQueueDisplay() {
        queueAdapter.submitList(transferQueue.toList())
    }

    private fun updateQueueStats() {
        val totalFiles = transferQueue.size
        val totalSize = transferQueue.sumOf { it.fileSize }
        val pendingFiles = transferQueue.count { it.status == TransferStatus.PENDING }
        val activeFiles = transferQueue.count { it.status == TransferStatus.IN_PROGRESS }
        val completedFiles = transferQueue.count { it.status == TransferStatus.COMPLETED }

        // Update count text
        queueCountText.text = "$totalFiles files"

        // Enable Start Transfer button if there are pending files and service is bound
        startTransferButton.isEnabled = pendingFiles > 0 && serviceBound

        // Update stats text
        queueStatsText.text = "Queue: $totalFiles files (${formatFileSize(totalSize)}) • " +
                "Active: $activeFiles • Pending: $pendingFiles • Completed: $completedFiles"

        Log.d("MainActivity", "Queue stats updated: Total=$totalFiles, Pending=$pendingFiles, Active=$activeFiles")
    }

    private fun updateEmptyState() {
        if (transferQueue.isEmpty()) {
            emptyStateLayout.visibility = View.VISIBLE
            queueRecyclerView.visibility = View.GONE
        } else {
            emptyStateLayout.visibility = View.GONE
            queueRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun showTransferDetails(transfer: QueuedTransfer) {
        val message = """
            Filename: ${transfer.filename}
            Size: ${formatFileSize(transfer.fileSize)}
            Status: ${transfer.status.name.lowercase().replaceFirstChar { it.uppercase() }}
            Priority: ${getPriorityName(transfer.priority)}
            Added: ${formatTime(transfer.addedTime)}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Transfer Details")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showClearQueueDialog() {
        if (transferQueue.isEmpty()) {
            Toast.makeText(this, "Queue is already empty", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Clear Queue")
            .setMessage("This will remove all transfers from the queue. Active transfers will be cancelled. Are you sure?")
            .setPositiveButton("Clear All") { _, _ ->
                // Cancel all active transfers
                for (transfer in transferQueue) {
                    if (transfer.status == TransferStatus.IN_PROGRESS) {
                        transferService?.cancelTransfer(transfer.id)
                    }
                }

                // Clear queue
                transferQueue.clear()
                updateQueueDisplay()
                updateQueueStats()
                updateEmptyState()

                Toast.makeText(this, "Queue cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkPendingTransfers() {
        lifecycleScope.launch {
            try {
                Log.d("MainActivity", "Checking for pending transfers from database...")

                val incompleteStatuses = listOf(
                    TransferStatus.PENDING,
                    TransferStatus.IN_PROGRESS,
                    TransferStatus.PAUSED
                )

                transferDao.getTransfersByStatus(incompleteStatuses).collect { transferStates ->
                    var restoredCount = 0

                    runOnUiThread {
                        for (state in transferStates) {
                            // Check if transfer is already in queue
                            if (transferQueue.none { it.id == state.transferId }) {
                                val queuedTransfer = QueuedTransfer(
                                    id = state.transferId,
                                    filename = state.filename,
                                    fileUri = state.fileUri,
                                    fileSize = state.fileSize,
                                    priority = 1,
                                    status = state.status,
                                    addedTime = state.timestamp
                                )

                                transferQueue.add(queuedTransfer)
                                restoredCount++
                            }
                        }

                        if (restoredCount > 0) {
                            updateQueueDisplay()
                            updateQueueStats()
                            updateEmptyState()

                            Toast.makeText(
                                this@MainActivity,
                                "Restored $restoredCount pending transfer(s)",
                                Toast.LENGTH_SHORT
                            ).show()

                            Log.d("MainActivity", "Restored $restoredCount pending transfers")
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("MainActivity", "Error checking pending transfers", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Error loading pending transfers: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Helper methods
    private fun getFileName(uri: Uri): String {
        var fileName = "unknown_file"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) {
                        fileName = name
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting file name for $uri", e)
        }
        return fileName
    }

    private fun getFileSize(uri: Uri): Long {
        var fileSize = 0L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst() && sizeIndex >= 0) {
                    fileSize = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting file size for $uri", e)
        }
        return fileSize
    }

    private fun formatFileSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return String.format("%.1f %s", size, units[unitIndex])
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        val mbps = (bytesPerSecond * 8.0) / (1024 * 1024)
        return String.format("%.1f Mbps", mbps)
    }

    private fun formatTime(timestamp: Long): String {
        val date = java.util.Date(timestamp)
        val formatter = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
        return formatter.format(date)
    }

    private fun getPriorityName(priority: Int): String {
        return when (priority) {
            0 -> "Low"
            1 -> "Normal"
            2 -> "High"
            3 -> "Urgent"
            else -> "Normal"
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        // Storage permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        // Network permissions
        permissions.add(Manifest.permission.INTERNET)
        permissions.add(Manifest.permission.ACCESS_NETWORK_STATE)

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Foreground service permission (Android 14+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 100) {
            val deniedPermissions = permissions.filterIndexed { index, _ ->
                grantResults[index] != PackageManager.PERMISSION_GRANTED
            }

            if (deniedPermissions.isNotEmpty()) {
                Toast.makeText(this, "Some permissions were denied. App may not work properly.", Toast.LENGTH_LONG).show()
            }
        }
    }
}