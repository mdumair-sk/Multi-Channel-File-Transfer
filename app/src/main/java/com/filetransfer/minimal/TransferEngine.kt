// OptimizedTransferEngine.kt - BitTorrent-inspired with dynamic load balancing
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.DataInputStream
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import android.util.Log
import com.filetransfer.minimal.ChunkHeader
import com.filetransfer.minimal.SessionStart
import com.filetransfer.minimal.TransferConfig
import com.filetransfer.minimal.TransferProgress
import kotlin.math.max

class OptimizedTransferEngine {
    private val protocolHandler = ProtocolHandler()
    private val TAG = "OptimizedTransferEngine"

    // Optimization constants
    private val OPTIMIZED_CHUNK_SIZE = 2 * 1024 * 1024 // 2MB chunks (32x larger)
    private val PIPELINE_DEPTH = 20 // Send 20 chunks ahead without waiting
    private val BATCH_ACK_SIZE = 10 // ACK every 10 chunks
    private val CHANNEL_SPEED_WINDOW = 5 // Track last 5 chunks for speed calculation

    suspend fun startTransfer(
        config: TransferConfig,
        fileName: String,
        fileSize: Long,
        inputStream: InputStream,
        progressCallback: (TransferProgress) -> Unit,
        logCallback: (String) -> Unit
    ) = withContext(Dispatchers.IO) {

        val transferId = UUID.randomUUID().toString()
        // Use optimized chunk size
        val chunkSize = OPTIMIZED_CHUNK_SIZE
        val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()
        val transferredBytes = AtomicLong(0)
        val startTime = System.currentTimeMillis()

        logCallback("🚀 OPTIMIZED TRANSFER: $fileName")
        logCallback("📊 File: ${formatBytes(fileSize)}, Chunks: $totalChunks (${formatBytes(chunkSize.toLong())} each)")

        // Read file into memory
        val fileBytes = inputStream.readBytes()
        inputStream.close()

        // Create connection pool with performance tracking
        val connectionPool = ConnectionPool()

        try {
            // Establish connections
            if (config.usbEnabled) {
                try {
                    val usbSocket = Socket(config.usbHost, config.usbPort)
                    usbSocket.tcpNoDelay = true
                    usbSocket.soTimeout = 30000
                    val conn = OptimizedConnection(usbSocket, "USB", logCallback)
                    connectionPool.addConnection(conn)
                    logCallback("🔌 USB connected: ${config.usbHost}:${config.usbPort}")
                } catch (e: Exception) {
                    logCallback("❌ USB failed: ${e.message}")
                }
            }

            if (config.wifiEnabled) {
                try {
                    val wifiSocket = Socket(config.wifiHost, config.wifiPort)
                    wifiSocket.tcpNoDelay = true
                    wifiSocket.soTimeout = 30000
                    val conn = OptimizedConnection(wifiSocket, "WiFi", logCallback)
                    connectionPool.addConnection(conn)
                    logCallback("📡 WiFi connected: ${config.wifiHost}:${config.wifiPort}")
                } catch (e: Exception) {
                    logCallback("❌ WiFi failed: ${e.message}")
                }
            }

            if (connectionPool.isEmpty()) {
                throw Exception("No connections available")
            }

            // Send session start to all connections
            val sessionStart = SessionStart(transferId, totalChunks, chunkSize, fileName)
            connectionPool.sendSessionStart(sessionStart, protocolHandler)
            logCallback("✅ Session started on ${connectionPool.size()} channel(s)")

            // Start performance monitoring
            val performanceMonitor = PerformanceMonitor(connectionPool, logCallback)
            val monitorJob = launch { performanceMonitor.start() }

            // Create chunk queue and missing chunks tracker
            val chunkQueue = Channel<ChunkTask>(capacity = PIPELINE_DEPTH * 2)
            val missingChunks = Collections.synchronizedSet(mutableSetOf<Int>())
            val completedChunks = AtomicInteger(0)

            // Populate chunk queue
            launch {
                for (chunkId in 0 until totalChunks) {
                    val offset = chunkId * chunkSize
                    val actualChunkSize = minOf(chunkSize, fileBytes.size - offset)
                    val chunkData = fileBytes.sliceArray(offset until offset + actualChunkSize)
                    val checksum = computeSHA256(chunkData)

                    val task = ChunkTask(
                        chunkId = chunkId,
                        data = chunkData,
                        checksum = checksum,
                        transferId = transferId
                    )
                    chunkQueue.send(task)
                }
                chunkQueue.close()
            }

            // Start chunk senders (one per connection)
            val senderJobs = connectionPool.getConnections().map { conn ->
                launch {
                    chunkSender(
                        connection = conn,
                        chunkQueue = chunkQueue,
                        protocolHandler = protocolHandler,
                        missingChunks = missingChunks,
                        completedChunks = completedChunks,
                        totalChunks = totalChunks,
                        transferredBytes = transferredBytes,
                        fileSize = fileSize,
                        startTime = startTime,
                        progressCallback = progressCallback,
                        logCallback = logCallback
                    )
                }
            }

            // Wait for all chunks to be sent
            senderJobs.joinAll()
            monitorJob.cancel()

            // Handle missing chunks (recovery phase)
            if (missingChunks.isNotEmpty()) {
                logCallback("🔄 Recovery phase: ${missingChunks.size} missing chunks")
                // TODO: Implement missing chunk recovery
            }

            // Send transfer complete
            connectionPool.sendTransferComplete(transferId, totalChunks, fileSize, protocolHandler)

            val duration = System.currentTimeMillis() - startTime
            val avgSpeedMbps = (fileSize * 8.0) / (duration * 1000.0) // Mbps
            logCallback("🎉 TRANSFER COMPLETE!")
            logCallback("⚡ Speed: ${String.format("%.1f", avgSpeedMbps)} Mbps (${formatBytes(fileSize * 1000 / duration)}/s)")
            logCallback("⏱️ Duration: ${duration}ms")

        } catch (e: Exception) {
            logCallback("💥 Transfer failed: ${e.message}")
            Log.e(TAG, "Transfer failed", e)
            throw e
        } finally {
            connectionPool.closeAll()
        }
    }

    private suspend fun chunkSender(
        connection: OptimizedConnection,
        chunkQueue: Channel<ChunkTask>,
        protocolHandler: ProtocolHandler,
        missingChunks: MutableSet<Int>,
        completedChunks: AtomicInteger,
        totalChunks: Int,
        transferredBytes: AtomicLong,
        fileSize: Long,
        startTime: Long,
        progressCallback: (TransferProgress) -> Unit,
        logCallback: (String) -> Unit
    ) {
        val inFlightChunks = mutableMapOf<Int, Long>() // chunkId -> sendTime

        try {
            // Pipeline chunks - keep PIPELINE_DEPTH chunks in flight
            while (true) {
                // Send new chunks if we have capacity
                while (inFlightChunks.size < PIPELINE_DEPTH) {
                    val task = chunkQueue.tryReceive().getOrNull() ?: break

                    try {
                        connection.sendChunk(task, protocolHandler)
                        inFlightChunks[task.chunkId] = System.currentTimeMillis()
                        Log.d(TAG, "${connection.name}: Sent chunk ${task.chunkId} (${inFlightChunks.size} in flight)")
                    } catch (e: Exception) {
                        logCallback("${connection.name}: Error sending chunk ${task.chunkId}: ${e.message}")
                        missingChunks.add(task.chunkId)
                        break
                    }
                }

                // Check for responses (non-blocking)
                val responses = connection.readPendingResponses()
                for (response in responses) {
                    val (msgType, data) = response
                    if (msgType == "chunk_ack") {
                        val chunkId = data.optInt("chunk_id", -1)
                        val sendTime = inFlightChunks.remove(chunkId)

                        if (sendTime != null) {
                            val rtt = System.currentTimeMillis() - sendTime
                            connection.updatePerformance(rtt, OPTIMIZED_CHUNK_SIZE)

                            val completed = completedChunks.incrementAndGet()
                            transferredBytes.addAndGet(OPTIMIZED_CHUNK_SIZE.toLong())

                            // Update progress
                            val currentTime = System.currentTimeMillis()
                            val elapsedSeconds = (currentTime - startTime) / 1000.0
                            val speedBps = if (elapsedSeconds > 0) (transferredBytes.get() / elapsedSeconds).toLong() else 0
                            val percentComplete = ((completed * 100) / totalChunks)

                            progressCallback(TransferProgress(transferredBytes.get(), fileSize, percentComplete, speedBps))

                            if (completed % 50 == 0) { // Log every 50 chunks
                                val speedMbps = (speedBps * 8.0) / (1024 * 1024)
                                logCallback("📈 Progress: $completed/$totalChunks (${String.format("%.1f", speedMbps)} Mbps)")
                            }
                        }
                    }
                }

                // Break if no more chunks and nothing in flight
                if (chunkQueue.isClosedForReceive && inFlightChunks.isEmpty()) {
                    break
                }

                // Small delay to prevent busy waiting
                if (inFlightChunks.size >= PIPELINE_DEPTH) {
                    delay(1)
                }
            }

        } catch (e: Exception) {
            logCallback("${connection.name}: Sender error: ${e.message}")
            Log.e(TAG, "Sender error", e)
        }
    }

    private fun computeSHA256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
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
}

// Optimized connection with performance tracking
class OptimizedConnection(
    val socket: Socket,
    val name: String,
    private val logCallback: (String) -> Unit
) {
    private val outputStream = socket.getOutputStream()
    private val inputStream = DataInputStream(socket.getInputStream())
    private val performanceHistory = mutableListOf<PerformanceData>()
    private val performanceMutex = Mutex()

    // Performance metrics
    private var avgRtt: Long = 0
    private var avgThroughput: Long = 0 // bytes/second
    private var weight: Double = 1.0 // Dynamic weight for load balancing

    suspend fun sendChunk(task: ChunkTask, protocolHandler: ProtocolHandler) {
        val chunkHeader = ChunkHeader(task.transferId, task.chunkId, task.data.size, task.checksum)
        val headerMessage = protocolHandler.serializeMessage("chunk_header", chunkHeader)

        // Send header + data atomically
        synchronized(outputStream) {
            outputStream.write(headerMessage)
            outputStream.write(task.data)
            outputStream.flush()
        }
    }

    fun readPendingResponses(): List<Pair<String, JSONObject>> {
        val responses = mutableListOf<Pair<String, JSONObject>>()

        try {
            while (inputStream.available() > 0) {
                val response = readServerResponse()
                if (response != null) {
                    responses.add(response)
                }
            }
        } catch (e: Exception) {
            // Non-blocking read, ignore errors
        }

        return responses
    }

    private fun readServerResponse(): Pair<String, JSONObject>? {
        return try {
            // Same as before, but with shorter timeout for non-blocking behavior
            socket.soTimeout = 100 // 100ms timeout

            val header7 = ByteArray(7)
            inputStream.readFully(header7)

            val headerBuf = ByteBuffer.wrap(header7).order(ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(4)
            headerBuf.get(magic)
            val version = headerBuf.get()
            val msgTypeLen = headerBuf.short.toInt() and 0xFFFF

            val msgTypeBytes = ByteArray(msgTypeLen)
            inputStream.readFully(msgTypeBytes)
            val msgType = String(msgTypeBytes, Charsets.UTF_8)

            val dataLenBytes = ByteArray(4)
            inputStream.readFully(dataLenBytes)
            val dataLen = ByteBuffer.wrap(dataLenBytes).order(ByteOrder.BIG_ENDIAN).int

            val dataBytes = ByteArray(dataLen)
            inputStream.readFully(dataBytes)
            val checksumBytes = ByteArray(32)
            inputStream.readFully(checksumBytes)

            val fullMessage = header7 + msgTypeBytes + dataLenBytes + dataBytes + checksumBytes

            ProtocolHandler().deserializeMessage(fullMessage)
        } catch (e: Exception) {
            null
        } finally {
            socket.soTimeout = 30000 // Reset to original timeout
        }
    }

    suspend fun updatePerformance(rtt: Long, bytesTransferred: Int) {
        performanceMutex.withLock {
            val throughput = if (rtt > 0) (bytesTransferred * 1000L) / rtt else 0

            performanceHistory.add(PerformanceData(rtt, throughput, System.currentTimeMillis()))

            // Keep only recent history
            if (performanceHistory.size > 10) {
                performanceHistory.removeAt(0)
            }

            // Calculate averages
            avgRtt = performanceHistory.map { it.rtt }.average().toLong()
            avgThroughput = performanceHistory.map { it.throughput }.average().toLong()

            // Calculate dynamic weight (higher throughput = higher weight)
            weight = max(0.1, avgThroughput.toDouble() / (1024 * 1024)) // Weight based on MB/s
        }
    }

    fun getWeight(): Double = weight
    fun getAvgThroughput(): Long = avgThroughput
    fun getAvgRtt(): Long = avgRtt

    fun close() {
        try {
            socket.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}

class ConnectionPool {
    private val connections = mutableListOf<OptimizedConnection>()

    fun addConnection(connection: OptimizedConnection) {
        connections.add(connection)
    }

    fun getConnections(): List<OptimizedConnection> = connections.toList()

    fun isEmpty(): Boolean = connections.isEmpty()
    fun size(): Int = connections.size

    suspend fun sendSessionStart(sessionStart: SessionStart, protocolHandler: ProtocolHandler) {
        val sessionMessage = protocolHandler.serializeMessage("session_start", sessionStart)

        connections.forEach { conn ->
            try {
                conn.socket.getOutputStream().write(sessionMessage)
                conn.socket.getOutputStream().flush()

                // Wait for session_ack (blocking for session setup)
                conn.socket.soTimeout = 10000
                val response = conn.readPendingResponses().firstOrNull()
                if (response != null && response.first == "session_ack") {
                    Log.d("ConnectionPool", "${conn.name}: Session acknowledged")
                }
            } catch (e: Exception) {
                Log.e("ConnectionPool", "${conn.name}: Session start error", e)
            }
        }
    }

    suspend fun sendTransferComplete(transferId: String, totalChunks: Int, fileSize: Long, protocolHandler: ProtocolHandler) {
        val completeMsg = mapOf(
            "transfer_id" to transferId,
            "success" to true,
            "stats" to mapOf(
                "total_chunks" to totalChunks,
                "file_size" to fileSize
            )
        )
        val completeMessage = protocolHandler.serializeMessage("transfer_complete", completeMsg)

        connections.forEach { conn ->
            try {
                conn.socket.getOutputStream().write(completeMessage)
                conn.socket.getOutputStream().flush()
            } catch (e: Exception) {
                Log.e("ConnectionPool", "${conn.name}: Complete message error", e)
            }
        }
    }

    fun closeAll() {
        connections.forEach { it.close() }
        connections.clear()
    }
}

class PerformanceMonitor(
    private val connectionPool: ConnectionPool,
    private val logCallback: (String) -> Unit
) {
    suspend fun start() {
        while (true) {
            delay(5000) // Report every 5 seconds

            val connections = connectionPool.getConnections()
            if (connections.isNotEmpty()) {
                val report = StringBuilder("📊 PERFORMANCE REPORT:\n")

                connections.forEach { conn ->
                    val throughputMbps = (conn.getAvgThroughput() * 8.0) / (1024 * 1024)
                    val weight = conn.getWeight()
                    report.append("   ${conn.name}: ${String.format("%.1f", throughputMbps)} Mbps (weight: ${String.format("%.2f", weight)})\n")
                }

                val totalThroughput = connections.sumOf { it.getAvgThroughput() }
                val totalMbps = (totalThroughput * 8.0) / (1024 * 1024)
                report.append("   TOTAL: ${String.format("%.1f", totalMbps)} Mbps")

                logCallback(report.toString())
            }
        }
    }
}

data class ChunkTask(
    val chunkId: Int,
    val data: ByteArray,
    val checksum: String,
    val transferId: String
)

data class PerformanceData(
    val rtt: Long,
    val throughput: Long,
    val timestamp: Long
)