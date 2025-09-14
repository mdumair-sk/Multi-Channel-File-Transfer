package com.filetransfer.minimal

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import java.text.SimpleDateFormat
import java.util.*

class TransferQueueAdapter(
    private val onItemClick: (QueuedTransfer) -> Unit,
    private val onPauseClick: (QueuedTransfer) -> Unit,
    private val onResumeClick: (QueuedTransfer) -> Unit,
    private val onCancelClick: (QueuedTransfer) -> Unit,
    private val onPriorityChange: (QueuedTransfer, Int) -> Unit
) : ListAdapter<QueuedTransfer, TransferQueueAdapter.TransferViewHolder>(TransferDiffCallback()) {

    private val progressMap = mutableMapOf<String, TransferProgress>()

    fun updateProgress(transferId: String, progress: TransferProgress) {
        progressMap[transferId] = progress
        // Find and update the specific item
        val position = currentList.indexOfFirst { it.id == transferId }
        if (position != -1) {
            notifyItemChanged(position)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransferViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transfer_queue, parent, false)
        return TransferViewHolder(view)
    }

    override fun onBindViewHolder(holder: TransferViewHolder, position: Int) {
        val transfer = getItem(position)
        val progress = progressMap[transfer.id]
        holder.bind(transfer, progress)
    }

    inner class TransferViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fileNameText: TextView = itemView.findViewById(R.id.fileNameText)
        private val fileSizeText: TextView = itemView.findViewById(R.id.fileSizeText)
        private val statusText: TextView = itemView.findViewById(R.id.statusText)
        private val statusIndicator: View = itemView.findViewById(R.id.statusIndicator)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val progressText: TextView = itemView.findViewById(R.id.progressText)
        private val speedText: TextView = itemView.findViewById(R.id.speedText)
        private val timeText: TextView = itemView.findViewById(R.id.timeText)
        private val prioritySpinner: Spinner = itemView.findViewById(R.id.prioritySpinner)
        private val pauseResumeButton: Button = itemView.findViewById(R.id.pauseResumeButton)
        private val cancelButton: Button = itemView.findViewById(R.id.cancelButton)
        private val dragHandle: ImageView = itemView.findViewById(R.id.dragHandle)

        fun bind(transfer: QueuedTransfer, progress: TransferProgress?) {
            fileNameText.text = transfer.filename
            fileSizeText.text = formatFileSize(transfer.fileSize)

            // Format timestamp
            val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            timeText.text = dateFormat.format(Date(transfer.addedTime))

            // Setup priority spinner
            setupPrioritySpinner(transfer)

            // Update based on status
            when (transfer.status) {
                TransferStatus.PENDING -> {
                    statusText.text = "Pending"
                    statusText.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_orange_dark))
                    statusIndicator.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_orange_dark))
                    progressBar.progress = 0
                    progressText.text = "Waiting to start..."
                    speedText.text = ""
                    pauseResumeButton.text = "Start"
                    pauseResumeButton.isEnabled = true
                    pauseResumeButton.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark))
                }

                TransferStatus.IN_PROGRESS -> {
                    statusText.text = "Transferring"
                    statusText.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_blue_bright))
                    statusIndicator.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_blue_bright))

                    progress?.let { p ->
                        progressBar.progress = p.percentComplete
                        progressText.text = "${p.percentComplete}% (${formatFileSize(p.transferredBytes)}/${formatFileSize(p.totalBytes)})"

                        var speedInfo = formatSpeed(p.speedBps)
                        if (p.currentChunkSize > 0) {
                            speedInfo += " • ${formatFileSize(p.currentChunkSize.toLong())} chunks"
                        }

                        // Calculate ETA
                        val remainingBytes = p.totalBytes - p.transferredBytes
                        if (p.speedBps > 0 && remainingBytes > 0) {
                            val etaSeconds = remainingBytes / p.speedBps
                            speedInfo += " • ETA: ${formatTime(etaSeconds)}"
                        }

                        speedText.text = speedInfo
                    } ?: run {
                        progressBar.progress = 0
                        progressText.text = "Starting transfer..."
                        speedText.text = ""
                    }

                    pauseResumeButton.text = "Pause"
                    pauseResumeButton.isEnabled = true
                    pauseResumeButton.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_orange_light))
                }

                TransferStatus.PAUSED -> {
                    statusText.text = "Paused"
                    statusText.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_orange_light))
                    statusIndicator.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_orange_light))

                    progress?.let { p ->
                        progressBar.progress = p.percentComplete
                        progressText.text = "${p.percentComplete}% (${formatFileSize(p.transferredBytes)}/${formatFileSize(p.totalBytes)})"
                        speedText.text = "Transfer paused"
                    } ?: run {
                        progressText.text = "Transfer paused"
                        speedText.text = ""
                    }

                    pauseResumeButton.text = "Resume"
                    pauseResumeButton.isEnabled = true
                    pauseResumeButton.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark))
                }

                TransferStatus.COMPLETED -> {
                    statusText.text = "Completed"
                    statusText.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark))
                    statusIndicator.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_green_dark))
                    progressBar.progress = 100
                    progressText.text = "100% Complete"
                    speedText.text = "Transfer completed successfully"
                    pauseResumeButton.isEnabled = false
                    pauseResumeButton.text = "Done"
                    cancelButton.text = "Remove"
                }

                TransferStatus.FAILED -> {
                    statusText.text = "Failed"
                    statusText.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark))
                    statusIndicator.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_red_dark))

                    progress?.let { p ->
                        progressBar.progress = p.percentComplete
                        progressText.text = "Transfer failed at ${p.percentComplete}%"
                    } ?: run {
                        progressText.text = "Transfer failed"
                    }

                    speedText.text = "Tap 'Retry' to try again"
                    pauseResumeButton.text = "Retry"
                    pauseResumeButton.isEnabled = true
                    pauseResumeButton.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.holo_blue_bright))
                    cancelButton.text = "Remove"
                }

                TransferStatus.CANCELLED -> {
                    statusText.text = "Cancelled"
                    statusText.setTextColor(ContextCompat.getColor(itemView.context, android.R.color.darker_gray))
                    statusIndicator.setBackgroundColor(ContextCompat.getColor(itemView.context, android.R.color.darker_gray))
                    progressText.text = "Cancelled by user"
                    speedText.text = ""
                    pauseResumeButton.isEnabled = false
                    pauseResumeButton.text = "Cancelled"
                    cancelButton.text = "Remove"
                }
            }

            // Setup click listeners
            itemView.setOnClickListener { onItemClick(transfer) }

            pauseResumeButton.setOnClickListener {
                when (transfer.status) {
                    TransferStatus.IN_PROGRESS -> onPauseClick(transfer)
                    TransferStatus.PAUSED, TransferStatus.FAILED -> onResumeClick(transfer)
                    TransferStatus.PENDING -> onResumeClick(transfer) // Start transfer
                    else -> {}
                }
            }

            cancelButton.setOnClickListener { onCancelClick(transfer) }
        }

        private fun setupPrioritySpinner(transfer: QueuedTransfer) {
            val priorities = arrayOf("Low", "Normal", "High", "Urgent")
            val adapter = ArrayAdapter(itemView.context, android.R.layout.simple_spinner_item, priorities)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            prioritySpinner.adapter = adapter

            // Set current priority (ensure it's within bounds)
            val priorityIndex = minOf(maxOf(transfer.priority, 0), priorities.size - 1)
            prioritySpinner.setSelection(priorityIndex)

            prioritySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position != transfer.priority) {
                        onPriorityChange(transfer, position)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        private fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"

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
            if (bytesPerSecond <= 0) return "0 Mbps"

            val mbps = (bytesPerSecond * 8.0) / (1024 * 1024)
            return String.format("%.1f Mbps", mbps)
        }

        private fun formatTime(seconds: Long): String {
            return when {
                seconds < 0 -> "Unknown"
                seconds < 60 -> "${seconds}s"
                seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
                else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            }
        }
    }
}

class TransferDiffCallback : DiffUtil.ItemCallback<QueuedTransfer>() {
    override fun areItemsTheSame(oldItem: QueuedTransfer, newItem: QueuedTransfer): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: QueuedTransfer, newItem: QueuedTransfer): Boolean {
        return oldItem == newItem
    }
}