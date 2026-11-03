package app.ypn.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ypn.browser.browser.BrowserController
import app.ypn.browser.download.DownloadController
import app.ypn.browser.ui.theme.LocalYpnPalette
import app.ypn.browser.ui.theme.YpnThemeId

private enum class MenuPage { ROOT, BOOKMARKS, HISTORY }

/**
 * The real Menu (Phase 1): Bookmarks, History, the global Desktop mode
 * toggle, and the theme picker (shared with the Home screen). Since
 * Phase 3 it also holds the dual-network acceleration toggle.
 */
@Composable
fun MenuScreen(
    controller: BrowserController,
    downloads: DownloadController,
    themeId: YpnThemeId,
    onThemeChange: (YpnThemeId) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var page by remember { mutableStateOf(MenuPage.ROOT) }

    when (page) {
        MenuPage.BOOKMARKS -> BookmarksScreen(
            controller = controller,
            onOpen = onOpenUrl,
            onBack = { page = MenuPage.ROOT },
            modifier = modifier
        )
        MenuPage.HISTORY -> HistoryScreen(
            controller = controller,
            onOpen = onOpenUrl,
            onBack = { page = MenuPage.ROOT },
            modifier = modifier
        )
        MenuPage.ROOT -> MenuRoot(
            controller = controller,
            downloads = downloads,
            themeId = themeId,
            onThemeChange = onThemeChange,
            onShowBookmarks = { page = MenuPage.BOOKMARKS },
            onShowHistory = { page = MenuPage.HISTORY },
            modifier = modifier
        )
    }
}

@Composable
private fun MenuRoot(
    controller: BrowserController,
    downloads: DownloadController,
    themeId: YpnThemeId,
    onThemeChange: (YpnThemeId) -> Unit,
    onShowBookmarks: () -> Unit,
    onShowHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val palette = LocalYpnPalette.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        Text(
            text = "Menu",
            color = colors.onBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp)
        )

        MenuRow(
            icon = Icons.Filled.Bookmark,
            label = "Bookmarks",
            onClick = onShowBookmarks
        )
        MenuDivider()
        MenuRow(
            icon = Icons.Filled.History,
            label = "History",
            onClick = onShowHistory
        )
        MenuDivider()

        // Desktop mode — global, persisted, applies to every open tab.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.DesktopWindows,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Desktop mode",
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Desktop user-agent and wide page view",
                    color = palette.muted,
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = controller.desktopMode,
                onCheckedChange = { controller.applyDesktopMode(it) }
            )
        }
        MenuDivider()

        // Dual-network acceleration — downloads pull over Wi-Fi and
        // mobile data at the same time (Phase 3's signature feature).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Dual-network acceleration",
                    color = colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Downloads use Wi-Fi and mobile data together — " +
                        "faster, but mobile data is consumed",
                    color = palette.muted,
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = downloads.accelerationEnabled,
                onCheckedChange = { downloads.applyAccelerationEnabled(it) }
            )
        }

        ThemePicker(themeId = themeId, onThemeChange = onThemeChange)

        Text(
            text = "Browsing, the built-in VPN and downloads are all live (Phases 1–3).",
            color = palette.muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 16.dp, top = 14.dp)
        )
    }
}

@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            color = colors.onSurface,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = LocalYpnPalette.current.muted,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun MenuDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline)
    )
}
