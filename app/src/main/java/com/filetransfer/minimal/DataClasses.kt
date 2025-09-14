package com.filetransfer.minimal

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
    val speedBps: Long
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