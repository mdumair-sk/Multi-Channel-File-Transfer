package com.filetransfer.minimal

import android.os.Parcel
import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.*

// Existing classes
data class TransferConfig(
    val usbEnabled: Boolean,
    val wifiEnabled: Boolean,
    val usbHost: String,
    val usbPort: Int,
    val wifiHost: String,
    val wifiPort: Int,
    val chunkSize: Int
)

data class TransferProgress(
    val transferredBytes: Long,
    val totalBytes: Long,
    val percentComplete: Int,
    val speedBps: Long,
    val currentChunkSize: Int = 0,
    val networkType: String = "Unknown"
)

data class SessionStart(
    val transferId: String,
    val totalChunks: Int,
    val chunkSize: Int,
    val filename: String
)

data class ChunkHeader(
    val transferId: String,
    val chunkId: Int,
    val chunkSize: Int,
    val checksum: String
)

data class ChunkAck(
    val chunkId: Int,
    val status: String,
    val bytesReceived: Int,
    val checksumVerified: Boolean
)

// Enhanced classes for functionality

@Entity(tableName = "transfer_states")
data class TransferState(
    @PrimaryKey val transferId: String,
    val filename: String,
    val fileUri: String,
    val fileSize: Long,
    val transferredBytes: Long,
    val totalChunks: Int,
    val completedChunks: Set<Int>,
    val chunkSize: Int,
    val status: TransferStatus,
    val timestamp: Long,
    val lastResumeTime: Long = 0
)

enum class TransferStatus {
    PENDING,
    IN_PROGRESS,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

// IMPLEMENTED: Made QueuedTransfer Parcelable
data class QueuedTransfer(
    val id: String = UUID.randomUUID().toString(),
    val filename: String,
    val fileUri: String,
    val fileSize: Long,
    val priority: Int = 0,
    val status: TransferStatus = TransferStatus.PENDING,
    val progress: TransferProgress? = null,
    val addedTime: Long = System.currentTimeMillis()
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readString() ?: UUID.randomUUID().toString(),
        parcel.readString() ?: "",
        parcel.readString() ?: "",
        parcel.readLong(),
        parcel.readInt(),
        TransferStatus.valueOf(parcel.readString() ?: TransferStatus.PENDING.name),
        parcel.readParcelable(TransferProgress::class.java.classLoader),
        parcel.readLong()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(filename)
        parcel.writeString(fileUri)
        parcel.writeLong(fileSize)
        parcel.writeInt(priority)
        parcel.writeString(status.name)
        parcel.writeParcelable(progress as Parcelable?, flags)
        parcel.writeLong(addedTime)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<QueuedTransfer> {
        override fun createFromParcel(parcel: Parcel): QueuedTransfer {
            return QueuedTransfer(parcel)
        }

        override fun newArray(size: Int): Array<QueuedTransfer?> {
            return arrayOfNulls(size)
        }
    }
}

// IMPLEMENTED: Made TransferProgress Parcelable
data class TransferProgressParcelable(
    val transferredBytes: Long,
    val totalBytes: Long,
    val percentComplete: Int,
    val speedBps: Long,
    val currentChunkSize: Int = 0,
    val networkType: String = "Unknown"
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readLong(),
        parcel.readLong(),
        parcel.readInt(),
        parcel.readLong(),
        parcel.readInt(),
        parcel.readString() ?: "Unknown"
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeLong(transferredBytes)
        parcel.writeLong(totalBytes)
        parcel.writeInt(percentComplete)
        parcel.writeLong(speedBps)
        parcel.writeInt(currentChunkSize)
        parcel.writeString(networkType)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<TransferProgressParcelable> {
        override fun createFromParcel(parcel: Parcel): TransferProgressParcelable {
            return TransferProgressParcelable(parcel)
        }

        override fun newArray(size: Int): Array<TransferProgressParcelable?> {
            return arrayOfNulls(size)
        }
    }

    // Convert to non-Parcelable TransferProgress
    fun toTransferProgress(): TransferProgress {
        return TransferProgress(
            transferredBytes = transferredBytes,
            totalBytes = totalBytes,
            percentComplete = percentComplete,
            speedBps = speedBps,
            currentChunkSize = currentChunkSize,
            networkType = networkType
        )
    }
}

// Extension function to convert TransferProgress to Parcelable
fun TransferProgress.toParcelable(): TransferProgressParcelable {
    return TransferProgressParcelable(
        transferredBytes = transferredBytes,
        totalBytes = totalBytes,
        percentComplete = percentComplete,
        speedBps = speedBps,
        currentChunkSize = currentChunkSize,
        networkType = networkType
    )
}

data class NetworkPerformance(
    val rttHistory: MutableList<Long> = mutableListOf(),
    val throughputHistory: MutableList<Long> = mutableListOf(),
    val connectionType: String = "Unknown",
    val currentChunkSize: Int = 2 * 1024 * 1024
) {
    fun getAverageRtt(): Long = if (rttHistory.isNotEmpty()) rttHistory.average().toLong() else 0
    fun getAverageThroughput(): Long = if (throughputHistory.isNotEmpty()) throughputHistory.average().toLong() else 0

    fun updatePerformance(rtt: Long, throughput: Long) {
        rttHistory.add(rtt)
        throughputHistory.add(throughput)

        // Keep only last 20 measurements
        if (rttHistory.size > 20) rttHistory.removeAt(0)
        if (throughputHistory.size > 20) throughputHistory.removeAt(0)
    }

    fun getOptimalChunkSize(): Int {
        val avgRtt = getAverageRtt()
        val avgThroughput = getAverageThroughput()

        return when {
            avgRtt < 50 && avgThroughput > 10_000_000 -> 8 * 1024 * 1024 // 8MB for excellent connections
            avgRtt < 100 && avgThroughput > 5_000_000 -> 4 * 1024 * 1024 // 4MB for good connections
            avgRtt < 200 && avgThroughput > 1_000_000 -> 2 * 1024 * 1024 // 2MB for normal connections
            avgRtt < 500 -> 1 * 1024 * 1024 // 1MB for slower connections
            else -> 512 * 1024 // 512KB for very slow connections
        }
    }
}

data class ServiceCommand(
    val action: String,
    val transferId: String? = null,
    val data: Any? = null
) {
    companion object {
        const val START_TRANSFER = "start_transfer"
        const val PAUSE_TRANSFER = "pause_transfer"
        const val RESUME_TRANSFER = "resume_transfer"
        const val CANCEL_TRANSFER = "cancel_transfer"
        const val QUEUE_TRANSFER = "queue_transfer"
    }
}

// Additional utility data classes for enhanced functionality

data class ConnectionStats(
    val connectionId: String,
    val connectionType: String,
    val isActive: Boolean,
    val totalChunksSent: Int,
    val totalBytesSent: Long,
    val averageRtt: Long,
    val averageThroughput: Long,
    val successRate: Double,
    val lastActiveTime: Long
)

data class TransferStatistics(
    val transferId: String,
    val filename: String,
    val fileSize: Long,
    val transferredBytes: Long,
    val startTime: Long,
    val endTime: Long?,
    val totalDuration: Long,
    val averageSpeed: Long,
    val peakSpeed: Long,
    val connectionStats: List<ConnectionStats>,
    val totalRetries: Int,
    val chunksTransferred: Int,
    val chunksFailed: Int
)

data class QueueStatistics(
    val totalFiles: Int,
    val totalSize: Long,
    val pendingFiles: Int,
    val activeFiles: Int,
    val completedFiles: Int,
    val failedFiles: Int,
    val averageFileSize: Long,
    val estimatedTimeRemaining: Long
) {
    companion object {
        fun fromQueue(queue: List<QueuedTransfer>): QueueStatistics {
            val totalFiles = queue.size
            val totalSize = queue.sumOf { it.fileSize }
            val pendingFiles = queue.count { it.status == TransferStatus.PENDING }
            val activeFiles = queue.count { it.status == TransferStatus.IN_PROGRESS }
            val completedFiles = queue.count { it.status == TransferStatus.COMPLETED }
            val failedFiles = queue.count { it.status == TransferStatus.FAILED }
            val averageFileSize = if (totalFiles > 0) totalSize / totalFiles else 0

            // Simple ETA calculation based on average progress
            val activeTransfers = queue.filter { it.status == TransferStatus.IN_PROGRESS && it.progress != null }
            val estimatedTimeRemaining = if (activeTransfers.isNotEmpty()) {
                val avgProgress = activeTransfers.mapNotNull { it.progress?.speedBps }.average()
                val remainingBytes = queue.filter { it.status != TransferStatus.COMPLETED }.sumOf { it.fileSize }
                if (avgProgress > 0) (remainingBytes / avgProgress).toLong() else 0
            } else 0

            return QueueStatistics(
                totalFiles = totalFiles,
                totalSize = totalSize,
                pendingFiles = pendingFiles,
                activeFiles = activeFiles,
                completedFiles = completedFiles,
                failedFiles = failedFiles,
                averageFileSize = averageFileSize,
                estimatedTimeRemaining = estimatedTimeRemaining
            )
        }
    }
}