package app.ypn.browser.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ypn.browser.browser.BrowserController
import app.ypn.browser.browser.BrowserTab
import app.ypn.browser.download.DownloadController
import app.ypn.browser.ui.theme.LocalYpnPalette
import app.ypn.browser.ui.theme.YpnThemeId
import app.ypn.browser.vpn.VpnController
import app.ypn.browser.vpn.VpnServer

private enum class YpnSection(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    TABS("Tabs", Icons.Filled.Tab),
    DOWNLOADS("Downloads", Icons.Filled.Download),
    MENU("Menu", Icons.Filled.MoreVert)
}

/**
 * App shell: the mockups' bottom navigation (Home / Tabs / Downloads
 * with badge / Menu) around the screens. Since Phase 1 the Home section
 * swaps between the home screen and the live browser view, Tabs is the
 * real tab list, and Menu holds bookmarks / history / desktop mode /
 * theme. Since Phase 2 the Home section can also show the VPN screen
 * (VPN Gate server list + tunnel status), and this shell owns the
 * Android VPN-consent launcher the VPN flow needs. Since Phase 3 the
 * Downloads section is the real download manager, fed by the
 * process-wide DownloadController singleton (shared with the download
 * foreground service), and the Downloads badge counts live downloads.
 * Since Phase 4 the first tab's WebView is pre-warmed at first
 * composition (see [BrowserController.warmUp]), Home no longer
 * carries the theme picker (Menu is the single settings home), and
 * VPN quick-connect prefers a server remembered as working.
 * Since v1.1 this shell also hosts the shared VPN bottom sheet
 * (VpnSheet), opened from the VPN chips on Home and in the browser
 * toolbar.
 * Since v1.3 the tabs live at the TOP: a scrolling strip of tab
 * chips tops the Home and Browser sections (see [TabStrip]), the
 * bottom navigation is Home / Downloads / Menu only, and the Tabs
 * grid remains as its own section, reached from the strip's grid
 * button — its TABS enum entry stays for that, it is simply no
 * longer a bottom-nav destination. Since v1.3.2 the strip sits in
 * the Scaffold's topBar slot rather than inside the Home section's
 * own layout: while it lived in the content column, a page's
 * first-load window could cover it (and the browser toolbar) until
 * the page committed; as chrome owned by the Scaffold it is
 * structurally out of the page's reach.
 */
@Composable
fun YpnApp(themeId: YpnThemeId, onThemeChange: (YpnThemeId) -> Unit) {
    val context = LocalContext.current
    val downloads = remember { DownloadController.get(context) }
    val controller = remember { BrowserController(context, downloads) }
    val vpn = remember { VpnController(context) }
    var selected by remember { mutableStateOf(YpnSection.HOME) }
    var showVpn by remember { mutableStateOf(false) }
    // v1.1: the VPN bottom sheet (VpnSheet) is hosted here at the
    // root so the VPN chips on Home and in the browser toolbar open
    // the one shared sheet.
    var vpnSheetOpen by remember { mutableStateOf(false) }
    // v1.3.3: the media sheet's open state lives here too — the
    // toolbar button that opens it moved up into the topBar with
    // the toolbar itself (see below), while the sheet stays hosted
    // by BrowserScreen.
    var mediaSheetOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    // Phase 4 pre-warm: build the first tab's WebView (and with it
    // Chromium's one-time initialization) while Home is still on
    // screen, so the first navigation never pays the cold-start cost
    // or shows a blank screen.
    val webViewSurface = colors.surface.toArgb()
    LaunchedEffect(Unit) {
        controller.warmUp(context, webViewSurface)
    }

    // VPN consent: VpnController.requestConnect returns the system's
    // consent intent when approval is still needed; on approval we
    // actually dial. (If it returns null, it already started.)
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vpn.beginConnect()
        }
    }
    val onVpnConnectRequest: (VpnServer) -> Unit = { server ->
        vpn.requestConnect(server)?.let { consentIntent ->
            vpnConsentLauncher.launch(consentIntent)
        }
    }

    // System Back: from the VPN screen it returns to Home content;
    // inside the browser it walks the page history first, then leaves
    // the browser for the Home screen; from any other section it
    // returns to Home.
    BackHandler(enabled = showVpn) {
        showVpn = false
    }
    BackHandler(enabled = !showVpn && selected == YpnSection.HOME && controller.browsing) {
        if (!controller.goBackInPage()) {
            controller.browsing = false
        }
    }
    BackHandler(enabled = !showVpn && selected != YpnSection.HOME) {
        selected = YpnSection.HOME
    }

    Scaffold(
        topBar = {
            // v1.3.2: the tab strip is Scaffold chrome, shown over
            // the Home section only (the VPN screen keeps its own
            // full-screen chrome, and Downloads / Menu / the Tabs
            // grid never carried it). Living here — outside the
            // section content — a loading page cannot cover it.
            if (selected == YpnSection.HOME && !showVpn) {
                Column {
                    TabStrip(
                        controller = controller,
                        onTabsGrid = { selected = YpnSection.TABS }
                    )
                    // v1.3.3: the browser toolbar and its 3dp
                    // progress line join the strip up here. The
                    // user's load-test video proved the strip safe
                    // in this slot while the toolbar, still inside
                    // BrowserScreen, kept vanishing for the whole
                    // first-load window — so the toolbar gets the
                    // same out-of-the-page's-reach hosting.
                    if (controller.browsing) {
                        val topTab = controller.activeTab
                        BrowserToolbar(
                            controller = controller,
                            tab = topTab,
                            onHome = { controller.browsing = false },
                            vpn = vpn,
                            onVpnSheetOpen = { vpnSheetOpen = true },
                            onMediaClick = { mediaSheetOpen = true }
                        )
                        if (topTab != null && topTab.isLoading) {
                            LinearProgressIndicator(
                                progress = { topTab.progress / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp),
                                color = colors.primary,
                                trackColor = LocalYpnPalette.current.surface2
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = colors.surface) {
                // v1.3: TABS is not a bottom-nav destination anymore
                // (the top strip + its grid button own tabs now).
                YpnSection.entries
                    .filter { it != YpnSection.TABS }
                    .forEach { section ->
                    val isSelected = section == selected
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            selected = section
                            showVpn = false
                            if (section == YpnSection.HOME) {
                                controller.browsing = false
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.primary,
                            selectedTextColor = colors.primary,
                            indicatorColor = colors.primaryContainer,
                            unselectedIconColor = LocalYpnPalette.current.muted,
                            unselectedTextColor = LocalYpnPalette.current.muted
                        ),
                        icon = {
                            if (section == YpnSection.DOWNLOADS && downloads.activeCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge(
                                            containerColor = LocalYpnPalette.current.data,
                                            contentColor = LocalYpnPalette.current.onAccent
                                        ) { Text(downloads.activeCount.toString()) }
                                    }
                                ) {
                                    Icon(imageVector = section.icon, contentDescription = section.label)
                                }
                            } else {
                                Icon(imageVector = section.icon, contentDescription = section.label)
                            }
                        },
                        label = { Text(section.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(innerPadding)
        ) {
            when (selected) {
                YpnSection.HOME -> {
                    if (showVpn) {
                        VpnScreen(
                            vpn = vpn,
                            onConnectRequest = onVpnConnectRequest,
                            onBack = { showVpn = false }
                        )
                    } else if (controller.browsing) {
                        BrowserScreen(
                            controller = controller,
                            downloads = downloads,
                            mediaSheetOpen = mediaSheetOpen,
                            onMediaSheetOpenChange = { mediaSheetOpen = it }
                        )
                    } else {
                        HomeScreen(
                            tabCount = controller.tabs.size,
                            onNavigate = { controller.openInNewTab(it) },
                            vpn = vpn,
                            onVpnOpen = { showVpn = true },
                            onVpnConnectRequest = onVpnConnectRequest,
                            onVpnSheetOpen = { vpnSheetOpen = true },
                            downloads = downloads,
                            onDownloadsOpen = { selected = YpnSection.DOWNLOADS }
                        )
                    }
                }
                YpnSection.TABS -> TabsScreen(
                    controller = controller,
                    onSelect = { id ->
                        controller.selectTab(id)
                        selected = YpnSection.HOME
                    },
                    onNewTab = {
                        controller.newTab(null)
                        controller.browsing = true
                        selected = YpnSection.HOME
                    }
                )
                YpnSection.DOWNLOADS -> DownloadsScreen(downloads = downloads)
                YpnSection.MENU -> MenuScreen(
                    controller = controller,
                    downloads = downloads,
                    themeId = themeId,
                    onThemeChange = onThemeChange,
                    onOpenUrl = { url ->
                        controller.openInNewTab(url)
                        selected = YpnSection.HOME
                    },
                    onShowDownloads = { selected = YpnSection.DOWNLOADS }
                )
            }

            if (vpnSheetOpen) {
                VpnSheet(
                    vpn = vpn,
                    onConnectRequest = onVpnConnectRequest,
                    onDismissRequest = { vpnSheetOpen = false }
                )
            }
        }
    }
}

/**
 * v1.3's top tab strip: the open tabs as a horizontally scrolling
 * row of chips in the Vivaldi / desktop-Chrome position, topping
 * the Home and Browser sections. Since v1.3.2 it is hosted in the
 * Scaffold's topBar slot (see [YpnApp]) — page loading can no
 * longer cover it — and its surface background is painted BEFORE
 * the status-bar inset is applied, so the theme colour runs
 * behind the status bar too while the chips sit below it. One
 * chip per tab — its page title, else the page's host, else
 * "New tab" — the active chip filled and underlined with the
 * accent; every chip carries its own ✕ (the controller's close
 * picks the neighbouring tab, exactly as in the Tabs grid);
 * tapping a chip switches to it and shows the browser. At the
 * row's end: ▦ opens the Tabs grid, + opens a new tab through
 * the same flow the grid uses. With zero tabs the row is just
 * those two buttons — the controller allows an empty tab list
 * (closing the last tab leaves the browser for Home), so the
 * strip never has to invent a tab.
 */
@Composable
private fun TabStrip(
    controller: BrowserController,
    onTabsGrid: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(controller.tabs, key = { it.id }) { tab ->
                TabChip(
                    label = tabStripLabel(tab),
                    active = tab.id == controller.activeTabId,
                    onClick = { controller.selectTab(tab.id) },
                    onClose = { controller.closeTab(tab.id) }
                )
            }
        }
        IconButton(onClick = onTabsGrid, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Filled.GridView,
                contentDescription = "All tabs",
                tint = palette.muted,
                modifier = Modifier.size(17.dp)
            )
        }
        IconButton(
            onClick = {
                controller.newTab(null)
                controller.browsing = true
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "New tab",
                tint = colors.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** One tab chip in the strip (see [TabStrip]). */
@Composable
private fun TabChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current
    Column(
        modifier = Modifier
            .widthIn(max = 150.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) palette.surface2 else colors.background)
            .border(
                1.dp,
                if (active) colors.primary else colors.outline,
                RoundedCornerShape(9.dp)
            )
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = if (active) colors.onSurface else palette.muted,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            IconButton(onClick = onClose, modifier = Modifier.size(20.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close tab",
                    tint = palette.muted,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        if (active) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(colors.primary)
            )
        }
    }
}

/** A chip's text: the page title, else its host, else "New tab". */
private fun tabStripLabel(tab: BrowserTab): String {
    val title = tab.title.trim()
    if (title.isNotEmpty() && title != "New Tab") return title
    val host = Uri.parse(tab.currentUrl).host?.takeIf { it.isNotBlank() }
    return host ?: "New tab"
}
