package app.ypn.browser.ui

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
import androidx.compose.ui.unit.sp
import app.ypn.browser.ui.theme.LocalYpnPalette
import app.ypn.browser.ui.theme.YpnThemeId

private enum class YpnTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    TABS("Tabs", Icons.Filled.Tab),
    DOWNLOADS("Downloads", Icons.Filled.Download),
    MENU("Menu", Icons.Filled.MoreVert)
}

/**
 * App shell: the mockups' bottom navigation (Home / Tabs / Downloads with
 * badge / Menu) around the Phase 0 home screen. Non-home tabs are simple
 * placeholders until their phases land (browser core Phase 1, downloads
 * Phase 3).
 */
@Composable
fun YpnApp(themeId: YpnThemeId, onThemeChange: (YpnThemeId) -> Unit) {
    var selected by remember { mutableStateOf(YpnTab.HOME) }
    val colors = MaterialTheme.colorScheme

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = colors.surface) {
                YpnTab.entries.forEach { tab ->
                    val isSelected = tab == selected
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selected = tab },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = colors.primary,
                            selectedTextColor = colors.primary,
                            indicatorColor = colors.primaryContainer,
                            unselectedIconColor = LocalYpnPalette.current.muted,
                            unselectedTextColor = LocalYpnPalette.current.muted
                        ),
                        icon = {
                            if (tab == YpnTab.DOWNLOADS) {
                                BadgedBox(
                                    badge = {
                                        Badge(
                                            containerColor = LocalYpnPalette.current.data,
                                            contentColor = LocalYpnPalette.current.onAccent
                                        ) { Text("2") }
                                    }
                                ) {
                                    Icon(imageVector = tab.icon, contentDescription = tab.label)
                                }
                            } else {
                                Icon(imageVector = tab.icon, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label) }
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
                YpnTab.HOME -> HomeScreen(themeId = themeId, onThemeChange = onThemeChange)
                YpnTab.TABS -> PlaceholderTab("Tabs arrive with the browser core in Phase 1.")
                YpnTab.DOWNLOADS -> PlaceholderTab("Downloads arrive with the download manager in Phase 3.")
                YpnTab.MENU -> PlaceholderTab("Menu arrives in a later phase.")
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
