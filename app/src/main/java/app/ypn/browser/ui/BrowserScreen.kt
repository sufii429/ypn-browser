package app.ypn.browser.ui

import android.net.Uri
import android.webkit.CookieManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.ypn.browser.browser.BrowserController
import app.ypn.browser.browser.BrowserTab
import app.ypn.browser.download.DownloadController
import app.ypn.browser.ui.theme.LocalYpnPalette
import app.ypn.browser.vpn.VpnController

/**
 * The real browsing view (Phase 1): toolbar (back / forward / address
 * bar / reload-stop / bookmark star / home), a loading progress line,
 * and the active tab's WebView underneath. Since Phase 4 the WebView
 * is painted over the theme surface colour, and until a tab's first
 * page commits, [InitialLoadOverlay] covers it with a real loading
 * state instead of a blank black screen. v1.1 added the Pawxy-style
 * VPN chip at the address bar's right end (it opens the VPN bottom
 * sheet, see VpnSheet.kt). v1.2 ("Mazedaar") makes page loads show
 * themselves, Vivaldi-style: a small spinner turns at the left of
 * the address bar while the active tab loads, and the progress line
 * under the toolbar is a thin 3dp line — both read the tab's real
 * [BrowserTab.isLoading] / [BrowserTab.progress] state, which the
 * controller feeds from the WebView's own callbacks.
 *
 * v1.3 additions: when the active tab's page has pulled video, a
 * fetcher button (download arrow + orange count badge) sits inside
 * the address bar just before the VPN chip and opens the media
 * sheet; and the link long-press sheet the controller raises is
 * hosted here, above the page. Both sheets live in MediaSheet.kt.
 */
@Composable
fun BrowserScreen(
    controller: BrowserController,
    onHome: () -> Unit,
    vpn: VpnController,
    onVpnSheetOpen: () -> Unit,
    downloads: DownloadController,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val surfaceArgb = colors.surface.toArgb()
    val tab = controller.activeTab
    val context = LocalContext.current
    var mediaSheetOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        BrowserToolbar(
            controller = controller,
            tab = tab,
            onHome = onHome,
            vpn = vpn,
            onVpnSheetOpen = onVpnSheetOpen,
            onMediaClick = { mediaSheetOpen = true }
        )
        if (tab != null && tab.isLoading) {
            // Directly under the toolbar's address bar: a thin (3dp,
            // v1.2) line filled with the tab's real load progress.
            // isLoading is progress in 1..99, so the line hides itself
            // the moment the page finishes.
            LinearProgressIndicator(
                progress = { tab.progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = colors.primary,
                trackColor = LocalYpnPalette.current.surface2
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == null || tab.currentUrl.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Type a URL or search in the bar above.",
                        color = LocalYpnPalette.current.muted,
                        fontSize = 13.sp
                    )
                }
            } else {
                // One AndroidView per tab id; the controller keeps the
                // WebView itself alive across tab switches.
                key(tab.id) {
                    AndroidView(
                        factory = { ctx -> controller.obtainWebView(ctx, tab, surfaceArgb) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (!tab.committed) {
                    // The tab's first page has not painted anything
                    // yet: show where it is going and how far it got
                    // instead of the still-blank WebView.
                    InitialLoadOverlay(url = tab.currentUrl, progress = tab.progress)
                }
            }
        }
    }

    // v1.3 fetcher sheet: the streams the active tab's page pulled.
    // Downloads go through the engine carrying the page as referer,
    // the browser's UA and the file host's session cookie.
    if (mediaSheetOpen && tab != null) {
        MediaSheet(
            tab = tab,
            userAgent = controller.lastUserAgent,
            onDismissRequest = { mediaSheetOpen = false },
            onDownload = { item ->
                downloads.enqueueMedia(
                    url = item.url,
                    referer = item.pageUrl.ifBlank { null },
                    userAgent = controller.lastUserAgent,
                    cookie = runCatching {
                        CookieManager.getInstance().getCookie(item.url)
                    }.getOrNull()
                )
                Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
                mediaSheetOpen = false
            },
            onDownloadHls = { url, quality, referer ->
                // v1.3.2: a picked HLS variant — the engine joins
                // its segments into one MPEG-TS file, hence .ts.
                downloads.enqueueHls(
                    url = url,
                    suggestedName = "video_" + (quality ?: "stream") + ".ts",
                    referer = referer.ifBlank { null },
                    userAgent = controller.lastUserAgent,
                    cookie = runCatching {
                        CookieManager.getInstance().getCookie(url)
                    }.getOrNull()
                )
                Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
                mediaSheetOpen = false
            }
        )
    }

    // v1.3 link long-press sheet, raised by a tab's WebView hit test.
    controller.linkMenu?.let { request ->
        LinkMenuSheet(
            request = request,
            onDismissRequest = { controller.linkMenu = null },
            onOpenInNewTab = {
                controller.linkMenu = null
                controller.newTab(request.url)
            },
            onOpenInBackgroundTab = {
                controller.linkMenu = null
                controller.openInBackgroundTab(request.url)
            },
            onDownloadLink = {
                controller.linkMenu = null
                downloads.enqueueMedia(
                    url = request.url,
                    referer = request.pageUrl.ifBlank { null },
                    userAgent = controller.lastUserAgent,
                    cookie = runCatching {
                        CookieManager.getInstance().getCookie(request.url)
                    }.getOrNull()
                )
                Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

/**
 * First-load cover for a tab (Phase 4): shown over the theme surface
 * until the tab's first page commits, so a cold WebView never looks
 * like a dead black screen. [progress] is the tab's real load
 * progress (WebChromeClient), shown as a bar once it starts moving.
 */
@Composable
private fun InitialLoadOverlay(url: String, progress: Int) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    val host = remember(url) {
        Uri.parse(url).host?.takeIf { it.isNotBlank() } ?: url
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = colors.primary)
            Text(
                text = "Loading $host…",
                color = colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp)
            )
            Text(
                text = url,
                color = palette.muted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 3.dp)
            )
            if (progress > 0) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .width(180.dp)
                        .padding(top = 14.dp),
                    color = colors.primary,
                    trackColor = palette.surface2
                )
            }
        }
    }
}

@Composable
private fun BrowserToolbar(
    controller: BrowserController,
    tab: BrowserTab?,
    onHome: () -> Unit,
    vpn: VpnController,
    onVpnSheetOpen: () -> Unit,
    onMediaClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    val focusManager = LocalFocusManager.current

    var editing by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    val currentUrl = tab?.currentUrl ?: ""
    val bookmarked = tab != null && controller.isBookmarked(currentUrl)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { controller.goBackInPage() },
            enabled = tab?.canGoBack == true,
            modifier = Modifier.size(38.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = if (tab?.canGoBack == true) colors.onSurface else palette.muted
            )
        }
        IconButton(
            onClick = { controller.goForwardInPage() },
            enabled = tab?.canGoForward == true,
            modifier = Modifier.size(38.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Forward",
                tint = if (tab?.canGoForward == true) colors.onSurface else palette.muted
            )
        }

        // Address bar: shows the live URL when idle, the typed text while editing.
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.background)
                .border(1.dp, colors.outline, RoundedCornerShape(999.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (tab?.isLoading == true) {
                // v1.2: while the page loads, a small spinner turns
                // where the lock sits — the load is visible from
                // inside the bar, not just under it.
                CircularProgressIndicator(
                    color = colors.primary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(12.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            BasicTextField(
                value = if (editing) editText else currentUrl,
                onValueChange = {
                    editing = true
                    editText = it
                },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            editing = true
                            editText = currentUrl
                        } else {
                            editing = false
                        }
                    },
                textStyle = TextStyle(color = colors.onSurface, fontSize = 13.sp),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    autoCorrectEnabled = false
                ),
                keyboardActions = KeyboardActions(
                    onGo = {
                        controller.loadInActiveTab(editText)
                        editing = false
                        focusManager.clearFocus()
                    }
                ),
                decorationBox = { innerField ->
                    Box {
                        if ((if (editing) editText else currentUrl).isEmpty()) {
                            Text(
                                text = "Search or type a URL",
                                color = palette.muted,
                                fontSize = 13.sp
                            )
                        }
                        innerField()
                    }
                }
            )
            Spacer(Modifier.width(6.dp))
            if (tab != null && tab.media.isNotEmpty()) {
                // v1.3 fetcher: the page pulled video — the count
                // badge wears the mockup's orange, on purpose not a
                // palette colour (it is a "found something" signal,
                // not chrome). Tapping opens the media sheet.
                IconButton(
                    onClick = onMediaClick,
                    modifier = Modifier.size(30.dp)
                ) {
                    Box {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Videos on this page",
                            tint = colors.primary,
                            modifier = Modifier.size(17.dp)
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 8.dp, y = (-6).dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color(0xFFFB923C))
                                .padding(horizontal = 4.dp)
                        ) {
                            Text(
                                text = tab.media.size.toString(),
                                color = Color(0xFF231203),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))
            }
            VpnChip(vpn = vpn, onClick = onVpnSheetOpen)
        }

        IconButton(
            onClick = {
                if (tab?.isLoading == true) controller.stopLoading() else controller.reload()
            },
            modifier = Modifier.size(38.dp)
        ) {
            Icon(
                imageVector = if (tab?.isLoading == true) Icons.Filled.Close else Icons.Filled.Refresh,
                contentDescription = if (tab?.isLoading == true) "Stop" else "Reload",
                tint = colors.onSurface
            )
        }
        IconButton(
            onClick = { controller.toggleBookmark() },
            enabled = currentUrl.isNotEmpty(),
            modifier = Modifier.size(38.dp)
        ) {
            Icon(
                imageVector = if (bookmarked) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = "Bookmark",
                tint = if (bookmarked) colors.primary else colors.onSurface
            )
        }
        IconButton(
            onClick = onHome,
            modifier = Modifier.size(38.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Home,
                contentDescription = "Home",
                tint = colors.onSurface
            )
        }
    }
}
