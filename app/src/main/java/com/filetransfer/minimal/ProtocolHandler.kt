// Fixed Protocol Handler - matching Python server exactly
import com.filetransfer.minimal.ChunkHeader
import com.filetransfer.minimal.SessionStart
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class ProtocolHandler {
    companion object {
        private val MAGIC_HEADER = byteArrayOf(0x42, 0x54, 0x46, 0x53) // "BTFS"
        private const val VERSION: Byte = 0x01
        private const val MAX_MESSAGE_SIZE = 2097152 // 2MB matching Python
    }

    fun serializeMessage(messageType: String, data: Any): ByteArray {
        val jsonData = toJson(data)
        val messageTypeBytes = messageType.toByteArray(Charsets.UTF_8)  // Changed to UTF-8
        val jsonBytes = jsonData.toByteArray(Charsets.UTF_8)

        // Compute checksum exactly as Python does
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(messageTypeBytes)
        digest.update(jsonBytes)
        val checksum = digest.digest()

        // Calculate total size
        val totalSize = 4 + 1 + 2 + messageTypeBytes.size + 4 + jsonBytes.size + 32

        val buffer = ByteBuffer.allocate(totalSize)
        buffer.order(ByteOrder.BIG_ENDIAN)  // Explicitly set byte order

        // Header: magic + version + msgtype_len
        buffer.put(MAGIC_HEADER)
        buffer.put(VERSION)
        buffer.putShort(messageTypeBytes.size.toShort())

        // Message type
        buffer.put(messageTypeBytes)

        // Data length (as unsigned int)
        buffer.putInt(jsonBytes.size)

        // JSON data
        buffer.put(jsonBytes)

        // Checksum
        buffer.put(checksum)

        return buffer.array()
    }

    fun deserializeMessage(data: ByteArray): Pair<String, JSONObject> {
        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // Verify magic header
        val magic = ByteArray(4)
        buffer.get(magic)
        if (!magic.contentEquals(MAGIC_HEADER)) {
            throw IllegalArgumentException("Invalid magic header")
        }

        // Verify version
        val version = buffer.get()
        if (version != VERSION) {
            throw IllegalArgumentException("Unsupported version: $version")
        }

        // Read message type
        val msgTypeLen = buffer.short.toInt() and 0xFFFF  // Ensure unsigned
        val msgTypeBytes = ByteArray(msgTypeLen)
        buffer.get(msgTypeBytes)
        val messageType = String(msgTypeBytes, Charsets.UTF_8)

        // Read JSON data length
        val dataLen = buffer.int
        if (dataLen > MAX_MESSAGE_SIZE) {
            throw IllegalArgumentException("Data too large: $dataLen")
        }

        val jsonBytes = ByteArray(dataLen)
        buffer.get(jsonBytes)
        val jsonData = String(jsonBytes, Charsets.UTF_8)

        // Read and verify checksum
        val receivedChecksum = ByteArray(32)
        buffer.get(receivedChecksum)

        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(msgTypeBytes)
        digest.update(jsonBytes)
        val computedChecksum = digest.digest()

        if (!receivedChecksum.contentEquals(computedChecksum)) {
            throw IllegalArgumentException("Checksum mismatch")
        }

        return Pair(messageType, JSONObject(jsonData))
    }

    private fun toJson(data: Any): String {
        return when (data) {
            is SessionStart -> JSONObject().apply {
                put("transfer_id", data.transferId)
                put("total_chunks", data.totalChunks)
                put("chunk_size", data.chunkSize)
                put("filename", data.filename)
            }.toString(0)  // No indentation, matching Python's separators

            is ChunkHeader -> JSONObject().apply {
                put("transfer_id", data.transferId)
                put("chunk_id", data.chunkId)
                put("chunk_size", data.chunkSize)
                put("checksum", data.checksum)
            }.toString(0)

            is Map<*, *> -> {
                val jsonObj = JSONObject()
                data.forEach { (key, value) ->
                    when (value) {
                        is Map<*, *> -> jsonObj.put(key.toString(), JSONObject(value as Map<String, Any>))
                        else -> jsonObj.put(key.toString(), value)
                    }
                }
                jsonObj.toString(0)
            }

            else -> JSONObject().toString(0)
        }
    }
}