package com.utsav.ffdownloader.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.utsav.ffdownloader.R
import com.utsav.ffdownloader.core.ItemStatus
import com.utsav.ffdownloader.core.QueueItem

class QueueAdapter(
    private val onPauseResume: (QueueItem) -> Unit,
    private val onCancel: (QueueItem) -> Unit
) : ListAdapter<QueueItem, QueueAdapter.VH>(DIFF) {

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val indicator: android.view.View = view.findViewById(R.id.statusIndicator)
        val title: TextView = view.findViewById(R.id.itemTitle)
        val category: TextView = view.findViewById(R.id.itemCategory)
        val status: TextView = view.findViewById(R.id.itemStatus)
        val progress: ProgressBar = view.findViewById(R.id.itemProgress)
        val actions: android.view.View = view.findViewById(R.id.itemActions)
        val pauseResume: Button = view.findViewById(R.id.itemPauseResume)
        val cancel: Button = view.findViewById(R.id.itemCancel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.title.text = item.fileName ?: item.sourceUrl
        holder.category.text = item.category.label

        val context = holder.itemView.context
        holder.status.text = when (item.status) {
            ItemStatus.PENDING -> "⏳ Queued"
            ItemStatus.RESOLVING -> "🔄 Resolving…"
            ItemStatus.NEEDS_CHALLENGE -> "🛡️ Verifying — complete the check in the browser screen"
            ItemStatus.READY -> "✅ Ready to download"
            ItemStatus.DOWNLOADING -> "⬇️ ${buildDownloadingText(item)}"
            ItemStatus.PAUSED -> "⏸️ Paused"
            ItemStatus.DONE -> "✔️ Done"
            ItemStatus.FAILED -> "❌ ${item.error ?: "Failed"}"
        }
        holder.indicator.setBackgroundColor(context.getColor(colorForStatus(item.status)))

        if (item.status == ItemStatus.DOWNLOADING && item.bytesTotal > 0) {
            holder.progress.visibility = android.view.View.VISIBLE
            holder.progress.progress = ((item.bytesDone * 100) / item.bytesTotal).toInt()
        } else {
            holder.progress.visibility = android.view.View.GONE
        }

        val showActions = item.status == ItemStatus.DOWNLOADING || item.status == ItemStatus.PAUSED
        holder.actions.visibility = if (showActions) android.view.View.VISIBLE else android.view.View.GONE
        holder.pauseResume.text = if (item.status == ItemStatus.PAUSED) {
            context.getString(R.string.action_resume)
        } else {
            context.getString(R.string.action_pause)
        }
        holder.pauseResume.setOnClickListener { onPauseResume(item) }
        holder.cancel.setOnClickListener { onCancel(item) }
    }

    private fun colorForStatus(status: ItemStatus): Int = when (status) {
        ItemStatus.PENDING, ItemStatus.RESOLVING, ItemStatus.NEEDS_CHALLENGE -> R.color.ff_muted
        ItemStatus.READY -> R.color.ff_accent
        ItemStatus.DOWNLOADING -> R.color.ff_accent
        ItemStatus.PAUSED -> R.color.ff_warning
        ItemStatus.DONE -> R.color.ff_success
        ItemStatus.FAILED -> R.color.ff_error
    }

    private fun buildDownloadingText(item: QueueItem): String {
        val pct = if (item.bytesTotal > 0) (item.bytesDone * 100 / item.bytesTotal) else 0
        val speedKb = item.speedBps / 1024.0

        val elapsedSec = if (item.downloadStartedAtMs > 0) {
            ((System.currentTimeMillis() - item.downloadStartedAtMs) / 1000L).coerceAtLeast(0)
        } else 0L

        val remainingBytes = (item.bytesTotal - item.bytesDone).coerceAtLeast(0)
        val etaSec = if (item.speedBps > 1.0) (remainingBytes / item.speedBps).toLong() else -1L

        val elapsedStr = formatDuration(elapsedSec)
        val etaStr = if (etaSec >= 0) formatDuration(etaSec) else "…"

        return "$pct% @ ${"%.0f".format(speedKb)} KB/s  •  $elapsedStr elapsed  •  ETA $etaStr"
    }

    private fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> "%d:%02d:%02d".format(h, m, s)
            else -> "%d:%02d".format(m, s)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<QueueItem>() {
            override fun areItemsTheSame(a: QueueItem, b: QueueItem) = a.id == b.id
            override fun areContentsTheSame(a: QueueItem, b: QueueItem) = a == b
        }
    }
}
