package com.utsav.ffdownloader.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.utsav.ffdownloader.R
import com.utsav.ffdownloader.core.Bookmark
import com.utsav.ffdownloader.core.BookmarkRepository
import com.utsav.ffdownloader.core.LinkParser
import com.utsav.ffdownloader.core.SuggestApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Mini in-app browser: address bar + WebView, with a Chrome-style
 * speed-dial grid shown in place of the WebView on "new tab" (i.e.
 * whenever there's no URL loaded). Typing in the address bar shows
 * generic DuckDuckGo suggest results (see SuggestApi) -- no site list is
 * bundled with this app. Auto-detects fuckingfast/fitgirl links on the
 * current page and surfaces a FAB to send them to the Home download
 * queue; also intercepts any file download the page itself triggers
 * (WebView's native download signal) behind a confirm dialog.
 */
class BrowserFragment : Fragment() {

    interface Callbacks {
        /** Same handoff HomeFragment uses for pasted links -- expands + queues + resolves. */
        fun triggerPrepare(lines: List<String>)
    }

    private lateinit var urlInput: EditText
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var reloadButton: ImageButton
    private lateinit var pageProgress: ProgressBar
    private lateinit var webView: WebView
    private lateinit var speedDialContainer: View
    private lateinit var speedDialGrid: RecyclerView
    private lateinit var addLinkFab: FloatingActionButton
    private lateinit var suggestionsCard: MaterialCardView
    private lateinit var suggestionsList: RecyclerView

    private lateinit var adapter: BookmarkAdapter
    private lateinit var suggestionAdapter: SuggestionAdapter
    private var lastDetectedLink: String? = null
    private var suggestJob: Job? = null

    // Own client instead of reusing MainActivity's -- this is a short-timeout,
    // fire-and-forget lookup that shouldn't share connection pool pressure
    // with the resolve/download clients.
    private val suggestClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_browser, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        urlInput = view.findViewById(R.id.urlInput)
        backButton = view.findViewById(R.id.backButton)
        forwardButton = view.findViewById(R.id.forwardButton)
        reloadButton = view.findViewById(R.id.reloadButton)
        pageProgress = view.findViewById(R.id.pageProgress)
        webView = view.findViewById(R.id.webView)
        speedDialContainer = view.findViewById(R.id.speedDialContainer)
        speedDialGrid = view.findViewById(R.id.speedDialGrid)
        addLinkFab = view.findViewById(R.id.addLinkFab)
        suggestionsCard = view.findViewById(R.id.suggestionsCard)
        suggestionsList = view.findViewById(R.id.suggestionsList)

        setupWebView()
        setupSpeedDial()
        setupAddressBar()
        setupSuggestions()

        addLinkFab.setOnClickListener { onAddLinkClicked() }

        // Start on the speed-dial ("new tab") page.
        showSpeedDial()
    }

    // ── WebView ──────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            onWebViewDownloadRequested(url, contentDisposition, mimeType)
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                pageProgress.visibility = View.VISIBLE
                pageProgress.progress = 0
                url?.let { urlInput.setText(it) }
                clearDetectedLink()
                updateNavButtons()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                pageProgress.visibility = View.GONE
                updateNavButtons()
                url?.let { checkPageForLinks(it) }
            }
        }
        webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                pageProgress.progress = newProgress
            }
        }
    }

    private fun updateNavButtons() {
        backButton.isEnabled = webView.canGoBack()
        forwardButton.isEnabled = webView.canGoForward()
    }

    /** Called by MainActivity to consume system back presses while a page is loaded. */
    fun onBackPressed(): Boolean {
        if (webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

    // ── Address bar ──────────────────────────────────────────────────────

    private fun setupAddressBar() {
        backButton.setOnClickListener { webView.goBack() }
        forwardButton.setOnClickListener { webView.goForward() }
        reloadButton.setOnClickListener { webView.reload() }

        urlInput.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isGo) {
                loadUrl(urlInput.text?.toString().orEmpty())
                true
            } else false
        }

        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!urlInput.hasFocus()) return // programmatic sets (e.g. onPageStarted) shouldn't trigger suggest
                scheduleSuggest(s?.toString().orEmpty())
            }
        })

        urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) hideSuggestions()
        }
    }

    private fun setupSuggestions() {
        suggestionAdapter = SuggestionAdapter(
            onTap = { phrase -> urlInput.setText(phrase); loadUrl(phrase) },
            onAddTap = { phrase ->
                val url = normalizeToUrl(phrase)
                BookmarkRepository.add(title = phrase, url = url)
                Toast.makeText(requireContext(), R.string.bookmark_added_toast, Toast.LENGTH_SHORT).show()
            }
        )
        suggestionsList.layoutManager = LinearLayoutManager(requireContext())
        suggestionsList.adapter = suggestionAdapter
    }

    /**
     * 2-3 letters is enough to start querying, debounced ~300ms so we're not
     * firing a network request on every keystroke. Query text and results
     * come entirely from DuckDuckGo's public suggest endpoint -- nothing
     * here is a list this app ships or maintains (see SuggestApi's doc).
     */
    private fun scheduleSuggest(query: String) {
        suggestJob?.cancel()
        if (query.trim().length < 2) {
            hideSuggestions()
            return
        }
        suggestJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(300)
            val results = withContext(Dispatchers.IO) { SuggestApi.suggest(query, suggestClient) }
            if (!isAdded) return@launch
            if (results.isEmpty()) {
                hideSuggestions()
            } else {
                suggestionAdapter.submitList(results)
                suggestionsCard.visibility = View.VISIBLE
            }
        }
    }

    private fun hideSuggestions() {
        suggestJob?.cancel()
        suggestionsCard.visibility = View.GONE
    }

    private fun loadUrl(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        val url = normalizeToUrl(input)
        hideSuggestions()
        showWebView()
        webView.loadUrl(url)
        // Drop keyboard focus so the address bar doesn't stay expanded.
        urlInput.clearFocus()
        val imm = requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    /** Bare host/search text -> https URL; anything already URL-shaped is passed through. */
    private fun normalizeToUrl(input: String): String {
        val looksLikeUrl = input.contains(".") && !input.contains(" ")
        return when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            looksLikeUrl -> "https://$input"
            else -> "https://www.google.com/search?q=${android.net.Uri.encode(input)}"
        }
    }

    // ── Speed dial (new tab) ─────────────────────────────────────────────

    private fun setupSpeedDial() {
        adapter = BookmarkAdapter(
            onTap = { bookmark -> urlInput.setText(bookmark.url); loadUrl(bookmark.url) },
            onLongPress = { bookmark -> showBookmarkOptionsDialog(bookmark) },
            onAddTap = { showAddBookmarkDialog(prefillUrl = null) }
        )
        speedDialGrid.layoutManager = GridLayoutManager(requireContext(), 4)
        speedDialGrid.adapter = adapter

        BookmarkRepository.bookmarks.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
        }
    }

    private fun showSpeedDial() {
        speedDialContainer.visibility = View.VISIBLE
        webView.visibility = View.GONE
        urlInput.setText("")
        hideSuggestions()
        clearDetectedLink()
        backButton.isEnabled = webView.canGoBack()
        forwardButton.isEnabled = webView.canGoForward()
    }

    private fun showWebView() {
        speedDialContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    private fun showAddBookmarkDialog(prefillUrl: String?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_bookmark, null)
        val titleInput = dialogView.findViewById<EditText>(R.id.bookmarkTitleInput)
        val urlField = dialogView.findViewById<EditText>(R.id.bookmarkUrlInput)
        urlField.setText(prefillUrl ?: webView.url.takeIf { webView.visibility == View.VISIBLE })

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.add_bookmark_title)
            .setView(dialogView)
            .setPositiveButton(R.string.action_add) { _, _ ->
                val url = urlField.text?.toString()?.trim().orEmpty()
                if (url.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.bookmark_needs_url, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val normalized = normalizeToUrl(url)
                BookmarkRepository.add(titleInput.text?.toString()?.trim().orEmpty(), normalized)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBookmarkOptionsDialog(bookmark: Bookmark) {
        AlertDialog.Builder(requireContext())
            .setTitle(bookmark.title)
            .setItems(arrayOf(getString(R.string.edit_bookmark_title), getString(R.string.action_delete))) { _, which ->
                when (which) {
                    0 -> showEditBookmarkDialog(bookmark)
                    1 -> BookmarkRepository.remove(bookmark)
                }
            }
            .show()
    }

    private fun showEditBookmarkDialog(bookmark: Bookmark) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_bookmark, null)
        val titleInput = dialogView.findViewById<EditText>(R.id.bookmarkTitleInput)
        val urlField = dialogView.findViewById<EditText>(R.id.bookmarkUrlInput)
        titleInput.setText(bookmark.title)
        urlField.setText(bookmark.url)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.edit_bookmark_title)
            .setView(dialogView)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                val url = urlField.text?.toString()?.trim().orEmpty()
                if (url.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.bookmark_needs_url, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                BookmarkRepository.remove(bookmark)
                BookmarkRepository.add(titleInput.text?.toString()?.trim().orEmpty(), normalizeToUrl(url))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Link auto-detect ─────────────────────────────────────────────────

    /**
     * Fires for ANY download the WebView's content triggers -- an <a
     * download> click, a redirect to a file with a Content-Disposition
     * header, or navigation straight to a file mimetype (apk/zip/mp4/pdf/
     * etc). This is a completely different path from checkPageForLinks:
     * that one watches the page's own URL for fuckingfast/fitgirl links
     * (site-specific, auto-shows a FAB); this one catches the browser's
     * native "start a download" signal for arbitrary files from any site.
     * Always confirms before queuing since it fires on real clicks, not
     * just heuristics.
     */
    private fun onWebViewDownloadRequested(url: String, contentDisposition: String?, mimeType: String?) {
        val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.download_confirm_title)
            .setMessage(getString(R.string.download_confirm_message, fileName))
            .setPositiveButton(R.string.action_add_to_downloads) { _, _ ->
                (activity as? Callbacks)?.triggerPrepare(listOf(url))
                Toast.makeText(requireContext(), R.string.link_found_toast, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Cheap, synchronous check against the page's own URL first (covers the
     * common case: user navigated straight to a share link or a
     * fitgirl-repacks post). We don't scrape the rendered DOM for
     * further off-URL share links here -- LinkParser.expandSources already
     * does that server-side (via Jsoup) once the link is handed to
     * triggerPrepare, so re-implementing it against WebView's DOM would be
     * redundant.
     */
    private fun checkPageForLinks(url: String) {
        if (LinkParser.isShareLink(url) || LinkParser.isFitgirlPage(url)) {
            lastDetectedLink = url
            addLinkFab.visibility = View.VISIBLE
        } else {
            clearDetectedLink()
        }
    }

    private fun clearDetectedLink() {
        lastDetectedLink = null
        addLinkFab.visibility = View.GONE
    }

    private fun onAddLinkClicked() {
        val link = lastDetectedLink ?: return
        (activity as? Callbacks)?.triggerPrepare(listOf(link))
        Toast.makeText(requireContext(), R.string.link_found_toast, Toast.LENGTH_LONG).show()
        clearDetectedLink()
    }
}
