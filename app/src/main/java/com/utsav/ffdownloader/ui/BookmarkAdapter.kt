package com.utsav.ffdownloader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.utsav.ffdownloader.R
import com.utsav.ffdownloader.core.Bookmark

/**
 * Chrome-style speed-dial grid: one tile per bookmark plus a trailing
 * "+" tile to add a new one. Tap opens the URL; long-press on a real
 * bookmark tile offers edit/delete (handled by the fragment via
 * [onLongPress]).
 */
class BookmarkAdapter(
    private val onTap: (Bookmark) -> Unit,
    private val onLongPress: (Bookmark) -> Unit,
    private val onAddTap: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var bookmarks: List<Bookmark> = emptyList()

    companion object {
        private const val VIEW_TYPE_BOOKMARK = 0
        private const val VIEW_TYPE_ADD = 1
    }

    fun submitList(items: List<Bookmark>) {
        bookmarks = items
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = bookmarks.size + 1

    override fun getItemViewType(position: Int): Int =
        if (position < bookmarks.size) VIEW_TYPE_BOOKMARK else VIEW_TYPE_ADD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_BOOKMARK) {
            BookmarkViewHolder(inflater.inflate(R.layout.item_bookmark_tile, parent, false))
        } else {
            AddTileViewHolder(inflater.inflate(R.layout.item_bookmark_add_tile, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is BookmarkViewHolder) {
            val bookmark = bookmarks[position]
            holder.title.text = bookmark.title
            holder.itemView.setOnClickListener { onTap(bookmark) }
            holder.itemView.setOnLongClickListener { onLongPress(bookmark); true }
        } else if (holder is AddTileViewHolder) {
            holder.itemView.setOnClickListener { onAddTap() }
        }
    }

    class BookmarkViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val favicon: ImageView = view.findViewById(R.id.tileFavicon)
        val title: TextView = view.findViewById(R.id.tileTitle)
    }

    class AddTileViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
