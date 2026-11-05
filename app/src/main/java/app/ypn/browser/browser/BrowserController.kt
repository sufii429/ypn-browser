package app.ypn.browser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.ypn.browser.data.BrowserStore
import app.ypn.browser.data.SavedPage
import app.ypn.browser.download.DownloadController
import java.net.URLEncoder

/** What the v1.3 fetcher recognised in a page's requests. */
enum class MediaKind { VIDEO_FILE, HLS }

/**
 * One media candidate the fetcher sniffed on a tab's current page
 * (v1.3): a direct video file or an HLS playlist the page pulled
 * while loading/streaming. [pageUrl] is the page that referenced it
 * — later sent as the download's Referer.
 */
class MediaItem(
    val url: String,
    val kind: MediaKind,
    val pageUrl: String,
    val firstSeenAt: Long
)

/** A pending link long-press menu (v1.3), raised by a WebView. */
class LinkMenuRequest(val url: String, val pageUrl: String)

/** One browser tab. Its WebView lives in the controller's map, not here. */
class BrowserTab(val id: Int) {
    var title by mutableStateOf("New Tab")
    var currentUrl by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    /**
     * Media this tab's current page has pulled so far (v1.3
     * fetcher), in first-seen order, deduped by URL and capped —
     * see [BrowserController.recordMediaCandidate]. Reset when the
     * tab navigates to a different page: the list always describes
     * THIS page. Written from WebView request threads and read by
     * Compose, so every access is guarded by [mediaLock] (the list
     * itself is snapshot state, so the UI still recomposes).
     */
    val media = mutableStateListOf<MediaItem>()
    val mediaLock = Any()

    /** The page [media] belongs to; a different URL resets the list. */
    var mediaPageUrl: String? = null

    /**
     * True once this tab's first page has actually committed (painted
     * something). Until then the browser screen covers the still-blank
     * WebView with its loading UI (Phase 4 — the old first-navigation
     * "black screen"). Stays true for later navigations, which keep
     * using the toolbar progress line.
     */
    var committed by mutableStateOf(false)

    /** URL the WebView still has to load once it exists. */
    var pendingUrl: String? = null

    val isLoading: Boolean get() = progress in 1..99
}

/**
 * Owns all browser state for the app shell: the tab list, one WebView
 * per tab (kept in a map, destroyed when its tab closes — the first
 * tab's WebView is pre-warmed at app start since Phase 4, the rest
 * are created lazily), bookmarks, history and the global desktop-mode
 * flag. Everything Compose reads is snapshot state, so the UI stays
 * in sync with WebView callbacks. Since v1.3 it also carries the
 * per-tab media the fetcher sniffed (see [BrowserTab.media]) and
 * the pending link long-press menu ([linkMenu]).
 */
class BrowserController(context: Context, private val downloads: DownloadController) {

    private val store = BrowserStore(context.applicationContext)

    val tabs = mutableStateListOf<BrowserTab>()
    var activeTabId by mutableStateOf<Int?>(null)
        private set
    var browsing by mutableStateOf(false)

    val bookmarks = mutableStateListOf<SavedPage>()
    val history = mutableStateListOf<SavedPage>()

    var desktopMode by mutableStateOf(false)
        private set

    private val webViews = mutableMapOf<Int, WebView>()
    private var nextTabId = 1

    val activeTab: BrowserTab? get() = tabs.firstOrNull { it.id == activeTabId }

    /**
     * The link long-press menu waiting to be shown (v1.3), set by a
     * tab's WebView when a link (or image-link) is held; the browser
     * screen renders the sheet and clears this when it closes.
     */
    var linkMenu by mutableStateOf<LinkMenuRequest?>(null)

    /**
     * The user agent the WebViews are currently sending (v1.3),
     * captured whenever a WebView's settings are applied. Page-picked
     * downloads (fetcher, link menu) present this same UA so hosts
     * see the browser, not a bare HTTP client.
     */
    var lastUserAgent: String? = null
        private set

    init {
        bookmarks.addAll(store.loadBookmarks())
        history.addAll(store.loadHistory())
        desktopMode = store.desktopMode
    }

    // ---------- Tabs ----------

    fun newTab(url: String?): BrowserTab {
        val tab = BrowserTab(nextTabId++)
        if (!url.isNullOrBlank()) {
            tab.pendingUrl = url
            tab.currentUrl = url
        }
        tabs.add(tab)
        activeTabId = tab.id
        return tab
    }

    /** Opens raw address-bar text or a full URL in a fresh tab. */
    fun openInNewTab(raw: String) {
        val url = resolveInput(raw)
        if (url.isEmpty()) return
        val active = activeTab
        if (active != null && active.currentUrl.isEmpty()) {
            // The active tab is still untouched (e.g. the pre-warmed
            // first tab): navigate it instead of stacking an empty tab
            // beside the page the user actually asked for.
            active.pendingUrl = url
            active.currentUrl = url
            webViews[active.id]?.let {
                it.loadUrl(url)
                active.pendingUrl = null
            }
        } else {
            newTab(url)
        }
        browsing = true
    }

    fun selectTab(id: Int) {
        if (tabs.any { it.id == id }) {
            activeTabId = id
            browsing = true
        }
    }

    /**
     * Link menu's "Open in background tab" (v1.3): a fresh tab
     * loads [url] WITHOUT stealing focus — the active tab (and what
     * is on screen) stays exactly as it is. The new tab's WebView is
     * created lazily when the tab is first shown; until then the
     * URL waits in [BrowserTab.pendingUrl]. If somehow no tab is
     * active, the new tab becomes the active one instead.
     */
    fun openInBackgroundTab(url: String) {
        if (url.isBlank()) return
        val tab = BrowserTab(nextTabId++)
        tab.pendingUrl = url
        tab.currentUrl = url
        tabs.add(tab)
        if (activeTabId == null) {
            activeTabId = tab.id
            browsing = true
        }
    }

    fun closeTab(id: Int) {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return
        webViews.remove(id)?.let { destroyWebView(it) }
        val wasActive = activeTabId == id
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            activeTabId = null
            browsing = false
        } else if (wasActive) {
            activeTabId = tabs[minOf(index, tabs.lastIndex)].id
        }
    }

    // ---------- Media fetcher (v1.3) ----------

    /**
     * The fetcher's ear: called from the WebViewClient for every
     * subresource request a page makes. A request whose path (query
     * and fragment stripped, case folded) ends in a direct-video
     * extension is a [MediaKind.VIDEO_FILE]; an .m3u8 is
     * [MediaKind.HLS]. Segment files (.ts) and everything else are
     * ignored. First sight of a URL wins; the per-tab list is
     * capped at [MAX_MEDIA_PER_TAB]. Recording never touches the
     * response — the caller always returns null.
     */
    private fun recordMediaCandidate(tab: BrowserTab, url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val kind = when {
            path.endsWith(".mp4") || path.endsWith(".webm") ||
                path.endsWith(".mkv") -> MediaKind.VIDEO_FILE
            path.endsWith(".m3u8") -> MediaKind.HLS
            else -> return
        }
        synchronized(tab.mediaLock) {
            if (tab.media.any { it.url == url }) return
            if (tab.media.size >= MAX_MEDIA_PER_TAB) return
            tab.media.add(
                MediaItem(
                    url = url,
                    kind = kind,
                    pageUrl = tab.currentUrl,
                    firstSeenAt = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * The fetcher's page rule: the media list describes the tab's
     * CURRENT page, so a main-frame navigation to a different URL
     * empties it (a reload of the same URL keeps it). Called from
     * onPageStarted.
     */
    private fun resetMediaForPage(tab: BrowserTab, url: String) {
        if (tab.mediaPageUrl == url) return
        tab.mediaPageUrl = url
        synchronized(tab.mediaLock) { tab.media.clear() }
    }

    // ---------- Navigation ----------

    /** Loads raw address-bar text into the active tab (creating one if needed). */
    fun loadInActiveTab(raw: String) {
        val url = resolveInput(raw)
        if (url.isEmpty()) return
        val tab = activeTab ?: newTab(null)
        tab.pendingUrl = url
        tab.currentUrl = url
        webViews[tab.id]?.let {
            it.loadUrl(url)
            tab.pendingUrl = null
        }
        browsing = true
    }

    /** @return true if the active page went back in its own history. */
    fun goBackInPage(): Boolean {
        val wv = activeTab?.let { webViews[it.id] } ?: return false
        return if (wv.canGoBack()) {
            wv.goBack()
            true
        } else {
            false
        }
    }

    fun goForwardInPage() {
        val wv = activeTab?.let { webViews[it.id] } ?: return
        if (wv.canGoForward()) wv.goForward()
    }

    fun reload() {
        activeTab?.let { webViews[it.id]?.reload() }
    }

    fun stopLoading() {
        activeTab?.let { tab ->
            webViews[tab.id]?.stopLoading()
            tab.progress = 100
            // Whatever partial page exists replaces the loading cover.
            tab.committed = true
        }
    }

    // ---------- Bookmarks & history ----------

    fun isBookmarked(url: String): Boolean =
        url.isNotEmpty() && bookmarks.any { it.url == url }

    fun toggleBookmark() {
        val tab = activeTab ?: return
        val url = tab.currentUrl
        if (url.isEmpty()) return
        if (isBookmarked(url)) {
            bookmarks.removeAll { it.url == url }
        } else {
            bookmarks.add(0, SavedPage(tab.title.ifBlank { url }, url, System.currentTimeMillis()))
        }
        store.saveBookmarks(bookmarks)
    }

    fun removeBookmark(url: String) {
        bookmarks.removeAll { it.url == url }
        store.saveBookmarks(bookmarks)
    }

    fun clearHistory() {
        history.clear()
        store.saveHistory(history)
    }

    private fun recordHistory(tab: BrowserTab) {
        val url = tab.currentUrl
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val entry = SavedPage(tab.title.ifBlank { url }, url, System.currentTimeMillis())
        if (history.firstOrNull()?.url == url) {
            history[0] = entry
        } else {
            history.add(0, entry)
            while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        }
        store.saveHistory(history)
    }

    // ---------- Desktop mode ----------

    fun applyDesktopMode(enabled: Boolean) {
        desktopMode = enabled
        store.desktopMode = enabled
        webViews.values.forEach { wv ->
            applyDesktopSettings(wv)
            wv.reload()
        }
    }

    private fun applyDesktopSettings(wv: WebView) {
        wv.settings.userAgentString = if (desktopMode) DESKTOP_UA else null
        // Remember what the WebViews actually send (v1.3): downloads
        // picked out of a page present this same user agent.
        lastUserAgent = wv.settings.userAgentString
    }

    // ---------- WebView lifecycle ----------

    /**
     * Phase 4 pre-warm: create the first tab and its WebView right
     * away (the app shell calls this on first composition, while the
     * Home screen is still showing) so Chromium's slow one-time
     * initialization is already done when the user first navigates.
     * The WebView created here IS the first tab's real WebView —
     * [obtainWebView] hands it back when the browser screen composes —
     * and it is destroyed with its tab like any other. Additional tabs
     * keep the lazy creation below. No-op once any tab exists.
     */
    fun warmUp(context: Context, backgroundColor: Int) {
        if (tabs.isNotEmpty()) return
        val tab = newTab(null)
        obtainWebView(context, tab, backgroundColor)
    }

    /**
     * Returns the tab's WebView, creating it (with all settings and
     * clients) on first use. Called from the browser screen's
     * AndroidView factory, so [context] is the Activity context.
     * [backgroundColor] (the theme surface colour) is painted behind
     * the page so a loading WebView never flashes black or white.
     */
    fun obtainWebView(context: Context, tab: BrowserTab, backgroundColor: Int): WebView {
        webViews[tab.id]?.let { existing ->
            existing.setBackgroundColor(backgroundColor)
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val wv = createWebView(context, tab, backgroundColor)
        webViews[tab.id] = wv
        val toLoad = tab.pendingUrl ?: tab.currentUrl.takeIf { it.isNotEmpty() }
        if (toLoad != null) {
            tab.pendingUrl = null
            wv.loadUrl(toLoad)
        }
        return wv
    }

    private fun destroyWebView(wv: WebView) {
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.stopLoading()
        wv.webChromeClient = null
        wv.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, tab: BrowserTab, backgroundColor: Int): WebView {
        val wv = WebView(context)
        // Theme surface behind the page: no black/white flash while
        // the first paint is still on its way (Phase 4).
        wv.setBackgroundColor(backgroundColor)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Pop-up blocking: no multiple windows, no JS-opened windows,
            // and onCreateWindow below refuses every new-window request.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        applyDesktopSettings(wv)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                // v1.3 fetcher: watch (never touch) what the page
                // pulls. Main-frame requests ARE the page, not its
                // media, so they are skipped. Returning null lets
                // the WebView load every request exactly as before.
                if (request?.isForMainFrame != true) {
                    request?.url?.toString()?.let { recordMediaCandidate(tab, it) }
                }
                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null) {
                    tab.currentUrl = url
                    // A new page means a new media list (fetcher).
                    resetMediaForPage(tab, url)
                }
                tab.canGoBack = view?.canGoBack() == true
                tab.canGoForward = view?.canGoForward() == true
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                // First paint: the browser screen's loading cover can
                // go away — something is actually on screen now.
                tab.committed = true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // A failed first load still replaces the blank view
                // (with WebView's error page) — drop the cover too.
                if (request?.isForMainFrame == true) tab.committed = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) tab.currentUrl = url
                view?.title?.takeIf { it.isNotBlank() }?.let { tab.title = it }
                tab.progress = 100
                // Fallback for pages where the commit-visible callback
                // is late or never delivered.
                tab.committed = true
                tab.canGoBack = view?.canGoBack() == true
                tab.canGoForward = view?.canGoForward() == true
                recordHistory(tab)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) return false
                // tel:, mailto:, intent: etc. — hand to the system, never load here.
                return try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                } catch (e: Exception) {
                    true
                }
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                tab.progress = newProgress
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                if (!title.isNullOrBlank()) tab.title = title
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean = false // pop-ups stay blocked
        }
        // v1.3 link menu: holding a link — or an image wrapped in a
        // link — raises the link sheet (the browser screen renders
        // it from [linkMenu]). Any other hit is NOT consumed
        // (returns false), so long-press text selection and other
        // page gestures keep their stock behaviour.
        wv.setOnLongClickListener {
            val hit = wv.hitTestResult
            when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    val url = hit.extra
                    if (!url.isNullOrBlank()) {
                        linkMenu = LinkMenuRequest(url, tab.currentUrl)
                        true
                    } else {
                        false
                    }
                }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    // Here hit.extra is the IMAGE url; the menu wants
                    // the wrapping link's href, which the WebView
                    // reports through requestFocusNodeHref ("url" in
                    // the reply data; "src" is the image itself). If
                    // no href comes back, the image url stands in.
                    try {
                        val reply = Message()
                        reply.target = Handler(Looper.getMainLooper()) { msg ->
                            val found = msg.data.getString("url")
                                ?: msg.data.getString("src")
                                ?: hit.extra
                            if (!found.isNullOrBlank()) {
                                linkMenu = LinkMenuRequest(found, tab.currentUrl)
                            }
                            true
                        }
                        wv.requestFocusNodeHref(reply)
                        true
                    } catch (e: Exception) {
                        val url = hit.extra
                        if (!url.isNullOrBlank()) {
                            linkMenu = LinkMenuRequest(url, tab.currentUrl)
                            true
                        } else {
                            false
                        }
                    }
                }
                else -> false
            }
        }
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (url.isNullOrBlank()) return@setDownloadListener
            // Session cookies travel with the download so logged-in
            // file links keep working in the download engine.
            val cookie = try {
                CookieManager.getInstance().getCookie(url)
            } catch (e: Exception) {
                null
            }
            downloads.enqueueFromBrowser(url, userAgent, contentDisposition, mimeType, cookie)
            Toast.makeText(
                context,
                "Download started",
                Toast.LENGTH_SHORT
            ).show()
        }
        return wv
    }

    companion object {
        private const val MAX_HISTORY = 500

        /** Fetcher cap: at most this many media candidates per page. */
        private const val MAX_MEDIA_PER_TAB = 30

        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * Address-bar logic: a full URL passes through, a bare domain
         * gets https:// in front, anything else becomes a Google search.
         */
        fun resolveInput(raw: String): String {
            val input = raw.trim()
            if (input.isEmpty()) return ""
            if (input.startsWith("http://") || input.startsWith("https://")) return input
            val hostPart = input.substringBefore('/').substringBefore('?').substringBefore('#')
            val labels = hostPart.split('.')
            val looksLikeHost = !input.contains(' ') &&
                labels.size >= 2 &&
                labels.all { it.isNotEmpty() } &&
                labels.last().length >= 2
            return if (looksLikeHost || hostPart == "localhost") {
                "https://$input"
            } else {
                "https://www.google.com/search?q=" + URLEncoder.encode(input, "UTF-8")
            }
        }
    }
}
