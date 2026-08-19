package com.utsav.ffdownloader.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.utsav.ffdownloader.R
import com.utsav.ffdownloader.core.ItemStatus
import com.utsav.ffdownloader.core.QueueItem
import com.utsav.ffdownloader.core.QueueRepository
import com.utsav.ffdownloader.service.DownloadService

class DownloadsFragment : Fragment() {

    private lateinit var adapter: QueueAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_downloads, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = QueueAdapter(
            onPauseResume = { item -> onItemPauseResume(item) },
            onCancel      = { item -> DownloadService.cancelItem(requireContext(), item.id) }
        )

        val recycler    = view.findViewById<RecyclerView>(R.id.queueRecycler)
        val emptyContainer = view.findViewById<View>(R.id.emptyContainer)
        val summary     = view.findViewById<TextView>(R.id.queueSummary)
        val cancelBtn   = view.findViewById<View>(R.id.cancelButton)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        cancelBtn.setOnClickListener { DownloadService.cancelAll(requireContext()) }

        QueueRepository.items.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)

            val isEmpty = list.isEmpty()
            recycler.visibility       = if (isEmpty) View.GONE else View.VISIBLE
            emptyContainer.visibility = if (isEmpty) View.VISIBLE else View.GONE
            cancelBtn.visibility      = if (isEmpty) View.GONE else View.VISIBLE

            if (isEmpty) {
                summary.visibility = View.GONE
                return@observe
            }

            val downloading = list.count { it.status == ItemStatus.DOWNLOADING }
            val ready       = list.count { it.status == ItemStatus.READY }
            val resolving   = list.count {
                it.status == ItemStatus.PENDING ||
                it.status == ItemStatus.RESOLVING ||
                it.status == ItemStatus.NEEDS_CHALLENGE
            }
            val paused  = list.count { it.status == ItemStatus.PAUSED }
            val done    = list.count { it.status == ItemStatus.DONE }
            val failed  = list.count { it.status == ItemStatus.FAILED }

            val parts = mutableListOf<String>()
            if (downloading > 0) parts += "$downloading downloading"
            if (ready > 0)       parts += "$ready ready"
            if (resolving > 0)   parts += "$resolving resolving"
            if (paused > 0)      parts += "$paused paused"
            if (done > 0)        parts += "$done done"
            if (failed > 0)      parts += "$failed failed"

            summary.text = parts.joinToString("  •  ")
            summary.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun onItemPauseResume(item: QueueItem) {
        if (item.status == ItemStatus.PAUSED) {
            DownloadService.resumeItem(requireContext(), item.id)
        } else {
            DownloadService.pauseItem(requireContext(), item.id)
        }
    }
}
