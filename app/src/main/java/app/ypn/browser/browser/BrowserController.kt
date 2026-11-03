package app.ypn.browser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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

/** One browser tab. Its WebView lives in the controller's map, not here. */
class BrowserTab(val id: Int) {
    var title by mutableStateOf("New Tab")
    var currentUrl by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)

    /** URL the WebView still has to load once it exists. */
    var pendingUrl: String? = null

    val isLoading: Boolean get() = progress in 1..99
}

/**
 * Owns all browser state for the app shell: the tab list, one lazily
 * created WebView per tab (kept in a map, destroyed when its tab
 * closes), bookmarks, history and the global desktop-mode flag.
 * Everything Compose reads is snapshot state, so the UI stays in sync
 * with WebView callbacks.
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
        newTab(url)
        browsing = true
    }

    fun selectTab(id: Int) {
        if (tabs.any { it.id == id }) {
            activeTabId = id
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
    }

    // ---------- WebView lifecycle ----------

    /**
     * Returns the tab's WebView, creating it (with all settings and
     * clients) on first use. Called from the browser screen's
     * AndroidView factory, so [context] is the Activity context.
     */
    fun obtainWebView(context: Context, tab: BrowserTab): WebView {
        webViews[tab.id]?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val wv = createWebView(context, tab)
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
    private fun createWebView(context: Context, tab: BrowserTab): WebView {
        val wv = WebView(context)
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
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url != null) tab.currentUrl = url
                tab.canGoBack = view?.canGoBack() == true
                tab.canGoForward = view?.canGoForward() == true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != null) tab.currentUrl = url
                view?.title?.takeIf { it.isNotBlank() }?.let { tab.title = it }
                tab.progress = 100
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
