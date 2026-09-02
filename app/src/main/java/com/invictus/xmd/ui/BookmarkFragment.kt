package com.invictus.xmd.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.invictus.xmd.R
import com.invictus.xmd.core.Bookmark
import com.invictus.xmd.core.BookmarkRepository
import com.invictus.xmd.ui.theme.XmdTheme

/** Saved-pages screen for real bookmarks (star button in the Browser
 *  toolbar) -- list, swipe-to-delete, Clear all, tap to reopen, and an
 *  in-memory search box. Same shape as HistoryFragment; both render via the
 *  shared [SavedPagesScreen]/[SavedPageRow] composables.
 *
 *  Rendering moved to Compose ([BookmarkScreen]); this Fragment hosts a
 *  [ComposeView] instead of inflating fragment_bookmarks.xml. */
class BookmarkFragment : Fragment() {

    interface Callbacks {
        /** Reopens the given URL in the Browser tab. */
        fun openBookmarkInBrowser(url: String)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            // Full, unfiltered set as last delivered by BookmarkRepository --
            // the source of truth the search box filters against.
            val allBookmarks by BookmarkRepository.bookmarks.collectAsStateWithLifecycle()
            var query by remember { mutableStateOf("") }
            var confirmingClearAll by remember { mutableStateOf(false) }

            val trimmedQuery = query.trim()
            val visible = if (trimmedQuery.isEmpty()) {
                allBookmarks
            } else {
                allBookmarks.filter { entry ->
                    entry.title.contains(trimmedQuery, ignoreCase = true) ||
                        entry.url.contains(trimmedQuery, ignoreCase = true)
                }
            }

            XmdTheme {
                BookmarkScreen(
                    entries = visible,
                    query = query,
                    onQueryChange = { query = it },
                    onBack = { parentFragmentManager.popBackStack() },
                    onClearAll = { confirmingClearAll = true },
                    onTap = { bookmark: Bookmark ->
                        (activity as? Callbacks)?.openBookmarkInBrowser(bookmark.url)
                        parentFragmentManager.popBackStack()
                    },
                    onDelete = { bookmark: Bookmark -> BookmarkRepository.remove(bookmark) },
                )

                if (confirmingClearAll) {
                    AlertDialog(
                        onDismissRequest = { confirmingClearAll = false },
                        title = { Text(stringResource(R.string.bookmarks_clear_all)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmingClearAll = false
                                BookmarkRepository.clearAll()
                                Toast.makeText(requireContext(), R.string.bookmarks_cleared_toast, Toast.LENGTH_SHORT).show()
                            }) {
                                Text(stringResource(R.string.bookmarks_clear_all))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmingClearAll = false }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        },
                    )
                }
            }
        }
    }
}
