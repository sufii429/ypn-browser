package app.ypn.browser.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import app.ypn.browser.browser.BrowserController
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
 */
@Composable
fun YpnApp(themeId: YpnThemeId, onThemeChange: (YpnThemeId) -> Unit) {
    val context = LocalContext.current
    val downloads = remember { DownloadController.get(context) }
    val controller = remember { BrowserController(context, downloads) }
    val vpn = remember { VpnController(context) }
    var selected by remember { mutableStateOf(YpnSection.HOME) }
    var showVpn by remember { mutableStateOf(false) }
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
        bottomBar = {
            NavigationBar(containerColor = colors.surface) {
                YpnSection.entries.forEach { section ->
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
                            onHome = { controller.browsing = false }
                        )
                    } else {
                        HomeScreen(
                            tabCount = controller.tabs.size,
                            onNavigate = { controller.openInNewTab(it) },
                            vpn = vpn,
                            onVpnOpen = { showVpn = true },
                            onVpnConnectRequest = onVpnConnectRequest,
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
                    }
                )
            }
        }
    }
}
