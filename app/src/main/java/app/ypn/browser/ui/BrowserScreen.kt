package app.ypn.browser.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
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
import app.ypn.browser.ui.theme.LocalYpnPalette

/**
 * The real browsing view (Phase 1): toolbar (back / forward / address
 * bar / reload-stop / bookmark star / home), a loading progress line,
 * and the active tab's WebView underneath. Since Phase 4 the WebView
 * is painted over the theme surface colour, and until a tab's first
 * page commits, [InitialLoadOverlay] covers it with a real loading
 * state instead of a blank black screen.
 */
@Composable
fun BrowserScreen(
    controller: BrowserController,
    onHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val surfaceArgb = colors.surface.toArgb()
    val tab = controller.activeTab

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        BrowserToolbar(controller = controller, tab = tab, onHome = onHome)
        if (tab != null && tab.isLoading) {
            LinearProgressIndicator(
                progress = { tab.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
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
    onHome: () -> Unit
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
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(12.dp)
            )
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
