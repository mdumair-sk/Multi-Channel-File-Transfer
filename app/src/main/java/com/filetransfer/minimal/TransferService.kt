package com.filetransfer.minimal

import EnhancedStreamingTransferEngine
import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TransferService : Service() {

    private val binder = TransferBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val transferEngine = EnhancedStreamingTransferEngine()
    private val settings by lazy { TransferSettings.getInstance(this) }

    // Database access
    private val database by lazy { TransferDatabase.getDatabase(this) }
    private val transferDao by lazy { database.transferStateDao() }

    // Transfer state management
    private val activeTransfers = ConcurrentHashMap<String, Job>()
    private val transferQueue = mutableListOf<QueuedTransfer>()
    private val transferStates = ConcurrentHashMap<String, TransferState>()

    // State flows for UI updates
    private val _queueState = MutableStateFlow<List<QueuedTransfer>>(emptyList())
    val queueState: StateFlow<List<QueuedTransfer>> = _queueState

    private val _transferProgress = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    val transferProgress: StateFlow<Map<String, TransferProgress>> = _transferProgress

    // Network monitoring
    private lateinit var connectivityManager: ConnectivityManager
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = null

    // Notification
    private val notificationId = AtomicInteger(1000)
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val CHANNEL_ID = "transfer_service_channel"
        const val ACTION_START_TRANSFER = "start_transfer"
        const val ACTION_PAUSE_TRANSFER = "pause_transfer"
        const val ACTION_RESUME_TRANSFER = "resume_transfer"
        const val ACTION_CANCEL_TRANSFER = "cancel_transfer"
        const val EXTRA_TRANSFER_ID = "transfer_id"
        const val EXTRA_QUEUED_TRANSFER = "queued_transfer"
    }

    inner class TransferBinder : Binder() {
        fun getService(): TransferService = this@TransferService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("TransferService", "Service created")

        // Initialize notification channel
        createNotificationChannel()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Initialize connectivity monitoring
        setupNetworkMonitoring()

        // Start foreground service
        startForeground(999, createServiceNotification())

        // Load pending transfers from database
        loadPendingTransfers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { handleIntent(it) }
        return START_STICKY // Restart if killed by system
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.d("TransferService", "Service destroyed")

        // Save current transfer states
        savePendingTransfers()

        // Cancel all active transfers
        activeTransfers.values.forEach { it.cancel() }

        // Cleanup network monitoring
        networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }

        // Cancel service scope
        scope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File Transfer Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background file transfers"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createServiceNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("File Transfer Service")
            .setContentText("Ready for transfers")
            .setSmallIcon(android.R.drawable.stat_sys_upload) // Using system icon as fallback
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createTransferNotification(transfer: QueuedTransfer, progress: TransferProgress): Notification {
        val cancelIntent = Intent(this, TransferService::class.java).apply {
            action = ACTION_CANCEL_TRANSFER
            putExtra(EXTRA_TRANSFER_ID, transfer.id)
        }
        val cancelPendingIntent = PendingIntent.getService(this, 0, cancelIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Transferring ${transfer.filename}")
            .setContentText("${progress.percentComplete}% • ${formatSpeed(progress.speedBps)}")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, progress.percentComplete, false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun setupNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                currentNetwork = network
                Log.d("TransferService", "Network available: $network")

                // Resume paused transfers if auto-resume is enabled
                if (settings.autoResume) {
                    resumePausedTransfers()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Log.d("TransferService", "Network lost: $network")

                // Pause active transfers to prevent failures
                pauseActiveTransfers()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, networkCapabilities)

                val isWifi = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val isCellular = networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

                Log.d("TransferService", "Network capabilities changed - WiFi: $isWifi, Cellular: $isCellular")

                // Adjust transfer behavior based on network type
                adjustTransferForNetwork(isWifi, isCellular)
            }
        }

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            ACTION_START_TRANSFER -> {
                val transfer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_QUEUED_TRANSFER, QueuedTransfer::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_QUEUED_TRANSFER)
                }
                transfer?.let { startTransfer(it) }
            }
            ACTION_PAUSE_TRANSFER -> {
                val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID)
                transferId?.let { pauseTransfer(it) }
            }
            ACTION_RESUME_TRANSFER -> {
                val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID)
                transferId?.let { resumeTransfer(it) }
            }
            ACTION_CANCEL_TRANSFER -> {
                val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID)
                transferId?.let { cancelTransfer(it) }
            }
        }
    }

    // Public methods for service interaction

    fun queueTransfer(transfer: QueuedTransfer) {
        Log.d("TransferService", "=== QUEUE TRANSFER CALLED ===")
        Log.d("TransferService", "Transfer: ${transfer.filename} (ID: ${transfer.id})")
        Log.d("TransferService", "Current active transfers: ${activeTransfers.size}")
        Log.d("TransferService", "Max concurrent: ${settings.maxConcurrentTransfers}")
        Log.d("TransferService", "Transfer queue size before: ${transferQueue.size}")

        // Add to internal queue
        transferQueue.add(transfer)
        updateQueueState()

        // Save to database
        scope.launch {
            saveTransferToDatabase(transfer)
        }

        // CRITICAL FIX: Actually start the transfer immediately if under concurrent limit
        if (activeTransfers.size < settings.maxConcurrentTransfers) {
            Log.d("TransferService", "Starting transfer immediately: ${transfer.filename}")
            startTransfer(transfer)
        } else {
            Log.d("TransferService", "Transfer queued, max concurrent limit reached: ${activeTransfers.size}/${settings.maxConcurrentTransfers}")
        }
    }

//    fun startTransfer(transfer: QueuedTransfer) {
//        if (activeTransfers.containsKey(transfer.id)) {
//            Log.w("TransferService", "Transfer ${transfer.id} already active")
//            return
//        }
//
//        val job = scope.launch {
//            try {
//                Log.d("TransferService", "Starting transfer: ${transfer.filename}")
//
//                // Update transfer state
//                val initialState = TransferState(
//                    transferId = transfer.id,
//                    filename = transfer.filename,
//                    fileUri = transfer.fileUri,
//                    fileSize = transfer.fileSize,
//                    transferredBytes = 0,
//                    totalChunks = 0, // Will be updated when transfer starts
//                    completedChunks = emptySet(),
//                    chunkSize = settings.chunkSize,
//                    status = TransferStatus.IN_PROGRESS,
//                    timestamp = System.currentTimeMillis()
//                )
//                updateTransferState(initialState)
//
//                // Get file input stream
//                val inputStream = contentResolver.openInputStream(transfer.fileUri.toUri())
//                    ?: throw Exception("Cannot open file: ${transfer.filename}")
//
//                // Check for existing transfer state (resume support)
//                val existingState = transferDao.getTransferById(transfer.id)
//
//                // Create transfer configuration
//                val config = settings.getTransferConfig()
//
//                // Start transfer with progress callbacks
//                transferEngine.startTransfer(
//                    config = config,
//                    fileName = transfer.filename,
//                    fileSize = transfer.fileSize,
//                    inputStream = inputStream,
//                    progressCallback = { progress ->
//                        updateTransferProgress(transfer.id, progress)
//
//                        // Update notification
//                        if (settings.notificationEnabled) {
//                            val notification = createTransferNotification(transfer, progress)
//                            notificationManager.notify(transfer.id.hashCode(), notification)
//                        }
//                    },
//                    logCallback = { message ->
//                        Log.d("TransferService", "${transfer.filename}: $message")
//                    },
//                    resumeState = existingState
//                )
//
//                // Transfer completed successfully
//                Log.d("TransferService", "Transfer completed: ${transfer.filename}")
//                val completedState = transferStates[transfer.id]?.copy(
//                    status = TransferStatus.COMPLETED,
//                    transferredBytes = transfer.fileSize
//                ) ?: TransferState(
//                    transferId = transfer.id,
//                    filename = transfer.filename,
//                    fileUri = transfer.fileUri,
//                    fileSize = transfer.fileSize,
//                    transferredBytes = transfer.fileSize,
//                    totalChunks = 0,
//                    completedChunks = emptySet(),
//                    chunkSize = settings.chunkSize,
//                    status = TransferStatus.COMPLETED,
//                    timestamp = System.currentTimeMillis()
//                )
//
//                updateTransferState(completedState)
//
//                // Remove from active transfers
//                activeTransfers.remove(transfer.id)
//
//                // Cancel notification
//                notificationManager.cancel(transfer.id.hashCode())
//
//                // Process next queued transfer
//                processNextTransfer()
//
//            } catch (e: Exception) {
//                Log.e("TransferService", "Transfer failed: ${transfer.filename}", e)
//                val failedState = transferStates[transfer.id]?.copy(
//                    status = TransferStatus.FAILED
//                ) ?: TransferState(
//                    transferId = transfer.id,
//                    filename = transfer.filename,
//                    fileUri = transfer.fileUri,
//                    fileSize = transfer.fileSize,
//                    transferredBytes = 0,
//                    totalChunks = 0,
//                    completedChunks = emptySet(),
//                    chunkSize = settings.chunkSize,
//                    status = TransferStatus.FAILED,
//                    timestamp = System.currentTimeMillis()
//                )
//
//                updateTransferState(failedState)
//                activeTransfers.remove(transfer.id)
//
//                // Cancel notification
//                notificationManager.cancel(transfer.id.hashCode())
//
//                // Process next transfer
//                processNextTransfer()
//            }
//        }
//
//        activeTransfers[transfer.id] = job
//    }

    // 2. Fix startTransfer to handle the transfer properly
    fun startTransfer(transfer: QueuedTransfer) {
        Log.d("TransferService", "=== START TRANSFER CALLED ===")
        Log.d("TransferService", "Transfer: ${transfer.filename} (ID: ${transfer.id})")
        Log.d("TransferService", "Already active: ${activeTransfers.containsKey(transfer.id)}")

        if (activeTransfers.containsKey(transfer.id)) {
            Log.w("TransferService", "Transfer ${transfer.id} already active")
            return
        }
        Log.d("TransferService", "About to start coroutine for transfer")
        Log.d("TransferService", "Starting transfer: ${transfer.filename}")

        val job = scope.launch {
            Log.d("TransferService", "About to start coroutine for transfer")
            try {
                // Update transfer state to IN_PROGRESS
                val initialState = TransferState(
                    transferId = transfer.id,
                    filename = transfer.filename,
                    fileUri = transfer.fileUri,
                    fileSize = transfer.fileSize,
                    transferredBytes = 0,
                    totalChunks = 0, // Will be calculated by transfer engine
                    completedChunks = emptySet(),
                    chunkSize = settings.chunkSize,
                    status = TransferStatus.IN_PROGRESS,
                    timestamp = System.currentTimeMillis()
                )
                updateTransferState(initialState)

                // Get file input stream - CRITICAL: Handle URI properly
                val inputStream = try {
                    contentResolver.openInputStream(transfer.fileUri.toUri())
                } catch (e: Exception) {
                    Log.e("TransferService", "Failed to open file: ${transfer.fileUri}", e)
                    throw Exception("Cannot open file: ${transfer.filename} - ${e.message}")
                }

                if (inputStream == null) {
                    throw Exception("Cannot open file: ${transfer.filename} - InputStream is null")
                }

                Log.d("TransferService", "File opened successfully: ${transfer.filename}")

                // Check for existing transfer state (resume support)
                val existingState = transferDao.getTransferById(transfer.id)

                // Create transfer configuration
                val config = settings.getTransferConfig()

                Log.d("TransferService", "Starting transfer engine for: ${transfer.filename}")
                Log.d("TransferService", "Config - USB: ${config.usbEnabled}:${config.usbHost}:${config.usbPort}, WiFi: ${config.wifiEnabled}:${config.wifiHost}:${config.wifiPort}")

                // Start transfer with progress callbacks
                transferEngine.startTransfer(
                    config = config,
                    fileName = transfer.filename,
                    fileSize = transfer.fileSize,
                    inputStream = inputStream,
                    progressCallback = { progress ->
                        Log.v("TransferService", "Progress for ${transfer.filename}: ${progress.percentComplete}%")
                        updateTransferProgress(transfer.id, progress)

                        // Update notification
                        if (settings.notificationEnabled) {
                            val notification = createTransferNotification(transfer, progress)
                            notificationManager.notify(transfer.id.hashCode(), notification)
                        }
                    },
                    logCallback = { message ->
                        Log.d("TransferService", "${transfer.filename}: $message")
                    },
                    resumeState = existingState
                )

                // Transfer completed successfully
                Log.d("TransferService", "Transfer completed successfully: ${transfer.filename}")
                val completedState = transferStates[transfer.id]?.copy(
                    status = TransferStatus.COMPLETED,
                    transferredBytes = transfer.fileSize
                ) ?: initialState.copy(
                    status = TransferStatus.COMPLETED,
                    transferredBytes = transfer.fileSize
                )

                updateTransferState(completedState)

                // Remove from active transfers
                activeTransfers.remove(transfer.id)

                // Cancel notification
                notificationManager.cancel(transfer.id.hashCode())

                // Process next queued transfer
                processNextTransfer()

            } catch (e: Exception) {
                Log.e("TransferService", "Transfer failed: ${transfer.filename}", e)

                val failedState = transferStates[transfer.id]?.copy(
                    status = TransferStatus.FAILED
                ) ?: TransferState(
                    transferId = transfer.id,
                    filename = transfer.filename,
                    fileUri = transfer.fileUri,
                    fileSize = transfer.fileSize,
                    transferredBytes = 0,
                    totalChunks = 0,
                    completedChunks = emptySet(),
                    chunkSize = settings.chunkSize,
                    status = TransferStatus.FAILED,
                    timestamp = System.currentTimeMillis()
                )

                updateTransferState(failedState)
                activeTransfers.remove(transfer.id)

                // Cancel notification
                notificationManager.cancel(transfer.id.hashCode())

                // Show error notification
                val errorNotification = NotificationCompat.Builder(this@TransferService, CHANNEL_ID)
                    .setContentTitle("Transfer Failed")
                    .setContentText("${transfer.filename}: ${e.message}")
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setAutoCancel(true)
                    .build()

                notificationManager.notify(transfer.id.hashCode() + 1000, errorNotification)

                // Process next transfer
                processNextTransfer()
            }
        }

        activeTransfers[transfer.id] = job
    }
    fun pauseTransfer(transferId: String) {
        activeTransfers[transferId]?.let { job ->
            job.cancel()
            activeTransfers.remove(transferId)

            // Update state to paused
            transferStates[transferId]?.let { state ->
                updateTransferState(state.copy(status = TransferStatus.PAUSED))
            }

            // Cancel notification
            notificationManager.cancel(transferId.hashCode())

            Log.d("TransferService", "Transfer paused: $transferId")
        }
    }

    fun resumeTransfer(transferId: String) {
        transferStates[transferId]?.let { state ->
            if (state.status == TransferStatus.PAUSED) {
                val queuedTransfer = QueuedTransfer(
                    id = state.transferId,
                    filename = state.filename,
                    fileUri = state.fileUri,
                    fileSize = state.fileSize
                )
                startTransfer(queuedTransfer)
                Log.d("TransferService", "Transfer resumed: $transferId")
            }
        }
    }

    fun cancelTransfer(transferId: String) {
        // Cancel active job
        activeTransfers[transferId]?.let { job ->
            job.cancel()
            activeTransfers.remove(transferId)
        }

        // Remove from queue
        transferQueue.removeAll { it.id == transferId }

        // Update state to cancelled
        transferStates[transferId]?.let { state ->
            updateTransferState(state.copy(status = TransferStatus.CANCELLED))
        }

        // Cancel notification
        notificationManager.cancel(transferId.hashCode())

        // Remove from database
        scope.launch {
            transferStates[transferId]?.let { state ->
                transferDao.deleteTransfer(state)
            }
        }

        Log.d("TransferService", "Transfer cancelled: $transferId")
        updateQueueState()

        // Process next transfer
        processNextTransfer()
    }

//    private fun processNextTransfer() {
//        if (activeTransfers.size >= settings.maxConcurrentTransfers) return
//
//        val nextTransfer = transferQueue.firstOrNull { it.status == TransferStatus.PENDING }
//        nextTransfer?.let {
//            transferQueue.remove(it)
//            startTransfer(it)
//            updateQueueState()
//        }
//    }
private fun processNextTransfer() {
    if (activeTransfers.size >= settings.maxConcurrentTransfers) {
        Log.d("TransferService", "Cannot process next transfer, at max concurrent limit: ${activeTransfers.size}/${settings.maxConcurrentTransfers}")
        return
    }

    val nextTransfer = transferQueue.firstOrNull { it.status == TransferStatus.PENDING }
    if (nextTransfer != null) {
        Log.d("TransferService", "Processing next transfer: ${nextTransfer.filename}")
        transferQueue.remove(nextTransfer)
        startTransfer(nextTransfer)
        updateQueueState()
    } else {
        Log.d("TransferService", "No pending transfers to process")
    }
}

    private fun updateTransferState(state: TransferState) {
        transferStates[state.transferId] = state

        // Save to database
        scope.launch {
            try {
                transferDao.insertTransfer(state)
            } catch (e: Exception) {
                Log.e("TransferService", "Failed to save transfer state", e)
            }
        }
    }

    private fun updateTransferProgress(transferId: String, progress: TransferProgress) {
        val currentProgress = _transferProgress.value.toMutableMap()
        currentProgress[transferId] = progress
        _transferProgress.value = currentProgress

        // Update transfer state with progress
        transferStates[transferId]?.let { state ->
            val updatedState = state.copy(
                transferredBytes = progress.transferredBytes,
                lastResumeTime = System.currentTimeMillis()
            )
            transferStates[transferId] = updatedState

            // Save to database periodically (every 1MB transferred)
            if (progress.transferredBytes % (1024 * 1024) == 0L) {
                scope.launch {
                    try {
                        transferDao.updateTransfer(updatedState)
                    } catch (e: Exception) {
                        Log.e("TransferService", "Failed to update transfer progress", e)
                    }
                }
            }
        }
    }

    private fun updateQueueState() {
        _queueState.value = transferQueue.toList()
    }

    private fun pauseActiveTransfers() {
        activeTransfers.keys.forEach { transferId ->
            pauseTransfer(transferId)
        }
    }

    private fun resumePausedTransfers() {
        transferStates.values
            .filter { it.status == TransferStatus.PAUSED }
            .forEach { state ->
                resumeTransfer(state.transferId)
            }
    }

    private fun adjustTransferForNetwork(isWifi: Boolean, isCellular: Boolean) {
        // Adjust settings based on network type
        // For example, reduce concurrent transfers on cellular
        if (isCellular && !isWifi) {
            // Limit concurrent transfers on cellular
            while (activeTransfers.size > 1) {
                val firstTransfer = activeTransfers.keys.first()
                pauseTransfer(firstTransfer)
            }
        }
    }

    // IMPLEMENTED: Load pending transfers from database
    private fun loadPendingTransfers() {
        scope.launch {
            try {
                Log.d("TransferService", "Loading pending transfers from database")

                // Get all incomplete transfers
                val incompleteStatuses = listOf(
                    TransferStatus.PENDING,
                    TransferStatus.IN_PROGRESS,
                    TransferStatus.PAUSED
                )

                transferDao.getTransfersByStatus(incompleteStatuses).collect { transferStates ->
                    for (state in transferStates) {
                        // Restore to local cache
                        this@TransferService.transferStates[state.transferId] = state

                        // Add to queue if not already there
                        if (transferQueue.none { it.id == state.transferId }) {
                            val queuedTransfer = QueuedTransfer(
                                id = state.transferId,
                                filename = state.filename,
                                fileUri = state.fileUri,
                                fileSize = state.fileSize,
                                status = state.status,
                                addedTime = state.timestamp
                            )
                            transferQueue.add(queuedTransfer)
                        }
                    }

                    updateQueueState()

                    // Auto-resume pending transfers if enabled
                    if (settings.autoResume) {
                        val pendingTransfers = transferStates.filter { it.status == TransferStatus.PENDING }
                        for (state in pendingTransfers.take(settings.maxConcurrentTransfers)) {
                            resumeTransfer(state.transferId)
                        }
                    }

                    Log.d("TransferService", "Loaded ${transferStates.size} pending transfers")
                }

                // Clean up old completed/cancelled transfers
                val cutoffTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L) // 7 days
                transferDao.deleteOldTransfers(cutoffTime)

            } catch (e: Exception) {
                Log.e("TransferService", "Failed to load pending transfers", e)
            }
        }
    }

    // IMPLEMENTED: Save pending transfers to database
    private fun savePendingTransfers() {
        runBlocking(Dispatchers.IO) {
            try {
                Log.d("TransferService", "Saving pending transfers to database")

                for (state in transferStates.values) {
                    try {
                        transferDao.insertTransfer(state)
                    } catch (e: Exception) {
                        Log.e("TransferService", "Failed to save transfer ${state.transferId}", e)
                    }
                }

                Log.d("TransferService", "Saved ${transferStates.size} transfer states")

            } catch (e: Exception) {
                Log.e("TransferService", "Failed to save pending transfers", e)
            }
        }
    }

    // IMPLEMENTED: Save individual transfer to database
    private suspend fun saveTransferToDatabase(transfer: QueuedTransfer) {
        try {
            val transferState = TransferState(
                transferId = transfer.id,
                filename = transfer.filename,
                fileUri = transfer.fileUri,
                fileSize = transfer.fileSize,
                transferredBytes = 0,
                totalChunks = 0,
                completedChunks = emptySet(),
                chunkSize = settings.chunkSize,
                status = transfer.status,
                timestamp = transfer.addedTime
            )

            transferDao.insertTransfer(transferState)
            transferStates[transfer.id] = transferState

            Log.d("TransferService", "Saved transfer to database: ${transfer.filename}")

        } catch (e: Exception) {
            Log.e("TransferService", "Failed to save transfer to database", e)
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String {
        val mbps = (bytesPerSecond * 8.0) / (1024 * 1024)
        return String.format("%.1f Mbps", mbps)
    }
}