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
import com.invictus.xmd.core.HistoryEntry
import com.invictus.xmd.core.HistoryRepository
import com.invictus.xmd.ui.theme.XmdTheme

/** Browser tab visited-page history: list, swipe-to-delete, Clear all, tap
 *  to reopen, and an in-memory search box that filters the currently loaded
 *  entries by title/URL. Same shape as BookmarkFragment; both render via
 *  the shared [SavedPagesScreen]/[SavedPageRow] composables.
 *
 *  Rendering moved to Compose ([HistoryScreen]); this Fragment hosts a
 *  [ComposeView] instead of inflating fragment_history.xml. */
class HistoryFragment : Fragment() {

    interface Callbacks {
        /** Reopens the given URL in the Browser tab. */
        fun openInBrowser(url: String)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            // Full, unfiltered set as last delivered by HistoryRepository --
            // the source of truth the search box filters against.
            val allEntries by HistoryRepository.entries.collectAsStateWithLifecycle()
            var query by remember { mutableStateOf("") }
            var confirmingClearAll by remember { mutableStateOf(false) }

            val trimmedQuery = query.trim()
            val visible = if (trimmedQuery.isEmpty()) {
                allEntries
            } else {
                allEntries.filter { entry ->
                    entry.title.contains(trimmedQuery, ignoreCase = true) ||
                        entry.url.contains(trimmedQuery, ignoreCase = true)
                }
            }

            XmdTheme {
                HistoryScreen(
                    entries = visible,
                    query = query,
                    onQueryChange = { query = it },
                    onBack = { parentFragmentManager.popBackStack() },
                    onClearAll = { confirmingClearAll = true },
                    onTap = { entry: HistoryEntry ->
                        (activity as? Callbacks)?.openInBrowser(entry.url)
                        parentFragmentManager.popBackStack()
                    },
                    onDelete = { entry: HistoryEntry -> HistoryRepository.remove(entry) },
                )

                if (confirmingClearAll) {
                    AlertDialog(
                        onDismissRequest = { confirmingClearAll = false },
                        title = { Text(stringResource(R.string.history_clear_all)) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmingClearAll = false
                                HistoryRepository.clearAll()
                                Toast.makeText(requireContext(), R.string.history_cleared_toast, Toast.LENGTH_SHORT).show()
                            }) {
                                Text(stringResource(R.string.history_clear_all))
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
