package app.ypn.browser.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import app.ypn.browser.browser.BrowserController
import app.ypn.browser.ui.theme.LocalYpnPalette
import app.ypn.browser.ui.theme.YpnThemeId

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
 * theme. Downloads stays a placeholder until Phase 3.
 */
@Composable
fun YpnApp(themeId: YpnThemeId, onThemeChange: (YpnThemeId) -> Unit) {
    val context = LocalContext.current
    val controller = remember { BrowserController(context) }
    var selected by remember { mutableStateOf(YpnSection.HOME) }
    val colors = MaterialTheme.colorScheme

    // System Back: inside the browser it walks the page history first,
    // then leaves the browser for the Home screen; from any other
    // section it returns to Home.
    BackHandler(enabled = selected == YpnSection.HOME && controller.browsing) {
        if (!controller.goBackInPage()) {
            controller.browsing = false
        }
    }
    BackHandler(enabled = selected != YpnSection.HOME) {
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
                            if (section == YpnSection.DOWNLOADS) {
                                BadgedBox(
                                    badge = {
                                        Badge(
                                            containerColor = LocalYpnPalette.current.data,
                                            contentColor = LocalYpnPalette.current.onAccent
                                        ) { Text("2") }
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
                    if (controller.browsing) {
                        BrowserScreen(
                            controller = controller,
                            onHome = { controller.browsing = false }
                        )
                    } else {
                        HomeScreen(
                            themeId = themeId,
                            onThemeChange = onThemeChange,
                            tabCount = controller.tabs.size,
                            onNavigate = { controller.openInNewTab(it) }
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
                YpnSection.DOWNLOADS -> PlaceholderTab("Downloads arrive with the download manager in Phase 3.")
                YpnSection.MENU -> MenuScreen(
                    controller = controller,
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

@Composable
private fun PlaceholderTab(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = LocalYpnPalette.current.muted,
            fontSize = 13.sp
        )
    }
}
