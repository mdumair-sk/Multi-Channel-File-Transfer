// EnhancedStreamingTransferEngine.kt - Resume + Dynamic Chunk Sizing

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
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.filetransfer.minimal.*
import kotlin.math.max
import kotlin.math.min

class EnhancedStreamingTransferEngine {
    private val protocolHandler = ProtocolHandler()
    private val TAG = "EnhancedTransferEngine"

    // Performance tracking
    private val networkPerformance = NetworkPerformance()

    // Dynamic chunk sizing constants
    private val MIN_CHUNK_SIZE = 512 * 1024 // 512KB
    private val MAX_CHUNK_SIZE = 8 * 1024 * 1024 // 8MB
    private val CHUNK_ADJUSTMENT_INTERVAL = 100 // Adjust every 100 chunks

    private var currentChunkSize = 2 * 1024 * 1024 // Start with 2MB
    private var chunksProcessed = 0

    suspend fun startTransfer(
        config: TransferConfig,
        fileName: String,
        fileSize: Long,
        inputStream: InputStream,
        progressCallback: (TransferProgress) -> Unit,
        logCallback: (String) -> Unit,
        resumeState: TransferState? = null
    ) = withContext(Dispatchers.IO) {

        val transferId = resumeState?.transferId ?: UUID.randomUUID().toString()
        val isResume = resumeState != null

        // Use dynamic chunk size if enabled, otherwise use config
        val chunkSize = if (resumeState?.chunkSize != null) {
            resumeState.chunkSize
        } else {
            currentChunkSize
        }

        val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()
        val transferredBytes = AtomicLong(resumeState?.transferredBytes ?: 0)
        val startTime = System.currentTimeMillis()

        logCallback("🚀 ${if (isResume) "RESUMING" else "STARTING"} TRANSFER: $fileName")
        logCallback("📊 File: ${formatBytes(fileSize)}, Chunks: $totalChunks (${formatBytes(chunkSize.toLong())} each)")
        logCallback("🔄 Resume: $isResume, Completed: ${resumeState?.completedChunks?.size ?: 0}")
        logCallback("💾 Dynamic chunk sizing enabled")

        // Create connection pool
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

            // Send session start with resume support
            val sessionStart = SessionStart(transferId, totalChunks, chunkSize, fileName)
            val sessionResponse = connectionPool.sendSessionStart(sessionStart, protocolHandler)

            // Handle resume response
            val completedChunks = mutableSetOf<Int>()
            if (sessionResponse?.first == "session_ack") {
                val responseData = sessionResponse.second as JSONObject
                val status = responseData.optString("status")

                if (status == "resume") {
                    val completedChunksList = responseData.optJSONArray("completed_chunks")
                    completedChunksList?.let { array ->
                        for (i in 0 until array.length()) {
                            completedChunks.add(array.getInt(i))
                        }
                    }
                    logCallback("🔄 Server confirmed resume: ${completedChunks.size} chunks already received")
                }
            }

            logCallback("✅ Session started on ${connectionPool.size()} channel(s)")

            // Start streaming transfer with resume capability
            val completedChunksAtomic = AtomicInteger(completedChunks.size)
            val chunkProducerChannel = Channel<ChunkTask>(capacity = 5)

            // Skip completed chunks if resuming
            val chunksToProcess = (0 until totalChunks).filterNot { completedChunks.contains(it) }

            // Chunk producer - reads file in streaming fashion
            val producerJob = launch {
                streamingChunkProducer(
                    inputStream = inputStream,
                    transferId = transferId,
                    chunksToProcess = chunksToProcess,
                    chunkSize = chunkSize,
                    chunkChannel = chunkProducerChannel,
                    logCallback = logCallback
                )
            }

            // Start chunk senders (one per connection)
            val senderJobs = connectionPool.getConnections().map { conn ->
                launch {
                    enhancedChunkSender(
                        connection = conn,
                        chunkChannel = chunkProducerChannel,
                        protocolHandler = protocolHandler,
                        completedChunks = completedChunksAtomic,
                        totalChunks = totalChunks,
                        transferredBytes = transferredBytes,
                        fileSize = fileSize,
                        startTime = startTime,
                        progressCallback = progressCallback,
                        logCallback = logCallback
                    )
                }
            }

            // Wait for producer and all senders
            producerJob.join()
            senderJobs.joinAll()

            // Send transfer complete
            connectionPool.sendTransferComplete(transferId, totalChunks, fileSize, protocolHandler)

            val duration = System.currentTimeMillis() - startTime
            val avgSpeedMbps = (fileSize * 8.0) / (duration * 1000.0)

            logCallback("🎉 TRANSFER COMPLETE!")
            logCallback("⚡ Speed: ${String.format("%.1f", avgSpeedMbps)} Mbps")
            logCallback("⏱️ Duration: ${duration}ms")
            logCallback("📈 Final chunk size: ${formatBytes(currentChunkSize.toLong())}")
            logCallback("🎯 Network performance: RTT=${networkPerformance.getAverageRtt()}ms, Throughput=${formatBytes(networkPerformance.getAverageThroughput())}/s")

        } catch (e: Exception) {
            logCallback("💥 Transfer failed: ${e.message}")
            Log.e(TAG, "Transfer failed", e)
            throw e
        } finally {
            inputStream.close()
            connectionPool.closeAll()
        }
    }

    private suspend fun streamingChunkProducer(
        inputStream: InputStream,
        transferId: String,
        chunksToProcess: List<Int>,
        chunkSize: Int,
        chunkChannel: Channel<ChunkTask>,
        logCallback: (String) -> Unit
    ) {
        try {
            val buffer = ByteArray(chunkSize)

            for (chunkId in chunksToProcess) {
                // Seek to chunk position
                val skipBytes = chunkId.toLong() * chunkSize
                inputStream.skip(skipBytes)

                // Read chunk data
                var totalRead = 0
                val remainingBytes = minOf(chunkSize, inputStream.available())

                while (totalRead < remainingBytes) {
                    val bytesRead = inputStream.read(buffer, totalRead, remainingBytes - totalRead)
                    if (bytesRead == -1) break
                    totalRead += bytesRead
                }

                if (totalRead == 0) break

                // Create chunk data array with actual size
                val chunkData = ByteArray(totalRead)
                System.arraycopy(buffer, 0, chunkData, 0, totalRead)

                // Compute checksum
                val checksum = computeSHA256(chunkData)

                val task = ChunkTask(
                    chunkId = chunkId,
                    data = chunkData,
                    checksum = checksum,
                    transferId = transferId
                )

                // Send chunk to channel
                chunkChannel.send(task)

                if ((chunkId + 1) % 100 == 0) {
                    logCallback("📖 Processed chunk ${chunkId + 1}/${chunksToProcess.size}")
                }
            }

            chunkChannel.close()
            logCallback("✅ File streaming complete: ${chunksToProcess.size} chunks processed")

        } catch (e: Exception) {
            logCallback("❌ Streaming producer error: ${e.message}")
            Log.e(TAG, "Streaming producer error", e)
            chunkChannel.close(e)
            throw e
        }
    }

    private suspend fun enhancedChunkSender(
        connection: OptimizedConnection,
        chunkChannel: Channel<ChunkTask>,
        protocolHandler: ProtocolHandler,
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
            // Process chunks as they become available
            for (task in chunkChannel) {
                try {
                    // Send chunk with current chunk size
                    connection.sendChunk(task, protocolHandler)
                    inFlightChunks[task.chunkId] = System.currentTimeMillis()

                    Log.d(TAG, "${connection.name}: Sent chunk ${task.chunkId} (${inFlightChunks.size} in flight)")

                    // Check for responses (non-blocking)
                    val responses = connection.readPendingResponses()
                    for (response in responses) {
                        val (msgType, data) = response
                        if (msgType == "chunk_ack") {
                            val chunkId = data.optInt("chunk_id", -1)
                            val sendTime = inFlightChunks.remove(chunkId)

                            if (sendTime != null) {
                                val rtt = System.currentTimeMillis() - sendTime
                                val throughput = (task.data.size * 1000L) / max(rtt, 1)

                                // Update performance metrics
                                connection.updatePerformance(rtt, task.data.size)
                                networkPerformance.updatePerformance(rtt, throughput)

                                // Dynamic chunk size adjustment
                                chunksProcessed++
                                if (chunksProcessed % CHUNK_ADJUSTMENT_INTERVAL == 0) {
                                    adjustChunkSize()
                                    logCallback("📏 Adjusted chunk size to: ${formatBytes(currentChunkSize.toLong())}")
                                }

                                val completed = completedChunks.incrementAndGet()
                                transferredBytes.addAndGet(task.data.size.toLong())

                                // Update progress with performance data
                                val currentTime = System.currentTimeMillis()
                                val elapsedSeconds = (currentTime - startTime) / 1000.0
                                val speedBps = if (elapsedSeconds > 0) (transferredBytes.get() / elapsedSeconds).toLong() else 0
                                val percentComplete = ((completed * 100) / totalChunks)

                                val enhancedProgress = TransferProgress(
                                    transferredBytes = transferredBytes.get(),
                                    totalBytes = fileSize,
                                    percentComplete = percentComplete,
                                    speedBps = speedBps,
                                    currentChunkSize = currentChunkSize,
                                    networkType = connection.name
                                )

                                progressCallback(enhancedProgress)

                                if (completed % 50 == 0) {
                                    val speedMbps = (speedBps * 8.0) / (1024 * 1024)
                                    logCallback("📈 Progress: $completed/$totalChunks (${String.format("%.1f", speedMbps)} Mbps, RTT: ${rtt}ms)")
                                }
                            }
                        }
                    }

                    // Throttle if too many chunks in flight
                    while (inFlightChunks.size >= 10) {
                        delay(1)
                        // Process more responses
                        val moreResponses = connection.readPendingResponses()
                        for (response in moreResponses) {
                            val (msgType, data) = response
                            if (msgType == "chunk_ack") {
                                val chunkId = data.optInt("chunk_id", -1)
                                inFlightChunks.remove(chunkId)?.let { sendTime ->
                                    val rtt = System.currentTimeMillis() - sendTime
                                    val throughput = (currentChunkSize * 1000L) / max(rtt, 1)

                                    connection.updatePerformance(rtt, currentChunkSize)
                                    networkPerformance.updatePerformance(rtt, throughput)
                                    completedChunks.incrementAndGet()
                                    transferredBytes.addAndGet(currentChunkSize.toLong())
                                }
                            }
                        }
                    }

                } catch (e: Exception) {
                    logCallback("${connection.name}: Error sending chunk ${task.chunkId}: ${e.message}")
                    break
                }
            }

            // Wait for remaining responses
            while (inFlightChunks.isNotEmpty()) {
                delay(10)
                val responses = connection.readPendingResponses()
                for (response in responses) {
                    val (msgType, data) = response
                    if (msgType == "chunk_ack") {
                        val chunkId = data.optInt("chunk_id", -1)
                        inFlightChunks.remove(chunkId)?.let { sendTime ->
                            val rtt = System.currentTimeMillis() - sendTime
                            val throughput = (currentChunkSize * 1000L) / max(rtt, 1)

                            connection.updatePerformance(rtt, currentChunkSize)
                            networkPerformance.updatePerformance(rtt, throughput)
                            completedChunks.incrementAndGet()
                            transferredBytes.addAndGet(currentChunkSize.toLong())
                        }
                    }
                }
            }

        } catch (e: Exception) {
            logCallback("${connection.name}: Sender error: ${e.message}")
            Log.e(TAG, "Sender error", e)
        }
    }

    private fun adjustChunkSize() {
        val optimalSize = networkPerformance.getOptimalChunkSize()

        // Gradually adjust toward optimal size
        when {
            optimalSize > currentChunkSize -> {
                currentChunkSize = minOf(optimalSize, currentChunkSize * 2, MAX_CHUNK_SIZE)
            }
            optimalSize < currentChunkSize -> {
                currentChunkSize = maxOf(optimalSize, currentChunkSize / 2, MIN_CHUNK_SIZE)
            }
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

    // Enhanced connection classes with better performance tracking
    class OptimizedConnection(
        val socket: Socket,
        val name: String,
        private val logCallback: (String) -> Unit
    ) {
        private val outputStream = socket.getOutputStream()
        private val inputStream = DataInputStream(socket.getInputStream())
        private val performanceHistory = mutableListOf<PerformanceData>()
        private val performanceMutex = Mutex()
        private var avgRtt: Long = 0
        private var avgThroughput: Long = 0
        private var weight: Double = 1.0

        suspend fun sendChunk(task: ChunkTask, protocolHandler: ProtocolHandler) {
            val chunkHeader = ChunkHeader(task.transferId, task.chunkId, task.data.size, task.checksum)
            val headerMessage = protocolHandler.serializeMessage("chunk_header", chunkHeader)

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
                socket.soTimeout = 100
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
                socket.soTimeout = 30000
            }
        }

        suspend fun updatePerformance(rtt: Long, bytesTransferred: Int) {
            performanceMutex.withLock {
                val throughput = if (rtt > 0) (bytesTransferred * 1000L) / rtt else 0
                performanceHistory.add(PerformanceData(rtt, throughput, System.currentTimeMillis()))

                if (performanceHistory.size > 10) {
                    performanceHistory.removeAt(0)
                }

                avgRtt = performanceHistory.map { it.rtt }.average().toLong()
                avgThroughput = performanceHistory.map { it.throughput }.average().toLong()
                weight = max(0.1, avgThroughput.toDouble() / (1024 * 1024))
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

        suspend fun sendSessionStart(sessionStart: SessionStart, protocolHandler: ProtocolHandler): Pair<String, Any>? {
            val sessionMessage = protocolHandler.serializeMessage("session_start", sessionStart)

            for (conn in connections) {
                try {
                    conn.socket.getOutputStream().write(sessionMessage)
                    conn.socket.getOutputStream().flush()
                    conn.socket.soTimeout = 10000

                    val response = conn.readPendingResponses().firstOrNull()
                    if (response != null && response.first == "session_ack") {
                        Log.d("ConnectionPool", "${conn.name}: Session acknowledged")
                        return response
                    }
                } catch (e: Exception) {
                    Log.e("ConnectionPool", "${conn.name}: Session start error", e)
                }
            }
            return null
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
}
